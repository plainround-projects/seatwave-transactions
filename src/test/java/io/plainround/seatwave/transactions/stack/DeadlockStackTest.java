package io.plainround.seatwave.transactions.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.transactions.booking.HoldRequest;
import io.plainround.seatwave.transactions.booking.SeatLabel;
import io.plainround.seatwave.transactions.race.Gate;
import io.plainround.seatwave.transactions.race.Race;
import io.plainround.seatwave.transactions.store.Attempt;
import io.plainround.seatwave.transactions.store.CheckThenWriteHolds;
import io.plainround.seatwave.transactions.store.HoldStore;
import io.plainround.seatwave.transactions.store.RetryPolicy;
import io.plainround.seatwave.transactions.store.SqlStates;
import io.plainround.seatwave.transactions.store.Transactions;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Failure mode 3: two group holds for A-7 and A-8 in opposite orders. In client order they deadlock
 * and PostgreSQL aborts one; in lock order (sorted) one waits and then gets its answer. Needs the
 * stack; its lock_timeout (2 s) must be longer than deadlock_timeout (1 s by default), or the lock
 * timeout would fire before the deadlock check runs.
 */
@Tag("stack")
class DeadlockStackTest {

  private static final SeatLabel A7 = new SeatLabel("A-7");
  private static final SeatLabel A8 = new SeatLabel("A-8");
  private static final int FANS = 2;
  private static final Duration DEADLINE = Duration.ofSeconds(30);
  private static final Duration TINY = Duration.ofMillis(1);
  private static final long SEED = 13;

  private static StackDatabase database;
  private List<Long> fans;

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
    fans = database.registerCustomers(database.guarded, FANS);
  }

  @Test
  void oppositeOrdersDeadlockAndTheDatabaseAbortsOne() throws Exception {
    // One attempt only, so the abort is seen instead of retried away.
    Transactions oneAttempt =
        new Transactions(
            database.guarded,
            new RetryPolicy(1, TINY, TINY),
            database.settings.lockTimeout(),
            new SplittableRandom(SEED));
    CheckThenWriteHolds broken =
        new CheckThenWriteHolds(
            database.guarded,
            oneAttempt,
            StackDatabase.CLOCK,
            database.settings.holdDuration(),
            database.settings.holdLimit());
    Gate afterFirstSeat = new Gate(FANS, database.settings.gatePatience());
    List<HoldRequest> requests =
        List.of(
            new HoldRequest(fans.get(0), StackDatabase.EVENT, List.of(A7, A8)),
            new HoldRequest(fans.get(1), StackDatabase.EVENT, List.of(A8, A7)));

    List<Race.Finish<Attempt>> finishes =
        Race.run(
            FANS,
            fan -> () -> broken.holdInClientOrder(requests.get(fan), afterFirstSeat),
            DEADLINE);

    assertThat(finishes)
        .filteredOn(finish -> finish.equals(new Race.Finish.Returned<>(Attempt.GRANTED)))
        .hasSize(1);
    assertThat(finishes)
        .filteredOn(finish ->
            finish instanceof Race.Finish.Threw<Attempt>(SQLException failure)
                && SqlStates.DEADLOCK_DETECTED.equals(failure.getSQLState()))
        .hasSize(1);
  }

  @Test
  void theOneLockOrderMakesTheSecondFanWaitInsteadOfDeadlocking() throws Exception {
    HoldStore holds =
        new HoldStore(
            database.transactions(database.guarded),
            StackDatabase.CLOCK,
            database.settings.holdDuration());
    List<HoldRequest> requests =
        List.of(
            new HoldRequest(fans.get(0), StackDatabase.EVENT, List.of(A7, A8)),
            new HoldRequest(fans.get(1), StackDatabase.EVENT, List.of(A8, A7)));

    Map<Attempt, Long> outcomes =
        DoubleBookingStackTest.tallyOrFail(
            Race.run(
                FANS,
                fan -> () -> DoubleBookingStackTest.attemptOf(holds.holdSeats(requests.get(fan))),
                DEADLINE));

    assertThat(outcomes)
        .containsOnly(Map.entry(Attempt.GRANTED, 1L), Map.entry(Attempt.SEAT_TAKEN, 1L));
  }
}
