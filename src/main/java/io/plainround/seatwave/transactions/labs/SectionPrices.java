package io.plainround.seatwave.transactions.labs;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.transactions.race.Gate;
import io.plainround.seatwave.transactions.store.IsolationLevel;
import io.plainround.seatwave.transactions.store.Transactions;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lab 2's solution: four ways for the box office to raise a section's price, one broken at read
 * committed. The three fixes run at read committed; what differs is how the read and the write are
 * tied together. Thread-safe: the only mutable state is a concurrent counter.
 */
public final class SectionPrices {

  private static final String READ =
      "SELECT price, version FROM sections WHERE event_id = ? AND section_code = ?";
  private static final String READ_LOCKED = READ + " FOR UPDATE";
  private static final String WRITE =
      "UPDATE sections SET price = ? WHERE event_id = ? AND section_code = ?";
  // Optimistic: write only if nobody wrote since this transaction read; 0 rows means "you lost".
  private static final String WRITE_IF_UNCHANGED =
      "UPDATE sections SET price = ?, version = version + 1"
          + " WHERE event_id = ? AND section_code = ? AND version = ?";
  // In place: the database reads and writes the row under its own row lock, in one statement.
  private static final String RAISE_IN_PLACE =
      "UPDATE sections SET price = price + ?, version = version + 1 WHERE event_id = ? AND section_code = ?";

  private final Transactions transactions;
  private final int maxOptimisticAttempts;
  private final LongAdder versionConflicts = new LongAdder();

  /**
   * Creates the price editor.
   *
   * @param transactions the runner
   * @param maxOptimisticAttempts how often an optimistic raise reads again after losing
   */
  public SectionPrices(Transactions transactions, int maxOptimisticAttempts) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    if (maxOptimisticAttempts < 1) {
      throw new IllegalArgumentException("maxOptimisticAttempts must be at least 1");
    }
    this.maxOptimisticAttempts = maxOptimisticAttempts;
  }

  /**
   * The section a raise changes.
   *
   * @param eventId the event
   * @param sectionCode the section
   */
  public record Section(long eventId, String sectionCode) {
    /** Checks the parts. */
    public Section {
      Objects.requireNonNull(sectionCode, "sectionCode");
    }
  }

  /**
   * BROKEN at read committed: read the price, wait at the gate, write read + amount. Two staff who
   * both read 80.00 both write 85.00: one raise is lost, and nothing reports it. At repeatable read
   * or serializable the same code is safe, because the database aborts the second writer (SQLSTATE
   * 40001) and the runner runs it again from the read.
   *
   * @param section the section
   * @param amount the raise
   * @param level the isolation level the read and the write run at
   * @param afterRead the gate between the read and the write
   * @throws SQLException if the database fails
   */
  public void raiseByReadModifyWrite(
      Section section, BigDecimal amount, IsolationLevel level, Gate afterRead)
      throws SQLException {
    transactions.inTransaction(
        level,
        connection -> {
          BigDecimal read = readPrice(connection, READ, section).price();
          passGate(afterRead);
          write(connection, WRITE, section, read.add(amount));
          return Result.success(Boolean.TRUE);
        });
  }

  /**
   * Pessimistic: {@code SELECT ... FOR UPDATE} locks the row before reading it, so the second
   * clerk's read waits until the first commits and then sees 85.00.
   *
   * @param section the section
   * @param amount the raise
   * @throws SQLException if the database fails
   */
  public void raiseWithRowLock(Section section, BigDecimal amount) throws SQLException {
    transactions.inTransaction(
        IsolationLevel.READ_COMMITTED,
        connection -> {
          BigDecimal read = readPrice(connection, READ_LOCKED, section).price();
          write(connection, WRITE, section, read.add(amount));
          return Result.success(Boolean.TRUE);
        });
  }

  /**
   * Optimistic: read the price and its version without a lock, and write only if the version is
   * unchanged. The loser of a race changes nothing, reads again and retries.
   *
   * @param section the section
   * @param amount the raise
   * @return true when the raise was written, false when every attempt lost (refuse with 409)
   * @throws SQLException if the database fails
   */
  public boolean raiseWithVersion(Section section, BigDecimal amount) throws SQLException {
    for (int attempt = 1; attempt <= maxOptimisticAttempts; attempt++) {
      boolean written =
          transactions
              .inTransaction(
                  IsolationLevel.READ_COMMITTED,
                  connection -> {
                    PriceAndVersion read = readPrice(connection, READ, section);
                    try (PreparedStatement update =
                        connection.prepareStatement(WRITE_IF_UNCHANGED)) {
                      update.setBigDecimal(1, read.price().add(amount));
                      update.setLong(2, section.eventId());
                      update.setString(3, section.sectionCode());
                      update.setLong(4, read.version());
                      return Result.success(update.executeUpdate() == 1);
                    }
                  })
              .recoverError(unused -> false);
      if (written) {
        return true;
      }
      versionConflicts.increment();
    }
    return false;
  }

  /**
   * In place: one statement computes and writes the new price, so there is no read in Java to go
   * stale. Works only when the new value follows from the old one by arithmetic SQL can do.
   *
   * @param section the section
   * @param amount the raise
   * @throws SQLException if the database fails
   */
  public void raiseInPlace(Section section, BigDecimal amount) throws SQLException {
    transactions.inTransaction(
        IsolationLevel.READ_COMMITTED,
        connection -> {
          try (PreparedStatement update = connection.prepareStatement(RAISE_IN_PLACE)) {
            update.setBigDecimal(1, amount);
            update.setLong(2, section.eventId());
            update.setString(3, section.sectionCode());
            return Result.success(update.executeUpdate() == 1);
          }
        });
  }

  /**
   * Returns how many optimistic attempts lost to another writer, since creation.
   *
   * @return the conflict count
   */
  public long versionConflicts() {
    return versionConflicts.sum();
  }

  private record PriceAndVersion(BigDecimal price, long version) {}

  private static PriceAndVersion readPrice(Connection connection, String sql, Section section)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(sql)) {
      query.setLong(1, section.eventId());
      query.setString(2, section.sectionCode());
      try (ResultSet row = query.executeQuery()) {
        if (!row.next()) {
          throw new SQLException(
              "no section " + section.sectionCode() + " in event " + section.eventId());
        }
        return new PriceAndVersion(row.getBigDecimal("price"), row.getLong("version"));
      }
    }
  }

  private static void write(Connection connection, String sql, Section section, BigDecimal price)
      throws SQLException {
    try (PreparedStatement update = connection.prepareStatement(sql)) {
      update.setBigDecimal(1, price);
      update.setLong(2, section.eventId());
      update.setString(3, section.sectionCode());
      update.executeUpdate();
    }
  }

  private static void passGate(Gate gate) throws SQLException {
    try {
      gate.arriveAndWait();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new SQLException(
          "interrupted at the gate; the transaction is rolled back", interrupted);
    }
  }
}
