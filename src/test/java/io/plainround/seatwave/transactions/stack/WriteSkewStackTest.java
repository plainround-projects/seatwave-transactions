package io.plainround.seatwave.transactions.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.transactions.race.Gate;
import io.plainround.seatwave.transactions.store.Attempt;
import io.plainround.seatwave.transactions.store.CheckThenWriteHolds;
import io.plainround.seatwave.transactions.store.IsolationLevel;
import io.plainround.seatwave.transactions.store.Transactions;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Lab 3's solution: the four-holds rule under each isolation level and lock, with the worst
 * interleaving forced by a gate, on the unguarded copy (no trigger), so only the Java code and the
 * isolation level decide. Needs the stack.
 */
@Tag("stack")
class WriteSkewStackTest {

  private static final String ACTIVE_HOLDS_OF_CUSTOMER =
      "SELECT count(*) FROM holds WHERE customer_id = ? AND state = 'ACTIVE'";

  private static StackDatabase database;
  private long customer;
  private int racers;

  @BeforeAll
  static void openTheLabDatabase() throws Exception {
    database = StackDatabase.open();
  }

  @AfterAll
  static void closeIt() {
    database.close();
  }

  @BeforeEach
  void startEmpty() throws Exception {
    database.reset();
    customer = database.registerCustomers(database.unguarded, 1).getFirst();
    racers = database.settings.limitRacers();
  }

  @Test
  void readCommittedWithoutALockBreaksTheLimit() throws Exception {
    raceCountThenInsert(
        IsolationLevel.READ_COMMITTED, store(database.transactions(database.unguarded)));

    assertThat(activeHolds()).isEqualTo(racers);
  }

  @Test
  void aLockOnTheCustomerRowAtReadCommittedKeepsTheLimit() throws Exception {
    CheckThenWriteHolds broken = store(database.transactions(database.unguarded));
    Gate afterStart = new Gate(racers, database.settings.gatePatience());

    DoubleBookingStackTest.race(
        racers,
        seat ->
            () ->
                broken.holdAfterLockThenCount(
                    customer,
                    StackDatabase.EVENT,
                    seat,
                    IsolationLevel.READ_COMMITTED,
                    afterStart));

    assertThat(activeHolds()).isEqualTo(database.settings.holdLimit());
  }

  @Test
  void theSameLockAtRepeatableReadStillBreaksTheLimit() throws Exception {
    CheckThenWriteHolds broken = store(database.transactions(database.unguarded));
    Gate afterStart = new Gate(racers, database.settings.gatePatience());

    DoubleBookingStackTest.race(
        racers,
        seat ->
            () ->
                broken.holdAfterLockThenCount(
                    customer,
                    StackDatabase.EVENT,
                    seat,
                    IsolationLevel.REPEATABLE_READ,
                    afterStart));

    // Each count read the snapshot taken before the gate, so every racer counted zero.
    assertThat(activeHolds()).isEqualTo(racers);
  }

  @Test
  void serializableKeepsTheLimitByAbortingAndRetrying() throws Exception {
    Transactions transactions = database.transactions(database.unguarded);

    Map<Attempt, Long> outcomes =
        raceCountThenInsert(IsolationLevel.SERIALIZABLE, store(transactions));

    // How many aborts a run needs depends on timing, so it is reported, not asserted: Gradle keeps
    // this line in the test report, and the module quotes the range over many runs.
    System.out.printf(
        Locale.ROOT,
        "serializable limit race: %d racers, aborted by the database %d, retries %d%n",
        racers,
        transactions.abortedByDatabase(),
        transactions.retries());
    assertThat(activeHolds()).isEqualTo(database.settings.holdLimit());
    assertThat(outcomes).containsEntry(Attempt.GRANTED, (long) database.settings.holdLimit());
    assertThat(transactions.abortedByDatabase()).isPositive();
  }

  private Map<Attempt, Long> raceCountThenInsert(IsolationLevel level, CheckThenWriteHolds broken)
      throws Exception {
    Gate afterCount = new Gate(racers, database.settings.gatePatience());
    return DoubleBookingStackTest.race(
        racers,
        seat ->
            () ->
                broken.holdAfterCountCheck(customer, StackDatabase.EVENT, seat, level, afterCount));
  }

  private static CheckThenWriteHolds store(Transactions transactions) {
    return new CheckThenWriteHolds(
        database.unguarded,
        transactions,
        StackDatabase.CLOCK,
        database.settings.holdDuration(),
        database.settings.holdLimit());
  }

  private long activeHolds() throws Exception {
    return StackDatabase.count(database.unguarded, ACTIVE_HOLDS_OF_CUSTOMER, customer);
  }
}
