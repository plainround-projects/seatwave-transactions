package io.plainround.seatwave.transactions.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.transactions.labs.SectionPrices;
import io.plainround.seatwave.transactions.race.Gate;
import io.plainround.seatwave.transactions.race.Race;
import io.plainround.seatwave.transactions.store.IsolationLevel;
import io.plainround.seatwave.transactions.store.Transactions;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Lab 2's solution: two clerks raise section A's price by $5.00 at once. Needs the stack. */
@Tag("stack")
class LostUpdateStackTest {

  private static final SectionPrices.Section SECTION_A =
      new SectionPrices.Section(StackDatabase.EVENT, "A");
  private static final BigDecimal RAISE = new BigDecimal("5.00");
  private static final int CLERKS = 2;
  private static final int OPTIMISTIC_ATTEMPTS = 5;
  // The fixes have no gate to force the bad interleaving (a gate after a locking read would wait
  // forever for the clerk the lock holds back), so they are tested by volume instead: 8 clerks
  // raise by $0.05 25 times each, $10.00 in all, the same total as the two $5.00 raises. Broken
  // code loses some of those 200 raises on practically every run; the mutation check confirmed it.
  private static final int BUSY_CLERKS = 8;
  private static final int RAISES_PER_BUSY_CLERK = 25;
  private static final BigDecimal SMALL_RAISE = new BigDecimal("0.05");
  // A busy version-column clerk may lose many rounds in a row; 1,000 is far more than it needs.
  private static final int BUSY_OPTIMISTIC_ATTEMPTS = 1_000;
  private static final Duration DEADLINE = Duration.ofSeconds(30);
  private static final String PRICE_IN_CENTS =
      "SELECT (price * 100)::bigint FROM sections WHERE event_id = 1 AND section_code = 'A'";
  // $80.00 plus $10.00 of raises (two of $5.00, or 200 of $0.05), in cents.
  private static final long BOTH_RAISES_CENTS = 9_000;
  private static final long ONE_RAISE_CENTS = 8_500;

  private static StackDatabase database;
  private SectionPrices prices;

  @BeforeAll
  static void openTheLabDatabase() throws Exception {
    database = StackDatabase.open();
  }

  @AfterAll
  static void closeIt() {
    database.close();
  }

  @BeforeEach
  void startAtEightyDollars() throws Exception {
    database.reset();
    prices = new SectionPrices(database.transactions(database.guarded), OPTIMISTIC_ATTEMPTS);
  }

  @Test
  void readModifyWriteLosesOneRaise() throws Exception {
    Gate afterRead = new Gate(CLERKS, database.settings.gatePatience());

    Race.run(
        CLERKS,
        clerk ->
            () -> {
              prices.raiseByReadModifyWrite(
                  SECTION_A, RAISE, IsolationLevel.READ_COMMITTED, afterRead);
              return true;
            },
        DEADLINE);

    assertThat(priceInCents()).isEqualTo(ONE_RAISE_CENTS);
  }

  // The isolation table's row "lost update": possible at read committed (above), prevented at the
  // two stronger levels, where the second writer is aborted with 40001 and its retry reads 85.00.
  @ParameterizedTest
  @EnumSource(
      value = IsolationLevel.class,
      names = {"REPEATABLE_READ", "SERIALIZABLE"})
  void theSameReadModifyWriteKeepsBothRaisesAtAStrongerLevel(IsolationLevel level)
      throws Exception {
    Transactions transactions = database.transactions(database.guarded);
    SectionPrices retried = new SectionPrices(transactions, OPTIMISTIC_ATTEMPTS);
    Gate afterRead = new Gate(CLERKS, database.settings.gatePatience());

    Race.run(
        CLERKS,
        clerk ->
            () -> {
              retried.raiseByReadModifyWrite(SECTION_A, RAISE, level, afterRead);
              return true;
            },
        DEADLINE);

    assertThat(priceInCents()).isEqualTo(BOTH_RAISES_CENTS);
    assertThat(transactions.abortedByDatabase()).isEqualTo(1);
  }

  @Test
  void aRowLockKeepsEveryRaise() throws Exception {
    raiseByEveryBusyClerk(() -> {
      prices.raiseWithRowLock(SECTION_A, SMALL_RAISE);
      return true;
    });

    assertThat(priceInCents()).isEqualTo(BOTH_RAISES_CENTS);
  }

  @Test
  void aVersionColumnKeepsEveryRaise() throws Exception {
    SectionPrices patient =
        new SectionPrices(database.transactions(database.guarded), BUSY_OPTIMISTIC_ATTEMPTS);

    raiseByEveryBusyClerk(() -> patient.raiseWithVersion(SECTION_A, SMALL_RAISE));

    assertThat(priceInCents()).isEqualTo(BOTH_RAISES_CENTS);
    assertThat(patient.versionConflicts()).isPositive();
  }

  @Test
  void anInPlaceUpdateKeepsEveryRaise() throws Exception {
    raiseByEveryBusyClerk(() -> {
      prices.raiseInPlace(SECTION_A, SMALL_RAISE);
      return true;
    });

    assertThat(priceInCents()).isEqualTo(BOTH_RAISES_CENTS);
  }

  // Every clerk raises the same row RAISES_PER_BUSY_CLERK times; a raise that reports false or
  // throws fails the test, so the price check below is about lost raises only.
  private static void raiseByEveryBusyClerk(Callable<Boolean> raise) throws Exception {
    List<Race.Finish<Integer>> finishes =
        Race.run(
            BUSY_CLERKS,
            clerk ->
                () -> {
                  int written = 0;
                  for (int round = 0; round < RAISES_PER_BUSY_CLERK; round++) {
                    written += raise.call() ? 1 : 0;
                  }
                  return written;
                },
            DEADLINE);

    assertThat(finishes).containsOnly(new Race.Finish.Returned<>(RAISES_PER_BUSY_CLERK));
  }

  private static long priceInCents() throws Exception {
    return StackDatabase.count(database.guarded, PRICE_IN_CENTS);
  }
}
