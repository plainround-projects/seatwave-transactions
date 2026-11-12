package io.plainround.seatwave.transactions.stack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.transactions.booking.Booking;
import io.plainround.seatwave.transactions.booking.ConfirmRefusal;
import io.plainround.seatwave.transactions.booking.Hold;
import io.plainround.seatwave.transactions.booking.HoldRefusal;
import io.plainround.seatwave.transactions.booking.HoldRequest;
import io.plainround.seatwave.transactions.booking.SeatLabel;
import io.plainround.seatwave.transactions.store.HoldStore;
import io.plainround.seatwave.transactions.store.SqlStates;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** One behavior of the production store per test, on the guarded schema. Needs the stack. */
@Tag("stack")
class HoldStoreStackTest {

  private static final SeatLabel A7 = new SeatLabel("A-7");
  private static final SeatLabel A8 = new SeatLabel("A-8");
  private static final SeatLabel MISSING = new SeatLabel("Z-1");
  // One second after a 300-second hold has run out.
  private static final Duration PAST_EXPIRY = Duration.ofSeconds(301);
  // Far longer than a lock wait takes to appear (milliseconds); shorter than lock_timeout (2 s).
  private static final Duration LOCK_WAIT_DEADLINE = Duration.ofMillis(1_500);
  private static final String INSERT_ACTIVE_HOLD =
      "INSERT INTO holds (hold_id, event_id, seat_label, customer_id, state, created_at, expires_at)"
          + " VALUES (?, 1, ?, ?, 'ACTIVE', ?, ?)";

  private static StackDatabase database;
  private HoldStore holds;
  private long fan;
  private long otherFan;

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
    holds = storeAt(StackDatabase.CLOCK);
    List<Long> fans = database.registerCustomers(database.guarded, 2);
    fan = fans.get(0);
    otherFan = fans.get(1);
  }

  @Test
  void holdsEverySeatOfAGroupInOneTransaction() throws Exception {
    Result<List<Hold>, HoldRefusal> outcome = holds.holdSeats(request(fan, A8, A7));

    assertThat(outcome.discardError())
        .hasValueSatisfying(granted -> assertThat(granted).hasSize(2));
  }

  // The holds come back in the order they were claimed, so this proves the claim order itself:
  // A-7 before A-8 although the fan asked for A-8 first. That one order is the deadlock fix.
  @Test
  void claimsTheSeatsOfAGroupInLockOrder() throws Exception {
    Result<List<Hold>, HoldRefusal> outcome = holds.holdSeats(request(fan, A8, A7));

    assertThat(outcome.discardError())
        .hasValueSatisfying(
            granted -> assertThat(granted).extracting(Hold::seat).containsExactly(A7, A8));
  }

  @Test
  void holdsNoSeatOfAGroupWhenOneIsTaken() throws Exception {
    holds.holdSeats(request(otherFan, A8));

    Result<List<Hold>, HoldRefusal> outcome = holds.holdSeats(request(fan, A7, A8));

    assertThat(outcome.isError()).isTrue();
    // A-7 was claimed first in lock order and then rolled back with the rest: all or nothing.
    assertThat(activeHoldsOf(fan)).isZero();
  }

  @Test
  void refusesASeatTheEventDoesNotHave() throws Exception {
    Result<List<Hold>, HoldRefusal> outcome = holds.holdSeats(request(fan, MISSING));

    assertThat(refusalOf(outcome)).isEqualTo(new HoldRefusal.UnknownSeat(MISSING));
  }

  @Test
  void letsAnotherFanTakeASeatWhoseHoldHasExpired() throws Exception {
    holds.holdSeats(request(otherFan, A7));
    HoldStore later = storeAt(Clock.offset(StackDatabase.CLOCK, PAST_EXPIRY));

    Result<List<Hold>, HoldRefusal> outcome = later.holdSeats(request(fan, A7));

    assertThat(outcome.isError()).isFalse();
    assertThat(
            StackDatabase.count(
                database.guarded,
                "SELECT count(*) FROM holds WHERE state = 'EXPIRED' AND customer_id = ?",
                otherFan))
        .isEqualTo(1);
  }

  @Test
  void confirmsAnActiveHoldAtTheSectionPrice() throws Exception {
    Hold hold = holds.holdSeats(request(fan, A7)).discardError().orElseThrow().getFirst();

    Result<Booking, ConfirmRefusal> outcome = holds.confirm(hold.holdId(), fan);

    assertThat(outcome.discardError())
        .hasValueSatisfying(booking -> assertThat(booking.price()).isEqualByComparingTo("80.00"));
  }

  @Test
  void refusesToConfirmTheSameHoldTwice() throws Exception {
    Hold hold = holds.holdSeats(request(fan, A7)).discardError().orElseThrow().getFirst();
    holds.confirm(hold.holdId(), fan);

    assertThat(holds.confirm(hold.holdId(), fan).isError()).isTrue();
  }

  // The last guard behind the claim index: even rows written by hand, around HoldStore, cannot
  // sell a sold seat again. A RELEASED hold on A-7 is allowed (the claim index is partial), so
  // only one_booking_per_seat stands between it and a second booking.
  @Test
  void refusesASecondBookingOfASoldSeatEvenWrittenByHand() throws Exception {
    Hold sold = holds.holdSeats(request(fan, A7)).discardError().orElseThrow().getFirst();
    holds.confirm(sold.holdId(), fan);
    UUID endedHold = UUID.randomUUID();
    try (Connection connection = database.guarded.getConnection();
        PreparedStatement hold =
            connection.prepareStatement(
                "INSERT INTO holds (hold_id, event_id, seat_label, customer_id, state, created_at,"
                    + " expires_at, ended_at) VALUES (?, 1, 'A-7', ?, 'RELEASED', ?, ?, ?)");
        PreparedStatement booking =
            connection.prepareStatement(
                "INSERT INTO bookings (booking_id, hold_id, event_id, seat_label, customer_id,"
                    + " price, currency, confirmed_at) VALUES (?, ?, 1, 'A-7', ?, 80.00, 'USD', ?)")) {
      OffsetDateTime now = StackDatabase.NOW.atOffset(ZoneOffset.UTC);
      hold.setObject(1, endedHold);
      hold.setLong(2, otherFan);
      hold.setObject(3, now);
      hold.setObject(4, now.plus(database.settings.holdDuration()));
      hold.setObject(5, now);
      hold.executeUpdate();
      booking.setObject(1, UUID.randomUUID());
      booking.setObject(2, endedHold);
      booking.setLong(3, otherFan);
      booking.setObject(4, now);

      assertThatThrownBy(booking::executeUpdate)
          .isInstanceOfSatisfying(
              SQLException.class,
              failure ->
                  assertThat(
                          SqlStates.isRefusalBy(
                              failure, SqlStates.UNIQUE_VIOLATION, "one_booking_per_seat"))
                      .isTrue());
    }
  }

  @Test
  void refusesToConfirmAnotherFansHold() throws Exception {
    Hold hold = holds.holdSeats(request(otherFan, A7)).discardError().orElseThrow().getFirst();

    assertThat(holds.confirm(hold.holdId(), fan).isError()).isTrue();
  }

  @Test
  void refusesToConfirmAnExpiredHold() throws Exception {
    Hold hold = holds.holdSeats(request(fan, A7)).discardError().orElseThrow().getFirst();
    HoldStore later = storeAt(Clock.offset(StackDatabase.CLOCK, PAST_EXPIRY));

    assertThat(later.confirm(hold.holdId(), fan).isError()).isTrue();
  }

  @Test
  void releasesOnceAndThenChangesNothing() throws Exception {
    Hold hold = holds.holdSeats(request(fan, A7)).discardError().orElseThrow().getFirst();

    assertThat(List.of(holds.release(hold.holdId(), fan), holds.release(hold.holdId(), fan)))
        .containsExactly(true, false);
  }

  // The trigger's lock, with the bad interleaving forced: the fan has 3 holds, an open transaction
  // inserts the 4th and does not commit, and a 5th hold starts meanwhile. Without the lock the 5th
  // would count 3 (the 4th is not committed) and commit too; with it, the 5th waits for the
  // customer's row, counts 4 once the 4th commits, and is refused.
  @Test
  void makesAConcurrentFifthHoldWaitForTheFourthAndThenRefusesIt() throws Exception {
    for (SeatLabel seat :
        List.of(new SeatLabel("C-1"), new SeatLabel("C-2"), new SeatLabel("C-3"))) {
      holds.holdSeats(request(fan, seat)).discardError().orElseThrow();
    }
    try (Connection fourth = database.guarded.getConnection();
        PreparedStatement insert = fourth.prepareStatement(INSERT_ACTIVE_HOLD);
        ExecutorService fifthFan = Executors.newVirtualThreadPerTaskExecutor()) {
      fourth.setAutoCommit(false);
      bindActiveHold(insert, "C-4", fan);
      insert.executeUpdate();

      Future<Result<List<Hold>, HoldRefusal>> fifth =
          fifthFan.submit(() -> holds.holdSeats(request(fan, new SeatLabel("C-5"))));
      // Either the 5th hold is seen waiting on a lock, or it finished without waiting (the bug).
      await()
          .atMost(LOCK_WAIT_DEADLINE)
          .until(() -> fifth.isDone() || applicationSessionsWaitingOnALock() > 0);
      fourth.commit();

      assertThat(refusalOf(fifth.get())).isEqualTo(new HoldRefusal.LimitReached());
    }
    assertThat(
            StackDatabase.count(
                database.guarded,
                "SELECT count(*) FROM holds WHERE customer_id = ? AND state = 'ACTIVE'",
                fan))
        .isEqualTo(database.settings.holdLimit());
  }

  @Test
  void refusesAHoldWrittenAtRepeatableRead() throws Exception {
    try (Connection connection = database.guarded.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO holds (hold_id, event_id, seat_label, customer_id, state, created_at, expires_at)"
                    + " VALUES (?, 1, 'A-9', ?, 'ACTIVE', ?, ?)")) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      insert.setObject(1, UUID.randomUUID());
      insert.setLong(2, fan);
      insert.setObject(3, StackDatabase.NOW.atOffset(ZoneOffset.UTC));
      insert.setObject(
          4, StackDatabase.NOW.plus(database.settings.holdDuration()).atOffset(ZoneOffset.UTC));

      // feature_not_supported: the trigger refuses the level at which its count could be stale.
      assertThatThrownBy(insert::executeUpdate)
          .isInstanceOf(SQLException.class)
          .extracting(failure -> ((SQLException) failure).getSQLState())
          .isEqualTo("0A000");
      // No rollback here: HikariCP counts SQLSTATE 0A000 as a broken connection and has already
      // closed this one, which rolled the transaction back. A hold at the wrong level is a
      // programmer error, so losing one pooled connection to it costs nothing that matters.
    }
  }

  @Test
  void neverLetsTheApplicationRoleDeleteABooking() throws Exception {
    try (Connection connection = database.guarded.getConnection();
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(() -> statement.execute("DELETE FROM bookings"))
          .isInstanceOf(SQLException.class)
          .extracting(failure -> ((SQLException) failure).getSQLState())
          .isEqualTo("42501");
    }
  }

  private void bindActiveHold(PreparedStatement insert, String seat, long customer)
      throws SQLException {
    insert.setObject(1, UUID.randomUUID());
    insert.setString(2, seat);
    insert.setLong(3, customer);
    insert.setObject(4, StackDatabase.NOW.atOffset(ZoneOffset.UTC));
    insert.setObject(
        5, StackDatabase.NOW.plus(database.settings.holdDuration()).atOffset(ZoneOffset.UTC));
  }

  // pg_stat_activity shows the wait of sessions of the same role, so the application role sees
  // its own pool's sessions.
  private static long applicationSessionsWaitingOnALock() throws SQLException {
    return StackDatabase.count(
        database.guarded,
        "SELECT count(*) FROM pg_stat_activity WHERE usename = current_user"
            + " AND wait_event_type = 'Lock'");
  }

  private HoldStore storeAt(Clock clock) {
    return new HoldStore(
        database.transactions(database.guarded),
        clock.withZone(ZoneOffset.UTC),
        database.settings.holdDuration());
  }

  private static HoldRequest request(long customer, SeatLabel... seats) {
    return new HoldRequest(customer, StackDatabase.EVENT, List.of(seats));
  }

  private static HoldRefusal refusalOf(Result<List<Hold>, HoldRefusal> outcome) {
    return switch (outcome) {
      case Result.Error<List<Hold>, HoldRefusal>(HoldRefusal refusal) -> refusal;
      case Result.Success<List<Hold>, HoldRefusal>(List<Hold> granted) ->
          throw new AssertionError(
              "expected a refusal, but " + granted.size() + " seat(s) were held");
    };
  }

  private static long activeHoldsOf(long customer) throws SQLException {
    return StackDatabase.count(
        database.guarded,
        "SELECT count(*) FROM holds WHERE customer_id = ? AND state = 'ACTIVE'",
        customer);
  }
}
