package io.plainround.seatwave.transactions.store;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.transactions.booking.HoldRequest;
import io.plainround.seatwave.transactions.booking.SeatLabel;
import io.plainround.seatwave.transactions.race.Gate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * DELIBERATELY BROKEN hold code, kept to show each anomaly happen and each fix stop it. Every
 * method reads a fact, decides in Java, and writes, with a {@link Gate} between the read and the
 * write so that every racer reads before any racer writes. Production code is {@link HoldStore}.
 *
 * <p>Thread-safe: it holds no mutable state.
 */
public final class CheckThenWriteHolds {

  private static final String COUNT_CLAIMS_ON_SEAT =
      """
      SELECT count(*) FROM holds
      WHERE event_id = ? AND seat_label = ?
        AND (state = 'CONFIRMED' OR (state = 'ACTIVE' AND expires_at > ?))
      """;

  private static final String COUNT_ACTIVE_HOLDS_OF_CUSTOMER =
      "SELECT count(*) FROM holds WHERE customer_id = ? AND state = 'ACTIVE' AND expires_at > ?";

  private static final String LOCK_CUSTOMER =
      "SELECT customer_id FROM customers WHERE customer_id = ? FOR UPDATE";

  // A plain INSERT: no ON CONFLICT. On the guarded schema a second claim raises 23505.
  private static final String INSERT_HOLD =
      """
      INSERT INTO holds (hold_id, event_id, seat_label, customer_id, state, created_at, expires_at)
      VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
      """;

  private static final String CLAIM_SEAT =
      INSERT_HOLD
          + "ON CONFLICT (event_id, seat_label) WHERE state IN ('ACTIVE', 'CONFIRMED') DO NOTHING";

  private final DataSource dataSource;
  private final Transactions transactions;
  private final Clock clock;
  private final Duration holdDuration;
  private final int holdLimit;

  /**
   * Creates the broken store.
   *
   * @param dataSource the pool, for the check that runs outside any transaction
   * @param transactions the runner for the writes
   * @param clock the clock
   * @param holdDuration how long a hold lasts
   * @param holdLimit the limit the Java check enforces; must equal the trigger's argument in V2
   */
  public CheckThenWriteHolds(
      DataSource dataSource,
      Transactions transactions,
      Clock clock,
      Duration holdDuration,
      int holdLimit) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.holdDuration = Objects.requireNonNull(holdDuration, "holdDuration");
    if (holdLimit < 1) {
      throw new IllegalArgumentException("holdLimit must be at least 1, but was " + holdLimit);
    }
    this.holdLimit = holdLimit;
  }

  /**
   * Broken seat rule: "is the seat free?" in one statement, then the insert in another. Between the
   * two, every other racer can ask the same question and get the same answer.
   *
   * @param customerId the customer
   * @param eventId the event
   * @param seat the seat
   * @param afterCheck the gate between the check and the write
   * @return how the attempt ended
   * @throws SQLException if the database fails for a reason other than a constraint refusal
   * @throws InterruptedException if the wait at the gate was interrupted
   */
  public Attempt holdAfterJavaCheck(long customerId, long eventId, SeatLabel seat, Gate afterCheck)
      throws SQLException, InterruptedException {
    Instant now = clock.instant();
    if (countClaims(eventId, seat, now) > 0) {
      return Attempt.SEAT_TAKEN;
    }
    afterCheck.arriveAndWait();
    try {
      transactions.inTransaction(
          IsolationLevel.READ_COMMITTED,
          connection ->
              Result.success(insertHold(connection, INSERT_HOLD, customerId, eventId, seat, now)));
      return Attempt.GRANTED;
    } catch (SQLException refused) {
      return refusedByDatabase(refused);
    }
  }

  /**
   * Broken limit rule: count the customer's active holds, then insert, in one transaction at the
   * given level. Each racer inserts a different seat, so no two racers write the same row, and
   * neither read committed nor repeatable read sees a conflict: write skew.
   *
   * @param customerId the customer
   * @param eventId the event
   * @param seat a seat nobody else in the race wants
   * @param level the isolation level under test
   * @param afterCount the gate between the count and the write
   * @return how the attempt ended
   * @throws SQLException if the database fails for a reason other than a constraint refusal
   */
  public Attempt holdAfterCountCheck(
      long customerId, long eventId, SeatLabel seat, IsolationLevel level, Gate afterCount)
      throws SQLException {
    Instant now = clock.instant();
    try {
      return transactions
          .inTransaction(
              level,
              connection -> {
                int activeHolds = countActiveHolds(connection, customerId, now);
                passGate(afterCount);
                if (activeHolds >= holdLimit) {
                  return Result.<Attempt, Attempt>error(Attempt.LIMIT_REACHED);
                }
                insertHold(connection, INSERT_HOLD, customerId, eventId, seat, now);
                return Result.<Attempt, Attempt>success(Attempt.GRANTED);
              })
          .recoverError(refusal -> refusal);
    } catch (SQLException refused) {
      return refusedByDatabase(refused);
    }
  }

  /**
   * The lock fix, and its trap: start the transaction, then lock the customer's row, then count,
   * then insert. At read committed the count runs after the lock and sees every committed hold, so
   * the rule holds. At repeatable read the count reads the snapshot taken by the first statement,
   * before the gate, so it misses holds committed while this racer waited for the lock: the lock
   * alone does not help.
   *
   * @param customerId the customer
   * @param eventId the event
   * @param seat a seat nobody else in the race wants
   * @param level the isolation level under test
   * @param afterStart the gate after the first statement, before the lock
   * @return how the attempt ended
   * @throws SQLException if the database fails for a reason other than a constraint refusal
   */
  public Attempt holdAfterLockThenCount(
      long customerId, long eventId, SeatLabel seat, IsolationLevel level, Gate afterStart)
      throws SQLException {
    Instant now = clock.instant();
    try {
      return transactions
          .inTransaction(
              level,
              connection -> {
                // The first statement: at repeatable read and serializable it fixes the snapshot.
                countActiveHolds(connection, customerId, now);
                passGate(afterStart);
                lockCustomer(connection, customerId);
                if (countActiveHolds(connection, customerId, now) >= holdLimit) {
                  return Result.<Attempt, Attempt>error(Attempt.LIMIT_REACHED);
                }
                insertHold(connection, INSERT_HOLD, customerId, eventId, seat, now);
                return Result.<Attempt, Attempt>success(Attempt.GRANTED);
              })
          .recoverError(refusal -> refusal);
    } catch (SQLException refused) {
      return refusedByDatabase(refused);
    }
  }

  /**
   * Broken lock order: claims the seats in the order the client sent them, with a gate after the
   * first claim. Two requests for the same two seats in opposite orders each claim one seat and
   * then wait for the other's: a deadlock, which PostgreSQL breaks by aborting one of them.
   *
   * @param request the seats in client order
   * @param afterFirstSeat the gate after the first claim
   * @return GRANTED or SEAT_TAKEN
   * @throws SQLException with SQLSTATE 40P01 for the transaction the database aborted (when the
   *     runner's policy allows one attempt only)
   */
  public Attempt holdInClientOrder(HoldRequest request, Gate afterFirstSeat) throws SQLException {
    Instant now = clock.instant();
    return transactions
        .inTransaction(
            IsolationLevel.READ_COMMITTED,
            connection -> {
              boolean firstSeat = true;
              for (SeatLabel seat : request.seats()) {
                if (insertHold(
                        connection, CLAIM_SEAT, request.customerId(), request.eventId(), seat, now)
                    == 0) {
                  return Result.<Attempt, Attempt>error(Attempt.SEAT_TAKEN);
                }
                if (firstSeat) {
                  passGate(afterFirstSeat);
                  firstSeat = false;
                }
              }
              return Result.<Attempt, Attempt>success(Attempt.GRANTED);
            })
        .recoverError(refusal -> refusal);
  }

  private long countClaims(long eventId, SeatLabel seat, Instant now) throws SQLException {
    // Autocommit: the check is its own transaction, exactly as "check in Java first" code does it.
    try (Connection connection = dataSource.getConnection();
        PreparedStatement query = connection.prepareStatement(COUNT_CLAIMS_ON_SEAT)) {
      query.setLong(1, eventId);
      query.setString(2, seat.text());
      query.setObject(3, utc(now));
      return singleCount(query);
    }
  }

  private static int countActiveHolds(Connection connection, long customerId, Instant now)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(COUNT_ACTIVE_HOLDS_OF_CUSTOMER)) {
      query.setLong(1, customerId);
      query.setObject(2, utc(now));
      return Math.toIntExact(singleCount(query));
    }
  }

  private static void lockCustomer(Connection connection, long customerId) throws SQLException {
    try (PreparedStatement lock = connection.prepareStatement(LOCK_CUSTOMER)) {
      lock.setLong(1, customerId);
      try (ResultSet locked = lock.executeQuery()) {
        if (!locked.next()) {
          throw new SQLException("customer " + customerId + " does not exist");
        }
      }
    }
  }

  private int insertHold(
      Connection connection, String sql, long customerId, long eventId, SeatLabel seat, Instant now)
      throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(sql)) {
      insert.setObject(1, UUID.randomUUID());
      insert.setLong(2, eventId);
      insert.setString(3, seat.text());
      insert.setLong(4, customerId);
      insert.setObject(5, utc(now));
      insert.setObject(6, utc(now.plus(holdDuration)));
      return insert.executeUpdate();
    }
  }

  private static long singleCount(PreparedStatement query) throws SQLException {
    try (ResultSet rows = query.executeQuery()) {
      rows.next();
      return rows.getLong(1);
    }
  }

  // The gate is waited on inside the transaction's work, which may throw only SQLException; an
  // interrupt there stops the work, restores the flag and rolls the transaction back.
  private static void passGate(Gate gate) throws SQLException {
    try {
      gate.arriveAndWait();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new SQLException(
          "interrupted at the gate; the transaction is rolled back", interrupted);
    }
  }

  private static Attempt refusedByDatabase(SQLException refused) throws SQLException {
    String state = refused.getSQLState();
    if (SqlStates.UNIQUE_VIOLATION.equals(state) || SqlStates.CHECK_VIOLATION.equals(state)) {
      return Attempt.REFUSED_BY_DATABASE;
    }
    throw refused;
  }

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
