package io.plainround.seatwave.transactions.store;

import com.github.sviperll.result4j.Result;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.random.RandomGenerator;
import javax.sql.DataSource;

/**
 * Runs one unit of work as one transaction at a named isolation level: commit when the work returns
 * a success, roll back when it returns an expected refusal or throws, and run it again from the
 * start when the database aborted it to protect isolation (a serialization failure or a deadlock),
 * up to the policy's limit.
 *
 * <p>Thread-safe: it keeps no per-call state; the counters are concurrent adders, and the one
 * jitter source is used only while holding its own lock, because a {@link RandomGenerator} such as
 * {@code SplittableRandom} is not safe for concurrent use. A retried attempt borrows a fresh
 * connection, so nothing of the aborted attempt can leak into the next.
 */
public final class Transactions {

  private final DataSource dataSource;
  private final RetryPolicy retryPolicy;
  private final Duration lockTimeout;
  private final RandomGenerator random;
  private final LongAdder retries = new LongAdder();
  private final LongAdder abortedByDatabase = new LongAdder();

  /**
   * Creates a runner.
   *
   * @param dataSource the pool to borrow from
   * @param retryPolicy how often an aborted transaction runs again
   * @param lockTimeout the longest any one lock wait may take before the statement fails
   * @param random the jitter source
   */
  public Transactions(
      DataSource dataSource,
      RetryPolicy retryPolicy,
      Duration lockTimeout,
      RandomGenerator random) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    this.lockTimeout = Objects.requireNonNull(lockTimeout, "lockTimeout");
    this.random = Objects.requireNonNull(random, "random");
  }

  /**
   * One unit of work inside an open transaction. It must not commit or roll back, and must not call
   * anything remote: the transaction's locks are held until it returns.
   *
   * @param <T> the value of a success
   * @param <E> the expected refusal
   */
  @FunctionalInterface
  public interface Work<T, E> {
    /**
     * Does the work.
     *
     * @param connection the transaction's connection
     * @return a success to commit, or a refusal to roll back
     * @throws SQLException if a statement fails
     */
    Result<T, E> run(Connection connection) throws SQLException;
  }

  /**
   * Runs the work as one transaction.
   *
   * @param level the isolation level, chosen by the caller for the rule it protects
   * @param work the work
   * @param <T> the value of a success
   * @param <E> the expected refusal
   * @return what the last attempt returned; a refusal means nothing was committed
   * @throws SQLException if a statement fails for any other reason, if every attempt was aborted,
   *     or if the wait before a retry was interrupted (the interrupt flag is then set again)
   */
  public <T, E> Result<T, E> inTransaction(IsolationLevel level, Work<T, E> work)
      throws SQLException {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(work, "work");
    for (int attempt = 1; ; attempt++) {
      try {
        return attemptOnce(level, work);
      } catch (SQLException failure) {
        if (!SqlStates.isRetryable(failure)) {
          throw failure;
        }
        abortedByDatabase.increment();
        if (attempt == retryPolicy.maxAttempts()) {
          throw new SQLException(
              "the transaction was aborted by the database on all "
                  + attempt
                  + " attempts; the last reason follows",
              failure.getSQLState(),
              failure);
        }
        waitBeforeRetry(attempt + 1, failure);
        retries.increment();
      }
    }
  }

  /**
   * Returns how many attempts were run again after an abort, since this runner was created.
   *
   * @return the retry count
   */
  public long retries() {
    return retries.sum();
  }

  /**
   * Returns how many attempts the database aborted (serialization failures and deadlocks).
   *
   * @return the abort count, retried or not
   */
  public long abortedByDatabase() {
    return abortedByDatabase.sum();
  }

  private <T, E> Result<T, E> attemptOnce(IsolationLevel level, Work<T, E> work)
      throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(level.jdbcLevel());
      try {
        limitLockWaits(connection);
        Result<T, E> outcome = work.run(connection);
        if (outcome.isError()) {
          connection.rollback();
        } else {
          // Commit can fail too: at serializable the database may detect the conflict only here.
          connection.commit();
        }
        return outcome;
      } catch (SQLException | RuntimeException failure) {
        rollbackQuietlyInto(connection, failure);
        throw failure;
      }
    }
  }

  // A transaction that waits longer than this for a lock gives up with SQLSTATE 55P03, so one
  // long transaction cannot make every other request wait for it (failure mode 4).
  private void limitLockWaits(Connection connection) throws SQLException {
    try (PreparedStatement setting =
        connection.prepareStatement("SELECT set_config('lock_timeout', ?, true)")) {
      setting.setString(1, lockTimeout.toMillis() + "ms");
      setting.execute();
    }
  }

  private static void rollbackQuietlyInto(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      // The original failure is what the caller must see; the rollback failure travels with it.
      original.addSuppressed(rollbackFailure);
    }
  }

  private void waitBeforeRetry(int nextAttempt, SQLException cause) throws SQLException {
    try {
      Duration wait;
      // Held only to draw one number, never across the sleep or any I/O.
      synchronized (random) {
        wait = retryPolicy.waitBefore(nextAttempt, random);
      }
      Thread.sleep(wait);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      SQLException stopped =
          new SQLException(
              "interrupted while waiting to retry an aborted transaction; nothing was committed",
              cause.getSQLState(),
              interrupted);
      stopped.addSuppressed(cause);
      throw stopped;
    }
  }
}
