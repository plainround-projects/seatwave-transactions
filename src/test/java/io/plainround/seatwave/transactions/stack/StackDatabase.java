package io.plainround.seatwave.transactions.stack;

import com.github.sviperll.result4j.Result;
import com.zaxxer.hikari.HikariDataSource;
import io.plainround.seatwave.transactions.config.SettingsReader;
import io.plainround.seatwave.transactions.config.StackSettings;
import io.plainround.seatwave.transactions.store.Database;
import io.plainround.seatwave.transactions.store.Transactions;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The stack tests' view of the lab database: settings, the two schemas, their pools and clean-up.
 * One instance per test class, closed after the class. Not thread-safe; the racers use only the
 * pools, which are.
 */
final class StackDatabase implements AutoCloseable {

  // A fixed instant, so expiry in the tests never depends on the machine's clock: one hour before
  // Module 10's 10:00 on-sale, on the day of the event in the seed.
  static final Instant NOW = Instant.parse("2026-12-08T09:00:00Z");
  static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  static final long EVENT = 1;
  private static final long JITTER_SEED = 13;

  final StackSettings settings;
  final HikariDataSource guarded;
  final HikariDataSource unguarded;
  private final HikariDataSource guardedOwner;
  private final HikariDataSource unguardedOwner;

  private StackDatabase(StackSettings settings) throws IOException, SQLException {
    this.settings = settings;
    Database.migrate(settings, Database.GUARDED_SCHEMA);
    Database.migrate(settings, Database.UNGUARDED_SCHEMA);
    this.guardedOwner = Database.pool(settings, Database.GUARDED_SCHEMA, Database.Role.OWNER);
    this.unguardedOwner = Database.pool(settings, Database.UNGUARDED_SCHEMA, Database.Role.OWNER);
    Database.seedIfEmpty(guardedOwner);
    Database.seedIfEmpty(unguardedOwner);
    removeGuards(unguardedOwner);
    this.guarded = Database.pool(settings, Database.GUARDED_SCHEMA, Database.Role.APPLICATION);
    this.unguarded = Database.pool(settings, Database.UNGUARDED_SCHEMA, Database.Role.APPLICATION);
  }

  /** Opens the lab database named by the system property {@code seatwave.stackSettings}. */
  static StackDatabase open() throws IOException, SQLException {
    Path file = Path.of(System.getProperty("seatwave.stackSettings", "config/stack.properties"));
    return switch (StackSettings.read(new SettingsReader(SettingsReader.load(file)))) {
      case Result.Success<StackSettings, List<String>>(StackSettings settings) ->
          new StackDatabase(settings);
      case Result.Error<StackSettings, List<String>>(List<String> problems) ->
          throw new IllegalStateException(
              "the settings in " + file + " have problems: " + problems);
    };
  }

  Transactions transactions(HikariDataSource pool) {
    return new Transactions(
        pool, settings.retryPolicy(), settings.lockTimeout(), new SplittableRandom(JITTER_SEED));
  }

  /** Deletes every hold, booking and customer in both schemas and resets the prices. */
  void reset() throws SQLException {
    for (HikariDataSource owner : List.of(guardedOwner, unguardedOwner)) {
      try (Connection connection = owner.getConnection();
          Statement statement = connection.createStatement()) {
        // The owner may delete; SeatWave's own role may not (R__app_grants.sql).
        statement.execute("DELETE FROM bookings; DELETE FROM holds; DELETE FROM customers;");
        statement.execute("UPDATE sections SET price = 80.00, version = 0");
      }
    }
  }

  /** Runs one owner statement in the guarded schema, for a lab's own set-up. */
  void runAsOwner(String sql) throws SQLException {
    try (Connection connection = guardedOwner.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  /** Registers customers fan_1 to fan_N in the schema behind the pool, and returns their keys. */
  List<Long> registerCustomers(HikariDataSource pool, int count) throws SQLException {
    List<Long> customerIds = new ArrayList<>(count);
    try (Connection connection = pool.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO customers (customer_ref, created_at) VALUES (?, ?) RETURNING customer_id")) {
      for (int fan = 1; fan <= count; fan++) {
        insert.setString(1, "fan_" + fan);
        insert.setObject(2, NOW.atOffset(ZoneOffset.UTC));
        try (ResultSet key = insert.executeQuery()) {
          key.next();
          customerIds.add(key.getLong(1));
        }
      }
    }
    return List.copyOf(customerIds);
  }

  /** Counts rows with one query, for the assertions; the SQL text is the test's own. */
  static long count(HikariDataSource pool, String sql, Object... parameters) throws SQLException {
    try (Connection connection = pool.getConnection();
        PreparedStatement query = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        query.setObject(index + 1, parameters[index]);
      }
      try (ResultSet rows = query.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  @Override
  public void close() {
    guarded.close();
    unguarded.close();
    guardedOwner.close();
    unguardedOwner.close();
  }

  // The lab copy keeps every table, key and check, and loses exactly the two guards whose job the
  // tests show: the unique index on claims and the hold-limit trigger.
  private static void removeGuards(HikariDataSource owner) throws SQLException {
    try (Connection connection = owner.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP INDEX IF EXISTS one_claim_per_seat");
      statement.execute("DROP TRIGGER IF EXISTS hold_limit_per_customer ON holds");
    }
  }
}
