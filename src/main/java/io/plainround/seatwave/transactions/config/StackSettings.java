package io.plainround.seatwave.transactions.config;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.transactions.store.RetryPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Everything the stack tests and the labs read from {@code config/stack.properties}, checked once.
 * Immutable and thread-safe.
 *
 * @param jdbcUrl the database, on the loopback address
 * @param ownerUser the role that owns the schema and runs migrations
 * @param ownerPasswordFile where the owner's generated password is
 * @param appUser the role SeatWave runs as: rows only, no DDL, no DELETE
 * @param appPasswordFile where the application's generated password is
 * @param poolSize connections in the application's pool
 * @param connectTimeout the longest wait for a pooled connection
 * @param lockTimeout the longest wait for one lock
 * @param retryPolicy how aborted transactions are retried
 * @param holdDuration how long a hold lasts
 * @param holdLimit active holds per customer that the Java checks enforce
 * @param seatRacers racers in the one-seat race
 * @param limitRacers racers in the hold-limit race
 * @param gatePatience how long a racer waits at a gate
 */
public record StackSettings(
    String jdbcUrl,
    String ownerUser,
    Path ownerPasswordFile,
    String appUser,
    Path appPasswordFile,
    int poolSize,
    Duration connectTimeout,
    Duration lockTimeout,
    RetryPolicy retryPolicy,
    Duration holdDuration,
    int holdLimit,
    int seatRacers,
    int limitRacers,
    Duration gatePatience) {

  // The lab database only: PostgreSQL on the loopback address, a port in Module 13's 21300 to
  // 21399.
  private static final Pattern LOOPBACK_POSTGRES =
      Pattern.compile("jdbc:postgresql://127\\.0\\.0\\.1:213[0-9]{2}/[a-z_]{1,32}");
  private static final Pattern ROLE = Pattern.compile("[a-z_]{1,32}");
  // HikariCP refuses a connection timeout below 250 ms; checking it here turns that into a
  // settings problem instead of a stack trace from the pool's constructor.
  private static final int HIKARI_MIN_CONNECT_TIMEOUT_MILLIS = 250;
  private static final int MAX_WAIT_MILLIS = 120_000;
  // compose.yaml sets max_connections to 200, three of them reserved for superusers; two
  // application pools of 90 and two owner pools of 2 (184 connections) still fit.
  private static final int MAX_POOL_SIZE = 90;
  private static final int MAX_RACERS = 1_000;
  private static final int MAX_HOLD_SECONDS = 3_600;
  private static final int MAX_HOLD_LIMIT = 100;

  /** Checks that nothing is missing. */
  public StackSettings {
    Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    Objects.requireNonNull(ownerUser, "ownerUser");
    Objects.requireNonNull(ownerPasswordFile, "ownerPasswordFile");
    Objects.requireNonNull(appUser, "appUser");
    Objects.requireNonNull(appPasswordFile, "appPasswordFile");
    Objects.requireNonNull(connectTimeout, "connectTimeout");
    Objects.requireNonNull(lockTimeout, "lockTimeout");
    Objects.requireNonNull(retryPolicy, "retryPolicy");
    Objects.requireNonNull(holdDuration, "holdDuration");
    Objects.requireNonNull(gatePatience, "gatePatience");
  }

  /**
   * Reads and checks every key, and reports all problems together.
   *
   * @param reader the loaded file
   * @return the settings, or one line per problem
   */
  public static Result<StackSettings, List<String>> read(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String jdbcUrl = reader.textMatching("db.url", LOOPBACK_POSTGRES);
    String ownerUser = reader.textMatching("db.ownerUser", ROLE);
    String ownerPasswordFile = reader.filePath("db.ownerPasswordFile");
    String appUser = reader.textMatching("db.appUser", ROLE);
    String appPasswordFile = reader.filePath("db.appPasswordFile");
    int poolSize = reader.intBetween("db.poolSize", 1, MAX_POOL_SIZE);
    Duration connectTimeout =
        reader.millisBetween(
            "db.connectTimeoutMillis", HIKARI_MIN_CONNECT_TIMEOUT_MILLIS, MAX_WAIT_MILLIS);
    Duration lockTimeout = reader.millisBetween("db.lockTimeoutMillis", 1, MAX_WAIT_MILLIS);
    int maxAttempts = reader.intBetween("retry.maxAttempts", 1, RetryPolicy.MAX_ATTEMPTS_ALLOWED);
    Duration firstBackoff = reader.millisBetween("retry.firstBackoffMillis", 1, MAX_WAIT_MILLIS);
    Duration maxBackoff = reader.millisBetween("retry.maxBackoffMillis", 1, MAX_WAIT_MILLIS);
    Duration holdDuration =
        Duration.ofSeconds(reader.intBetween("hold.durationSeconds", 1, MAX_HOLD_SECONDS));
    int holdLimit = reader.intBetween("hold.maxActivePerCustomer", 1, MAX_HOLD_LIMIT);
    int seatRacers = reader.intBetween("race.seatRacers", 1, MAX_RACERS);
    int limitRacers = reader.intBetween("race.limitRacers", 1, MAX_RACERS);
    Duration gatePatience = reader.millisBetween("race.gatePatienceMillis", 1, MAX_WAIT_MILLIS);
    // Cross-key rules run only when both keys were read cleanly, so one missing key gives one
    // problem line, never a second, misleading one.
    List<String> keyProblems = reader.problems();
    if (!mentions(keyProblems, "retry.firstBackoffMillis", "retry.maxBackoffMillis")
        && maxBackoff.compareTo(firstBackoff) < 0) {
      reader.addProblem("retry.maxBackoffMillis must be at least retry.firstBackoffMillis");
    }
    // Every racer of the limit race holds an open transaction at the gate, so each needs its own
    // connection; with fewer, the racers still waiting for one never reach the gate.
    if (!mentions(keyProblems, "race.limitRacers", "db.poolSize") && limitRacers > poolSize) {
      reader.addProblem("race.limitRacers must not be larger than db.poolSize");
    }
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(
        new StackSettings(
            jdbcUrl,
            ownerUser,
            Path.of(ownerPasswordFile),
            appUser,
            Path.of(appPasswordFile),
            poolSize,
            connectTimeout,
            lockTimeout,
            new RetryPolicy(maxAttempts, firstBackoff, maxBackoff),
            holdDuration,
            holdLimit,
            seatRacers,
            limitRacers,
            gatePatience));
  }

  private static boolean mentions(List<String> problems, String firstKey, String secondKey) {
    return problems.stream()
        .anyMatch(
            problem -> problem.startsWith(firstKey + " ") || problem.startsWith(secondKey + " "));
  }
}
