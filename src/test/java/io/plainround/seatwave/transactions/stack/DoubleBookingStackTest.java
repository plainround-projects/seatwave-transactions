package io.plainround.seatwave.transactions.stack;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import com.zaxxer.hikari.HikariDataSource;
import io.plainround.seatwave.transactions.booking.Hold;
import io.plainround.seatwave.transactions.booking.HoldRefusal;
import io.plainround.seatwave.transactions.booking.HoldRequest;
import io.plainround.seatwave.transactions.booking.SeatLabel;
import io.plainround.seatwave.transactions.race.Gate;
import io.plainround.seatwave.transactions.race.Race;
import io.plainround.seatwave.transactions.store.Attempt;
import io.plainround.seatwave.transactions.store.CheckThenWriteHolds;
import io.plainround.seatwave.transactions.store.HoldStore;
import io.plainround.seatwave.transactions.store.IsolationLevel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The module's artifact: 200 racers on one seat and 50 on one customer's limit, first with the
 * broken check-then-write code (the anomaly happens), then on the guarded schema (the database
 * refuses it), then with the production code (the anomaly cannot happen). Needs the stack.
 */
@Tag("stack")
class DoubleBookingStackTest {

  private static final SeatLabel HOT_SEAT = new SeatLabel("A-7");
  private static final String CLAIMS_ON_SEAT =
      "SELECT count(*) FROM holds WHERE event_id = ? AND seat_label = ? AND state = 'ACTIVE'";
  private static final String ACTIVE_HOLDS_OF_CUSTOMER =
      "SELECT count(*) FROM holds WHERE customer_id = ? AND state = 'ACTIVE'";
  private static final Duration RACE_DEADLINE = Duration.ofSeconds(60);

  private static StackDatabase database;

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
  }

  @Test
  void checkThenWriteWithoutTheGuardSellsOneSeatToEveryRacer() throws Exception {
    int racers = database.settings.seatRacers();
    Map<Attempt, Long> outcomes = raceOnHotSeat(database.unguarded, racers);

    assertThat(outcomes).containsEntry(Attempt.GRANTED, (long) racers);
    assertThat(claimsOnHotSeat(database.unguarded)).isEqualTo(racers);
  }

  @Test
  void theUniqueIndexRefusesEveryClaimButOneWhenTheCodeIsBroken() throws Exception {
    int racers = database.settings.seatRacers();
    Map<Attempt, Long> outcomes = raceOnHotSeat(database.guarded, racers);

    assertThat(outcomes)
        .containsEntry(Attempt.GRANTED, 1L)
        .containsEntry(Attempt.REFUSED_BY_DATABASE, racers - 1L);
    assertThat(claimsOnHotSeat(database.guarded)).isEqualTo(1);
  }

  @Test
  void theProductionHoldGrantsTheSeatOnceAndRefusesTheRestAsTaken() throws Exception {
    int racers = database.settings.seatRacers();
    List<Long> fans = database.registerCustomers(database.guarded, racers);
    HoldStore holds =
        new HoldStore(
            database.transactions(database.guarded),
            StackDatabase.CLOCK,
            database.settings.holdDuration());

    Map<Attempt, Long> outcomes =
        tallyOrFail(
            Race.run(
                racers,
                fan ->
                    () ->
                        attemptOf(
                            holds.holdSeats(
                                new HoldRequest(
                                    fans.get(fan), StackDatabase.EVENT, List.of(HOT_SEAT)))),
                RACE_DEADLINE));

    // Nothing reached the index as a second claim: the conflict was answered, not raised.
    assertThat(outcomes)
        .containsOnly(Map.entry(Attempt.GRANTED, 1L), Map.entry(Attempt.SEAT_TAKEN, racers - 1L));
    assertThat(claimsOnHotSeat(database.guarded)).isEqualTo(1);
  }

  @Test
  void countThenInsertAtRepeatableReadLetsOneCustomerHoldEverySeatItAsks() throws Exception {
    int racers = database.settings.limitRacers();
    long customer = database.registerCustomers(database.unguarded, 1).getFirst();
    CheckThenWriteHolds broken = brokenStore(database.unguarded);
    Gate afterCount = new Gate(racers, database.settings.gatePatience());

    Map<Attempt, Long> outcomes =
        race(
            racers,
            seat ->
                () ->
                    broken.holdAfterCountCheck(
                        customer,
                        StackDatabase.EVENT,
                        seat,
                        IsolationLevel.REPEATABLE_READ,
                        afterCount));

    assertThat(outcomes).containsEntry(Attempt.GRANTED, (long) racers);
    assertThat(StackDatabase.count(database.unguarded, ACTIVE_HOLDS_OF_CUSTOMER, customer))
        .isEqualTo(racers);
  }

  @Test
  void theProductionHoldKeepsOneCustomerToFourActiveHolds() throws Exception {
    int racers = database.settings.limitRacers();
    long customer = database.registerCustomers(database.guarded, 1).getFirst();
    HoldStore holds =
        new HoldStore(
            database.transactions(database.guarded),
            StackDatabase.CLOCK,
            database.settings.holdDuration());

    int limit = database.settings.holdLimit();
    Map<Attempt, Long> outcomes =
        race(
            racers,
            seat ->
                () ->
                    attemptOf(
                        holds.holdSeats(
                            new HoldRequest(customer, StackDatabase.EVENT, List.of(seat)))));

    assertThat(outcomes)
        .containsOnly(
            Map.entry(Attempt.GRANTED, (long) limit),
            Map.entry(Attempt.LIMIT_REACHED, (long) racers - limit));
    assertThat(StackDatabase.count(database.guarded, ACTIVE_HOLDS_OF_CUSTOMER, customer))
        .isEqualTo(database.settings.holdLimit());
  }

  private static Map<Attempt, Long> raceOnHotSeat(HikariDataSource pool, int racers)
      throws Exception {
    List<Long> fans = database.registerCustomers(pool, racers);
    CheckThenWriteHolds broken = brokenStore(pool);
    Gate afterCheck = new Gate(racers, database.settings.gatePatience());
    List<Race.Finish<Attempt>> finishes =
        Race.run(
            racers,
            fan ->
                () ->
                    broken.holdAfterJavaCheck(
                        fans.get(fan), StackDatabase.EVENT, HOT_SEAT, afterCheck),
            RACE_DEADLINE);
    return tallyOrFail(finishes);
  }

  static Map<Attempt, Long> race(int racers, Function<SeatLabel, Callable<Attempt>> racer)
      throws Exception {
    return tallyOrFail(Race.run(racers, index -> racer.apply(distinctSeat(index)), RACE_DEADLINE));
  }

  // Racer N wants seat B-(N+1): a different seat each, so the seat rule never interferes with the
  // limit rule under test.
  static SeatLabel distinctSeat(int racerIndex) {
    return new SeatLabel("B-" + (racerIndex + 1));
  }

  private static CheckThenWriteHolds brokenStore(HikariDataSource pool) {
    return new CheckThenWriteHolds(
        pool,
        database.transactions(pool),
        StackDatabase.CLOCK,
        database.settings.holdDuration(),
        database.settings.holdLimit());
  }

  private static long claimsOnHotSeat(HikariDataSource pool) throws Exception {
    return StackDatabase.count(pool, CLAIMS_ON_SEAT, StackDatabase.EVENT, HOT_SEAT.text());
  }

  // The production store answers with a Result; the tests count it with the same names.
  static Attempt attemptOf(Result<List<Hold>, HoldRefusal> outcome) {
    return outcome
        .map(granted -> Attempt.GRANTED)
        .recoverError(refusal ->
            switch (refusal) {
              case HoldRefusal.SeatTaken taken -> Attempt.SEAT_TAKEN;
              case HoldRefusal.LimitReached limit -> Attempt.LIMIT_REACHED;
              case HoldRefusal.UnknownSeat unknown ->
                  throw new AssertionError("the seed has no seat " + unknown.seat());
            });
  }

  // A racer that threw makes the tally meaningless, so the test fails with that racer's reason.
  static Map<Attempt, Long> tallyOrFail(List<Race.Finish<Attempt>> finishes) {
    return finishes.stream()
        .map(finish ->
            switch (finish) {
              case Race.Finish.Returned<Attempt>(Attempt attempt) -> attempt;
              case Race.Finish.Threw<Attempt>(Exception failure) ->
                  throw new AssertionError("a racer failed: " + failure, failure);
            })
        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
  }
}
