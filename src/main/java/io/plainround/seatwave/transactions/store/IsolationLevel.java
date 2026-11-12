package io.plainround.seatwave.transactions.store;

import java.sql.Connection;

/**
 * The three isolation levels PostgreSQL implements (it runs read uncommitted as read committed).
 * Each transaction names its level on purpose, never by inheriting a pool default.
 */
public enum IsolationLevel {
  /** Each statement sees what was committed when that statement started. PostgreSQL's default. */
  READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
  /** Every statement sees one snapshot, taken at the transaction's first statement. */
  REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),
  /**
   * As repeatable read, and the database aborts one of two transactions that could not run one
   * after the other.
   */
  SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

  private final int jdbcLevel;

  IsolationLevel(int jdbcLevel) {
    this.jdbcLevel = jdbcLevel;
  }

  /**
   * Returns the JDBC constant for {@link Connection#setTransactionIsolation(int)}.
   *
   * @return the constant
   */
  public int jdbcLevel() {
    return jdbcLevel;
  }
}
