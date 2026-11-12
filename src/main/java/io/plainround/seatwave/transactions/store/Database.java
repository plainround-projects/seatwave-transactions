package io.plainround.seatwave.transactions.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.plainround.seatwave.transactions.config.StackSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Opens the two pools a SeatWave schema needs and migrates it. The owner role runs migrations; the
 * application role only reads and writes rows. Each pool is created once and closed by the caller.
 * Not shared state: one instance per schema under test.
 */
public final class Database {

  /** The real schema: every guard in place. */
  public static final String GUARDED_SCHEMA = "seatwave";

  /** The lab copy: the same tables with {@code one_claim_per_seat} and the hold trigger dropped. */
  public static final String UNGUARDED_SCHEMA = "seatwave_unguarded";

  // The owner's pool does set-up and clean-up between tests, one statement at a time.
  private static final int OWNER_POOL_SIZE = 2;

  // Demo data, not a migration: it lives beside the migrations and is loaded on request.
  private static final String SEED = "/db/seed/arena-night.sql";

  private Database() {}

  /** Which role a pool logs in as. */
  public enum Role {
    /** Owns the schema: migrations, set-up and clean-up. */
    OWNER,
    /** SeatWave itself: SELECT, INSERT and UPDATE on its rows, nothing more. */
    APPLICATION
  }

  /**
   * Migrates a schema with Flyway as the owner role, creating the schema if it does not exist.
   * PostgreSQL runs DDL inside transactions, so a migration that fails leaves nothing half done.
   *
   * @param settings the settings
   * @param schema {@link #GUARDED_SCHEMA} or {@link #UNGUARDED_SCHEMA}
   * @return Flyway's result, whose {@code migrationsExecuted} is 0 when the schema was current
   * @throws IOException if the owner's password file cannot be read
   */
  public static MigrateResult migrate(StackSettings settings, String schema) throws IOException {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(schema, "schema");
    return Flyway.configure()
        .dataSource(
            settings.jdbcUrl(), settings.ownerUser(), password(settings.ownerPasswordFile()))
        .schemas(schema)
        .locations("classpath:db/migration")
        .placeholders(Map.of("appRole", settings.appUser()))
        .load()
        .migrate();
  }

  /**
   * Opens a pool for one schema.
   *
   * @param settings the settings
   * @param schema the schema every connection uses
   * @param role the role every connection logs in as
   * @return the pool; the caller closes it
   * @throws IOException if the password file cannot be read
   */
  public static HikariDataSource pool(StackSettings settings, String schema, Role role)
      throws IOException {
    boolean isOwner = role == Role.OWNER;
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(settings.jdbcUrl());
    config.setUsername(isOwner ? settings.ownerUser() : settings.appUser());
    config.setPassword(
        password(isOwner ? settings.ownerPasswordFile() : settings.appPasswordFile()));
    // Sets search_path, so the same SQL text runs against the guarded or the unguarded copy.
    config.setSchema(schema);
    config.setMaximumPoolSize(isOwner ? OWNER_POOL_SIZE : settings.poolSize());
    config.setConnectionTimeout(settings.connectTimeout().toMillis());
    // Transactions set autocommit and isolation explicitly; the pool restores both on return.
    config.setAutoCommit(true);
    config.setPoolName(role.name().toLowerCase(Locale.ROOT) + "-" + schema);
    return new HikariDataSource(config);
  }

  /**
   * Loads the arena seed ({@code db/seed/arena-night.sql}: event 1, 20 sections, 20,000 seats) into
   * the schema behind an owner pool, unless that schema already has an event. Every program that
   * needs the seed calls it, so a freshly started lab database works with any of them first.
   *
   * @param owner a pool of {@link Role#OWNER} connections for one schema
   * @return true if the seed was loaded now, false if the schema already had events
   * @throws IOException if the seed file is missing from the class path
   * @throws SQLException if the database refuses the seed
   */
  public static boolean seedIfEmpty(HikariDataSource owner) throws IOException, SQLException {
    Objects.requireNonNull(owner, "owner");
    try (Connection connection = owner.getConnection();
        Statement statement = connection.createStatement()) {
      try (ResultSet events = statement.executeQuery("SELECT count(*) FROM events")) {
        events.next();
        if (events.getLong(1) > 0) {
          return false;
        }
      }
      try (var seed = Database.class.getResourceAsStream(SEED)) {
        if (seed == null) {
          throw new IOException(SEED + " is not on the class path");
        }
        statement.execute(new String(seed.readAllBytes(), StandardCharsets.UTF_8));
      }
      return true;
    }
  }

  private static String password(Path file) throws IOException {
    try {
      return Files.readString(file, StandardCharsets.UTF_8).strip();
    } catch (IOException unreadable) {
      throw new IOException(
          "the database password file "
              + file
              + " cannot be read; scripts/stack.sh up generates it",
          unreadable);
    }
  }
}
