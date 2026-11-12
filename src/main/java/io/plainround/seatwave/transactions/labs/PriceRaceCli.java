package io.plainround.seatwave.transactions.labs;

import com.github.sviperll.result4j.Result;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import io.plainround.seatwave.transactions.config.SettingsReader;
import io.plainround.seatwave.transactions.config.StackSettings;
import io.plainround.seatwave.transactions.race.Race;
import io.plainround.seatwave.transactions.store.Database;
import io.plainround.seatwave.transactions.store.Transactions;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.api.FlywayException;

/**
 * Lab 2's measurement: 16 clerks each raise section A's price by $0.01, 125 times, with each fix,
 * and the table prints the raises per second, the version conflicts and whether every cent arrived.
 * One row is one hot row taking every write: the ceiling of Chapter 13.5. The only code in the lab
 * that prints. Exit status 0 when every fix kept every raise.
 */
public final class PriceRaceCli {

  private static final int CLERKS = 16;
  private static final int RAISES_PER_CLERK = 125;
  private static final BigDecimal ONE_CENT = new BigDecimal("0.01");
  private static final BigDecimal START_PRICE = new BigDecimal("80.00");
  // With 16 clerks on one row, one raise can lose many rounds in a row; the lab measures what
  // that costs, so a raise gives up only after 1,000 losses, far more than the run can produce.
  private static final int OPTIMISTIC_ATTEMPTS = 1_000;
  private static final Duration DEADLINE = Duration.ofMinutes(5);
  private static final long SEED = 13;
  private static final SectionPrices.Section SECTION_A = new SectionPrices.Section(1, "A");
  private static final double NANOS_PER_SECOND = 1e9;

  private PriceRaceCli() {}

  /** The fixes compared. */
  private enum Fix {
    ROW_LOCK,
    VERSION_COLUMN,
    IN_PLACE
  }

  /**
   * Runs the comparison.
   *
   * @param arguments the settings file, such as config/stack.properties
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: PriceRaceCli <settings file>");
      System.exit(2);
    }
    // The exit comes after the try, so both pools are closed on every path first.
    System.exit(compare(Path.of(arguments[0])));
  }

  private static int compare(Path settingsFile) {
    try {
      StackSettings settings = settings(settingsFile);
      // A fresh lab database has no schema yet; migrating is a no-op when it is current.
      Database.migrate(settings, Database.GUARDED_SCHEMA);
      try (HikariDataSource owner =
              Database.pool(settings, Database.GUARDED_SCHEMA, Database.Role.OWNER);
          HikariDataSource app =
              Database.pool(settings, Database.GUARDED_SCHEMA, Database.Role.APPLICATION)) {
        Database.seedIfEmpty(owner);
        BigDecimal expected =
            START_PRICE.add(
                ONE_CENT.multiply(BigDecimal.valueOf((long) CLERKS * RAISES_PER_CLERK)));
        System.out.printf(
            Locale.ROOT,
            "%d clerks x %d raises of 0.01 on one row; expected final price %s%n",
            CLERKS,
            RAISES_PER_CLERK,
            expected);
        boolean allKept = true;
        for (Fix fix : Fix.values()) {
          allKept &= runOne(fix, owner, app, settings, expected);
        }
        return allKept ? 0 : 1;
      }
    } catch (IOException
        | SQLException
        | TimeoutException
        | FlywayException
        | HikariPool.PoolInitializationException failure) {
      System.err.println("the comparison stopped: " + failure.getMessage());
      return 1;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      System.err.println("interrupted before the comparison finished");
      return 1;
    }
  }

  private static boolean runOne(
      Fix fix,
      HikariDataSource owner,
      HikariDataSource app,
      StackSettings settings,
      BigDecimal expected)
      throws SQLException, TimeoutException, InterruptedException {
    resetPrice(owner);
    Transactions transactions =
        new Transactions(
            app, settings.retryPolicy(), settings.lockTimeout(), new SplittableRandom(SEED));
    SectionPrices prices = new SectionPrices(transactions, OPTIMISTIC_ATTEMPTS);
    long started = System.nanoTime();
    List<Race.Finish<Integer>> finishes =
        Race.run(CLERKS, clerk -> () -> raiseRepeatedly(fix, prices), DEADLINE);
    double seconds = (System.nanoTime() - started) / NANOS_PER_SECOND;
    int written = finishes.stream().mapToInt(PriceRaceCli::writtenOrZero).sum();
    BigDecimal finalPrice = price(owner);
    System.out.printf(
        Locale.ROOT,
        "  %-15s raises written %4d of %4d, %7.1f per second, version conflicts %5d, final %s%n",
        fix,
        written,
        CLERKS * RAISES_PER_CLERK,
        written / seconds,
        prices.versionConflicts(),
        finalPrice);
    return finalPrice.compareTo(expected) == 0;
  }

  private static int raiseRepeatedly(Fix fix, SectionPrices prices) throws SQLException {
    int written = 0;
    for (int raise = 0; raise < RAISES_PER_CLERK; raise++) {
      boolean kept =
          switch (fix) {
            case ROW_LOCK -> {
              prices.raiseWithRowLock(SECTION_A, ONE_CENT);
              yield true;
            }
            case VERSION_COLUMN -> prices.raiseWithVersion(SECTION_A, ONE_CENT);
            case IN_PLACE -> {
              prices.raiseInPlace(SECTION_A, ONE_CENT);
              yield true;
            }
          };
      written += kept ? 1 : 0;
    }
    return written;
  }

  private static int writtenOrZero(Race.Finish<Integer> finish) {
    return switch (finish) {
      case Race.Finish.Returned<Integer>(Integer written) -> written;
      case Race.Finish.Threw<Integer>(Exception failure) -> {
        System.err.println("a clerk stopped: " + failure.getMessage());
        yield 0;
      }
    };
  }

  private static void resetPrice(HikariDataSource owner) throws SQLException {
    try (Connection connection = owner.getConnection();
        PreparedStatement update =
            connection.prepareStatement(
                "UPDATE sections SET price = ?, version = 0 WHERE event_id = ? AND section_code = ?")) {
      update.setBigDecimal(1, START_PRICE);
      update.setLong(2, SECTION_A.eventId());
      update.setString(3, SECTION_A.sectionCode());
      update.executeUpdate();
    }
  }

  private static BigDecimal price(HikariDataSource owner) throws SQLException {
    try (Connection connection = owner.getConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT price FROM sections WHERE event_id = ? AND section_code = ?")) {
      query.setLong(1, SECTION_A.eventId());
      query.setString(2, SECTION_A.sectionCode());
      try (ResultSet row = query.executeQuery()) {
        row.next();
        return row.getBigDecimal(1);
      }
    }
  }

  private static StackSettings settings(Path file) throws IOException {
    return switch (StackSettings.read(new SettingsReader(SettingsReader.load(file)))) {
      case Result.Success<StackSettings, List<String>>(StackSettings checked) -> checked;
      case Result.Error<StackSettings, List<String>>(List<String> problems) ->
          throw new IOException("the settings in " + file + " have problems: " + problems);
    };
  }
}
