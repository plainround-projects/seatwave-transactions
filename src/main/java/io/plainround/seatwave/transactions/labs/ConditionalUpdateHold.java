package io.plainround.seatwave.transactions.labs;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Lab 1's solution: the hold as one conditional UPDATE on {@code lab1_seats}. The check ("the seat
 * is available, or held with an expired hold") and the write are one statement, so no other
 * transaction can act between them. Thread-safe: it holds no mutable state.
 */
public final class ConditionalUpdateHold {

  // At read committed, a second UPDATE of the same row waits for the first to commit, then
  // re-evaluates this WHERE on the row's new version: the seat is now HELD, so 0 rows change.
  private static final String HOLD =
      """
      UPDATE lab1_seats SET status = 'HELD', hold_customer = ?, hold_expires_at = ?
      WHERE event_id = ? AND seat_label = ?
        AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= ?))
      """;

  private final DataSource dataSource;
  private final Clock clock;
  private final Duration holdDuration;

  /**
   * Creates the hold.
   *
   * @param dataSource the pool
   * @param clock the clock
   * @param holdDuration how long a hold lasts
   */
  public ConditionalUpdateHold(DataSource dataSource, Clock clock, Duration holdDuration) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.holdDuration = Objects.requireNonNull(holdDuration, "holdDuration");
  }

  /**
   * Tries to hold a seat.
   *
   * @param eventId the event
   * @param seatLabel the seat
   * @param customer the customer string, validated by the caller
   * @return true if this call changed the seat to HELD, false if the seat was not free
   * @throws SQLException if the database fails
   */
  public boolean hold(long eventId, String seatLabel, String customer) throws SQLException {
    Instant now = clock.instant();
    // Autocommit: one statement is one transaction, the smallest transaction there is.
    try (Connection connection = dataSource.getConnection();
        PreparedStatement update = connection.prepareStatement(HOLD)) {
      update.setString(1, customer);
      update.setObject(2, now.plus(holdDuration).atOffset(ZoneOffset.UTC));
      update.setLong(3, eventId);
      update.setString(4, seatLabel);
      update.setObject(5, now.atOffset(ZoneOffset.UTC));
      return update.executeUpdate() == 1;
    }
  }
}
