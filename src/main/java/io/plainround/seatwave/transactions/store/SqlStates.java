package io.plainround.seatwave.transactions.store;

import java.sql.SQLException;
import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Reads what PostgreSQL said about a failed statement: its SQLSTATE code and, for a constraint, the
 * constraint's name. Stateless and thread-safe.
 */
public final class SqlStates {

  /** {@code serialization_failure}: the transaction lost a conflict and may be run again. */
  public static final String SERIALIZATION_FAILURE = "40001";

  /** {@code deadlock_detected}: the database aborted this transaction to break a deadlock. */
  public static final String DEADLOCK_DETECTED = "40P01";

  /** {@code unique_violation}: a unique constraint or unique index refused the row. */
  public static final String UNIQUE_VIOLATION = "23505";

  /** {@code foreign_key_violation}: the row names a parent row that does not exist. */
  public static final String FOREIGN_KEY_VIOLATION = "23503";

  /** {@code check_violation}: a CHECK constraint, or the hold-limit trigger, refused the row. */
  public static final String CHECK_VIOLATION = "23514";

  /** {@code lock_not_available}: a lock wait passed {@code lock_timeout}. */
  public static final String LOCK_NOT_AVAILABLE = "55P03";

  private SqlStates() {}

  /**
   * Says whether running the whole transaction again can succeed. Only the two codes with which the
   * database aborts a transaction to protect isolation qualify: nothing of the aborted attempt was
   * committed, so a fresh attempt is safe. A constraint refusal is an answer, not bad luck.
   *
   * @param failure the failure
   * @return true for a serialization failure or a deadlock
   */
  public static boolean isRetryable(SQLException failure) {
    String state = failure.getSQLState();
    return SERIALIZATION_FAILURE.equals(state) || DEADLOCK_DETECTED.equals(state);
  }

  /**
   * Returns the constraint PostgreSQL named in its error, such as {@code one_claim_per_seat}.
   *
   * @param failure the failure
   * @return the constraint name, or empty when the failure is not about a constraint
   */
  public static Optional<String> constraintName(SQLException failure) {
    if (failure instanceof PSQLException postgresFailure) {
      ServerErrorMessage message = postgresFailure.getServerErrorMessage();
      if (message != null) {
        return Optional.ofNullable(message.getConstraint());
      }
    }
    return Optional.empty();
  }

  /**
   * Says whether a failure is this constraint's refusal.
   *
   * @param failure the failure
   * @param sqlState the expected code
   * @param constraint the expected constraint name
   * @return true when both match
   */
  public static boolean isRefusalBy(SQLException failure, String sqlState, String constraint) {
    return sqlState.equals(failure.getSQLState())
        && constraintName(failure).filter(constraint::equals).isPresent();
  }
}
