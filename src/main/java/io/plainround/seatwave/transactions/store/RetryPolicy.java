package io.plainround.seatwave.transactions.store;

import java.time.Duration;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * How often and how patiently a transaction that the database aborted is run again: exponential
 * backoff with full jitter and a cap. Immutable and thread-safe; the random source is passed in.
 *
 * @param maxAttempts the most times one transaction runs, the first included (1 means no retry)
 * @param firstBackoff the largest wait before the second attempt
 * @param maxBackoff the cap on any one wait
 */
public record RetryPolicy(int maxAttempts, Duration firstBackoff, Duration maxBackoff) {

  /**
   * At most 20 attempts: a transaction that loses 20 times in a row is refused, not retried
   * forever.
   */
  public static final int MAX_ATTEMPTS_ALLOWED = 20;

  /**
   * Checks the values.
   *
   * @throws IllegalArgumentException if attempts are outside 1 to 20, a wait is not positive, or
   *     the cap is below the first wait
   */
  public RetryPolicy {
    Objects.requireNonNull(firstBackoff, "firstBackoff");
    Objects.requireNonNull(maxBackoff, "maxBackoff");
    if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_ALLOWED) {
      throw new IllegalArgumentException(
          "maxAttempts must be from 1 to " + MAX_ATTEMPTS_ALLOWED + ", but was " + maxAttempts);
    }
    if (firstBackoff.isNegative()
        || firstBackoff.isZero()
        || maxBackoff.compareTo(firstBackoff) < 0) {
      throw new IllegalArgumentException(
          "firstBackoff must be positive and maxBackoff at least as long, but were "
              + firstBackoff
              + " and "
              + maxBackoff);
    }
  }

  /**
   * Returns the wait before an attempt: a random duration from zero to {@code firstBackoff ×
   * 2^(n-2)}, capped at {@code maxBackoff}. Full jitter spreads the losers of one conflict apart,
   * so they do not collide again on the same millisecond.
   *
   * @param attempt the attempt about to run, at least 2
   * @param random the random source
   * @return the wait
   */
  public Duration waitBefore(int attempt, RandomGenerator random) {
    Objects.requireNonNull(random, "random");
    if (attempt < 2 || attempt > maxAttempts) {
      throw new IllegalArgumentException(
          "a wait comes before attempts 2 to " + maxAttempts + ", not " + attempt);
    }
    // The shift stops growing at 2^20, far past any cap, so the multiplication cannot overflow.
    long ceilingNanos =
        Math.min(
            maxBackoff.toNanos(),
            Math.multiplyExact(
                firstBackoff.toNanos(), 1L << Math.min(attempt - 2, MAX_ATTEMPTS_ALLOWED)));
    return Duration.ofNanos(random.nextLong(ceilingNanos + 1));
  }
}
