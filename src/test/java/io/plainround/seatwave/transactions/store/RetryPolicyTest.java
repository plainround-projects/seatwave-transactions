package io.plainround.seatwave.transactions.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  private static final long SEED = 13;
  private static final int SAMPLES = 10_000;
  private static final Duration FIRST = Duration.ofMillis(2);
  private static final Duration CAP = Duration.ofMillis(50);
  private static final int ATTEMPTS = 10;

  @Test
  void waitsAtMostTheFirstBackoffBeforeTheSecondAttempt() {
    RetryPolicy policy = new RetryPolicy(ATTEMPTS, FIRST, CAP);
    SplittableRandom random = new SplittableRandom(SEED);

    for (int sample = 0; sample < SAMPLES; sample++) {
      assertThat(policy.waitBefore(2, random)).isBetween(Duration.ZERO, FIRST);
    }
  }

  @Test
  void doublesTheCeilingForEachLaterAttempt() {
    RetryPolicy policy = new RetryPolicy(ATTEMPTS, FIRST, CAP);
    SplittableRandom random = new SplittableRandom(SEED);
    Duration longest = Duration.ZERO;

    for (int sample = 0; sample < SAMPLES; sample++) {
      Duration wait = policy.waitBefore(4, random);
      longest = wait.compareTo(longest) > 0 ? wait : longest;
    }

    // Attempt 4 may wait up to 2 ms x 2^2 = 8 ms; 10,000 samples come close to it.
    assertThat(longest)
        .isGreaterThan(FIRST.multipliedBy(2))
        .isLessThanOrEqualTo(FIRST.multipliedBy(4));
  }

  @Test
  void neverWaitsLongerThanTheCap() {
    RetryPolicy policy = new RetryPolicy(RetryPolicy.MAX_ATTEMPTS_ALLOWED, FIRST, CAP);
    SplittableRandom random = new SplittableRandom(SEED);

    for (int sample = 0; sample < SAMPLES; sample++) {
      assertThat(policy.waitBefore(RetryPolicy.MAX_ATTEMPTS_ALLOWED, random))
          .isLessThanOrEqualTo(CAP);
    }
  }

  @Test
  void refusesACapShorterThanTheFirstWait() {
    assertThatThrownBy(() -> new RetryPolicy(ATTEMPTS, CAP, FIRST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxBackoff at least as long");
  }

  @Test
  void refusesAWaitBeforeTheFirstAttempt() {
    RetryPolicy policy = new RetryPolicy(ATTEMPTS, FIRST, CAP);

    assertThatThrownBy(() -> policy.waitBefore(1, new SplittableRandom(SEED)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
