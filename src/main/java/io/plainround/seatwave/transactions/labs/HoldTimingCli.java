package io.plainround.seatwave.transactions.labs;

import com.github.sviperll.result4j.Result;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import io.plainround.seatwave.transactions.booking.Hold;
import io.plainround.seatwave.transactions.booking.HoldRefusal;
import io.plainround.seatwave.transactions.booking.HoldRequest;
import io.plainround.seatwave.transactions.booking.SeatLabel;
import io.plainround.seatwave.transactions.config.SettingsReader;
import io.plainround.seatwave.transactions.config.StackSettings;
import io.plainround.seatwave.transactions.race.Race;
import io.plainround.seatwave.transactions.store.Database;
import io.plainround.seatwave.transactions.store.HoldStore;
import io.plainround.seatwave.transactions.store.Transactions;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.api.FlywayException;

/**
 * Measures the two times Chapter 13.5's estimates rest on, with the production {@code HoldStore} on
 * the lab database: how long one hold transaction takes when nobody competes for its seat, and how
 * long each of two fans waits when both ask for the same seat at the same moment (the loser waits
 * for the winner's claim window, then is told the seat is taken). Every time is measured on the
 * client, so it includes the round trips into Docker's virtual machine. The only code here that
 * prints. Exit status 0 when every hold was answered as expected.
 */
public final class HoldTimingCli {

  // Not counted: lets the JIT compile the hot path before anything is timed.
  private static final int WARM_UP_HOLDS = 400;
  private static final int SOLO_HOLDS = 2_000;
  private static final int PAIRS = 1_000;
  private static final int FANS_PER_PAIR = 2;
  // Section F warms up, sections C and D (1,000 seats each) are the solo seats, E the pairs' seats.
  private static final String WARM_UP_SECTION = "F";
  private static final List<String> SOLO_SECTIONS = List.of("C", "D");
  private static final String PAIR_SECTION = "E";
  private static final int SEATS_PER_SECTION = 1_000;
  private static final long EVENT = 1;
  private static final long SEED = 13;
  private static final Duration PAIR_DEADLINE = Duration.ofSeconds(30);
  private static final double NANOS_PER_MILLI = 1e6;
  private static final double[] PERCENTILES = {0.50, 0.90, 0.99};
  private static final int PERCENT = 100;

  private HoldTimingCli() {}

  /**
   * Runs the measurement.
   *
   * @param arguments the settings file, such as config/stack.properties
   */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: HoldTimingCli <settings file>");
      System.exit(2);
    }
    // The exit comes after the try, so both pools are closed on every path first.
    System.exit(measure(Path.of(arguments[0])));
  }

  private static int measure(Path settingsFile) {
    try {
      StackSettings settings = settings(settingsFile);
      Database.migrate(settings, Database.GUARDED_SCHEMA);
      try (HikariDataSource owner =
              Database.pool(settings, Database.GUARDED_SCHEMA, Database.Role.OWNER);
          HikariDataSource app =
              Database.pool(settings, Database.GUARDED_SCHEMA, Database.Role.APPLICATION)) {
        Database.seedIfEmpty(owner);
        removeEveryHold(owner);
        HoldStore holds =
            new HoldStore(
                new Transactions(
                    app,
                    settings.retryPolicy(),
                    settings.lockTimeout(),
                    new SplittableRandom(SEED)),
                Clock.systemUTC(),
                settings.holdDuration());
        int limit = settings.holdLimit();
        timeAlone(
            holds,
            registerCustomers(app, "warm", WARM_UP_HOLDS / limit),
            seats(List.of(WARM_UP_SECTION), WARM_UP_HOLDS),
            limit);
        long[] solo =
            timeAlone(
                holds,
                registerCustomers(app, "solo", SOLO_HOLDS / limit),
                seats(SOLO_SECTIONS, SOLO_HOLDS),
                limit);
        System.out.println(
            line("hold transaction, nobody else on the seat (" + SOLO_HOLDS + " holds)", solo));
        return timePairs(holds, registerCustomers(app, "pair", PAIRS * FANS_PER_PAIR));
      }
    } catch (IOException
        | SQLException
        | TimeoutException
        | FlywayException
        | HikariPool.PoolInitializationException failure) {
      System.err.println("the measurement stopped: " + failure.getMessage());
      return 1;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      System.err.println("interrupted before the measurement finished");
      return 1;
    }
  }

  // One fan at a time, one seat each, each customer up to the limit: every hold must be granted.
  private static long[] timeAlone(
      HoldStore holds, List<Long> customers, List<SeatLabel> seats, int holdsPerCustomer)
      throws SQLException {
    long[] millis = new long[seats.size()];
    for (int index = 0; index < seats.size(); index++) {
      HoldRequest request =
          new HoldRequest(
              customers.get(index / holdsPerCustomer), EVENT, List.of(seats.get(index)));
      long started = System.nanoTime();
      Result<List<Hold>, HoldRefusal> outcome = holds.holdSeats(request);
      millis[index] = System.nanoTime() - started;
      if (outcome.isError()) {
        throw new SQLException("hold " + index + " was refused: " + outcome);
      }
    }
    return millis;
  }

  // Two fans start together on a free seat; exactly one must be granted and one told it is taken.
  private static int timePairs(HoldStore holds, List<Long> customers)
      throws SQLException, TimeoutException, InterruptedException {
    List<SeatLabel> seats = seats(List.of(PAIR_SECTION), PAIRS);
    long[] winners = new long[PAIRS];
    long[] losers = new long[PAIRS];
    int unexpected = 0;
    for (int pair = 0; pair < PAIRS; pair++) {
      int first = pair * FANS_PER_PAIR;
      SeatLabel seat = seats.get(pair);
      List<Race.Finish<Timed>> finishes =
          Race.run(
              FANS_PER_PAIR,
              fan -> () -> timedHold(holds, customers.get(first + fan), seat),
              PAIR_DEADLINE);
      List<Timed> answers = new ArrayList<>(FANS_PER_PAIR);
      for (Race.Finish<Timed> finish : finishes) {
        switch (finish) {
          case Race.Finish.Returned<Timed>(Timed timed) -> answers.add(timed);
          case Race.Finish.Threw<Timed>(Exception failure) ->
              throw new SQLException("a fan of pair " + pair + " failed", failure);
        }
      }
      List<Timed> granted = answers.stream().filter(Timed::wasGranted).toList();
      List<Timed> taken = answers.stream().filter(timed -> !timed.wasGranted()).toList();
      if (granted.size() != 1 || taken.size() != 1) {
        unexpected++;
        continue;
      }
      winners[pair] = granted.getFirst().nanos();
      losers[pair] = taken.getFirst().nanos();
    }
    if (unexpected > 0) {
      System.out.printf(
          Locale.ROOT,
          "%d of %d pairs did not end with one hold and one refusal%n",
          unexpected,
          PAIRS);
      return 1;
    }
    System.out.println(line("two fans on one seat (" + PAIRS + " pairs), the winner", winners));
    System.out.println(line("two fans on one seat (" + PAIRS + " pairs), the loser", losers));
    return 0;
  }

  /** One fan's answer and how long it took. */
  private record Timed(boolean wasGranted, long nanos) {}

  private static Timed timedHold(HoldStore holds, long customer, SeatLabel seat)
      throws SQLException {
    long started = System.nanoTime();
    Result<List<Hold>, HoldRefusal> outcome =
        holds.holdSeats(new HoldRequest(customer, EVENT, List.of(seat)));
    long elapsed = System.nanoTime() - started;
    return switch (outcome) {
      case Result.Success<List<Hold>, HoldRefusal>(List<Hold> _) -> new Timed(true, elapsed);
      case Result.Error<List<Hold>, HoldRefusal>(HoldRefusal.SeatTaken _) ->
          new Timed(false, elapsed);
      case Result.Error<List<Hold>, HoldRefusal>(HoldRefusal other) ->
          throw new SQLException("expected a hold or 'seat taken', but got " + other);
    };
  }

  private static String line(String label, long[] nanos) {
    long[] sorted = nanos.clone();
    Arrays.sort(sorted);
    StringBuilder text = new StringBuilder(label).append(':');
    for (double percentile : PERCENTILES) {
      // The nearest-rank percentile: the smallest value with at least this share at or below it.
      int rank = (int) Math.ceil(percentile * sorted.length) - 1;
      text.append(
          String.format(
              Locale.ROOT,
              " p%d %.2f ms,",
              Math.round(percentile * PERCENT),
              sorted[rank] / NANOS_PER_MILLI));
    }
    double meanNanos = Arrays.stream(sorted).average().orElse(0);
    return text.append(
            String.format(
                Locale.ROOT,
                " max %.2f ms, mean %.2f ms",
                sorted[sorted.length - 1] / NANOS_PER_MILLI,
                meanNanos / NANOS_PER_MILLI))
        .toString();
  }

  private static List<SeatLabel> seats(List<String> sections, int count) {
    List<SeatLabel> seats = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      String section = sections.get(index / SEATS_PER_SECTION);
      seats.add(new SeatLabel(section + "-" + (index % SEATS_PER_SECTION + 1)));
    }
    return List.copyOf(seats);
  }

  // The lab database only (the settings refuse any other address): the owner may delete, and a
  // run starts with no holds, so every seat it times is free.
  private static void removeEveryHold(HikariDataSource owner) throws SQLException {
    try (Connection connection = owner.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM bookings; DELETE FROM holds; DELETE FROM customers;");
    }
  }

  private static List<Long> registerCustomers(HikariDataSource app, String prefix, int count)
      throws SQLException {
    List<Long> customerIds = new ArrayList<>(count);
    try (Connection connection = app.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO customers (customer_ref, created_at) VALUES (?, now())"
                    + " RETURNING customer_id")) {
      for (int fan = 1; fan <= count; fan++) {
        insert.setString(1, prefix + "_" + fan);
        try (ResultSet key = insert.executeQuery()) {
          key.next();
          customerIds.add(key.getLong(1));
        }
      }
    }
    return List.copyOf(customerIds);
  }

  private static StackSettings settings(Path file) throws IOException {
    return switch (StackSettings.read(new SettingsReader(SettingsReader.load(file)))) {
      case Result.Success<StackSettings, List<String>>(StackSettings checked) -> checked;
      case Result.Error<StackSettings, List<String>>(List<String> problems) ->
          throw new IOException("the settings in " + file + " have problems: " + problems);
    };
  }
}
