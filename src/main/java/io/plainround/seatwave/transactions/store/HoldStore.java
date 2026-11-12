package io.plainround.seatwave.transactions.store;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.transactions.booking.Booking;
import io.plainround.seatwave.transactions.booking.ConfirmRefusal;
import io.plainround.seatwave.transactions.booking.Hold;
import io.plainround.seatwave.transactions.booking.HoldRefusal;
import io.plainround.seatwave.transactions.booking.HoldRequest;
import io.plainround.seatwave.transactions.booking.SeatLabel;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * SeatWave's holds and bookings on PostgreSQL: the production way. Every operation is one short
 * transaction at read committed, and the rules are decided by the database, so they hold for every
 * instance and every code path:
 *
 * <ul>
 *   <li>one claim per seat: the partial unique index {@code one_claim_per_seat}, used by an {@code
 *       INSERT ... ON CONFLICT DO NOTHING} that changes one row or none;
 *   <li>four active holds per customer: the trigger {@code hold_limit_per_customer}, which locks
 *       the customer's row and counts;
 *   <li>a confirm turns exactly one active, unexpired hold of this customer into a booking, in one
 *       transaction with the booking row.
 * </ul>
 *
 * <p>Thread-safe: it holds no mutable state; each call borrows its own connection.
 */
public final class HoldStore {

  private static final String EXPIRE_STALE_HOLD =
      """
      UPDATE holds SET state = 'EXPIRED', ended_at = ?
      WHERE event_id = ? AND seat_label = ? AND state = 'ACTIVE' AND expires_at <= ?
      """;

  // ON CONFLICT names the partial unique index by its columns and predicate. A conflict is the
  // expected "seat taken" answer, so it returns no row instead of raising an error.
  private static final String CLAIM_SEAT =
      """
      INSERT INTO holds (hold_id, event_id, seat_label, customer_id, state, created_at, expires_at)
      VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
      ON CONFLICT (event_id, seat_label) WHERE state IN ('ACTIVE', 'CONFIRMED') DO NOTHING
      """;

  private static final String END_ACTIVE_HOLD_AS_CONFIRMED =
      """
      UPDATE holds SET state = 'CONFIRMED', ended_at = ?
      WHERE hold_id = ? AND customer_id = ? AND state = 'ACTIVE' AND expires_at > ?
      RETURNING seat_label
      """;

  // The price paid is read from the section at the moment of the confirm, in the same
  // transaction, so a later price change never rewrites what this fan paid.
  private static final String INSERT_BOOKING =
      """
      INSERT INTO bookings
        (booking_id, hold_id, event_id, seat_label, customer_id, price, currency, confirmed_at)
      SELECT ?, holds.hold_id, holds.event_id, holds.seat_label, holds.customer_id,
             sections.price, sections.currency, ?
      FROM holds
      JOIN seats ON seats.event_id = holds.event_id AND seats.seat_label = holds.seat_label
      JOIN sections ON sections.event_id = seats.event_id
                   AND sections.section_code = seats.section_code
      WHERE holds.hold_id = ?
      RETURNING price, currency
      """;

  private static final String RELEASE_ACTIVE_HOLD =
      """
      UPDATE holds SET state = 'RELEASED', ended_at = ?
      WHERE hold_id = ? AND customer_id = ? AND state = 'ACTIVE'
      """;

  private final Transactions transactions;
  private final Clock clock;
  private final Duration holdDuration;

  /**
   * Creates the store.
   *
   * @param transactions the runner, with the application role's pool
   * @param clock the clock every timestamp comes from, so tests control time
   * @param holdDuration how long a hold lasts; Module 1 set 300 seconds
   */
  public HoldStore(Transactions transactions, Clock clock, Duration holdDuration) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.holdDuration = Objects.requireNonNull(holdDuration, "holdDuration");
    if (holdDuration.isNegative() || holdDuration.isZero()) {
      throw new IllegalArgumentException("holdDuration must be positive, but was " + holdDuration);
    }
  }

  /**
   * Holds every seat of the request, or none of them.
   *
   * <p>Read committed is enough here, and it is chosen on purpose: no decision rests on a value
   * read earlier in the transaction. The seat rule is decided by the unique index at the insert,
   * and the limit rule by the trigger, which locks before it counts.
   *
   * @param request the seats, the event and the customer
   * @return the holds, one per seat in lock order; or why nothing was held
   * @throws SQLException if the database fails, a lock wait passes {@code lock_timeout} (SQLSTATE
   *     55P03, which the API answers with 503), or every retry was aborted
   */
  public Result<List<Hold>, HoldRefusal> holdSeats(HoldRequest request) throws SQLException {
    Objects.requireNonNull(request, "request");
    Instant now = clock.instant();
    return transactions.inTransaction(
        IsolationLevel.READ_COMMITTED, connection -> claimAll(connection, request, now));
  }

  /**
   * Turns one active hold into a booking.
   *
   * @param holdId the hold
   * @param customerId the customer who must own it
   * @return the booking, or why nothing was sold
   * @throws SQLException if the database fails
   */
  public Result<Booking, ConfirmRefusal> confirm(UUID holdId, long customerId) throws SQLException {
    Objects.requireNonNull(holdId, "holdId");
    Instant now = clock.instant();
    return transactions.inTransaction(
        IsolationLevel.READ_COMMITTED,
        connection -> confirmIn(connection, holdId, customerId, now));
  }

  /**
   * Releases one active hold. Safe to repeat: a second call finds nothing active and changes
   * nothing, which is what Module 7's {@code DELETE /v1/holds/{holdId}} promises.
   *
   * @param holdId the hold
   * @param customerId the customer who must own it
   * @return true if this call ended the hold, false if it was not active
   * @throws SQLException if the database fails
   */
  public boolean release(UUID holdId, long customerId) throws SQLException {
    Objects.requireNonNull(holdId, "holdId");
    Instant now = clock.instant();
    // A release has no expected refusal: "nothing was active" is the answer false, so the error
    // type is Void and never used.
    Result<Boolean, Void> released =
        transactions.inTransaction(
            IsolationLevel.READ_COMMITTED,
            connection -> {
              try (PreparedStatement update = connection.prepareStatement(RELEASE_ACTIVE_HOLD)) {
                update.setObject(1, utc(now));
                update.setObject(2, holdId);
                update.setLong(3, customerId);
                return Result.success(update.executeUpdate() == 1);
              }
            });
    return released.recoverError(never -> false);
  }

  private Result<List<Hold>, HoldRefusal> claimAll(
      Connection connection, HoldRequest request, Instant now) throws SQLException {
    Instant expiresAt = now.plus(holdDuration);
    List<Hold> granted = new ArrayList<>();
    // One fixed order for every transaction: two group holds that share seats queue on the first
    // shared seat instead of each holding one and waiting for the other (a deadlock).
    for (SeatLabel seat : request.seatsInLockOrder()) {
      expireStaleHold(connection, request.eventId(), seat, now);
      UUID holdId = UUID.randomUUID();
      try {
        if (!claimSeat(connection, request, seat, holdId, now, expiresAt)) {
          // Returning the refusal makes Transactions roll back the seats claimed so far.
          return Result.error(new HoldRefusal.SeatTaken(seat));
        }
      } catch (SQLException refused) {
        if (SqlStates.isRefusalBy(refused, SqlStates.CHECK_VIOLATION, "hold_limit_per_customer")) {
          return Result.error(new HoldRefusal.LimitReached());
        }
        if (SqlStates.isRefusalBy(
            refused, SqlStates.FOREIGN_KEY_VIOLATION, "hold_names_a_real_seat")) {
          return Result.error(new HoldRefusal.UnknownSeat(seat));
        }
        throw refused;
      }
      granted.add(new Hold(holdId, request.eventId(), seat, request.customerId(), expiresAt));
    }
    return Result.success(List.copyOf(granted));
  }

  // Lazy expiry (Module 1): an old hold leaves the unique index only when someone wants the seat.
  private static void expireStaleHold(
      Connection connection, long eventId, SeatLabel seat, Instant now) throws SQLException {
    try (PreparedStatement update = connection.prepareStatement(EXPIRE_STALE_HOLD)) {
      update.setObject(1, utc(now));
      update.setLong(2, eventId);
      update.setString(3, seat.text());
      update.setObject(4, utc(now));
      update.executeUpdate();
    }
  }

  private static boolean claimSeat(
      Connection connection,
      HoldRequest request,
      SeatLabel seat,
      UUID holdId,
      Instant now,
      Instant expiresAt)
      throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(CLAIM_SEAT)) {
      insert.setObject(1, holdId);
      insert.setLong(2, request.eventId());
      insert.setString(3, seat.text());
      insert.setLong(4, request.customerId());
      insert.setObject(5, utc(now));
      insert.setObject(6, utc(expiresAt));
      // 1: this transaction now owns the seat's claim; 0: a committed or in-flight claim exists.
      return insert.executeUpdate() == 1;
    }
  }

  private static Result<Booking, ConfirmRefusal> confirmIn(
      Connection connection, UUID holdId, long customerId, Instant now) throws SQLException {
    SeatLabel seat;
    try (PreparedStatement update = connection.prepareStatement(END_ACTIVE_HOLD_AS_CONFIRMED)) {
      update.setObject(1, utc(now));
      update.setObject(2, holdId);
      update.setLong(3, customerId);
      update.setObject(4, utc(now));
      try (ResultSet ended = update.executeQuery()) {
        // The conditions are re-checked by the UPDATE itself, on the newest committed version of
        // the row: a hold released or confirmed a moment ago does not match.
        if (!ended.next()) {
          return Result.error(new ConfirmRefusal.HoldNotActive());
        }
        seat = new SeatLabel(ended.getString("seat_label"));
      }
    }
    UUID bookingId = UUID.randomUUID();
    try (PreparedStatement insert = connection.prepareStatement(INSERT_BOOKING)) {
      insert.setObject(1, bookingId);
      insert.setObject(2, utc(now));
      insert.setObject(3, holdId);
      try (ResultSet paid = insert.executeQuery()) {
        if (!paid.next()) {
          // The hold row was just updated in this transaction, so the join cannot miss it.
          throw new IllegalStateException("hold " + holdId + " ended but no booking was written");
        }
        BigDecimal price = paid.getBigDecimal("price");
        return Result.success(
            new Booking(bookingId, holdId, seat, price, paid.getString("currency"), now));
      }
    }
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
