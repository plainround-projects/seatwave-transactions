package io.plainround.seatwave.transactions.race;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Holds every racer at one point until all of them have reached it, then lets them all go: the
 * worst interleaving, forced on purpose. A broken check-then-write passes its check in every racer
 * before any racer writes, so the anomaly happens on every run instead of now and then.
 *
 * <p>One use only. After the gate has opened, {@link #arriveAndWait()} returns at once, so a
 * retried attempt passes straight through. Thread-safe.
 */
public final class Gate {

  private final CountDownLatch notYetArrived;
  private final Duration patience;

  /**
   * Creates a gate.
   *
   * @param racers how many must arrive before it opens
   * @param patience how long one racer waits before it gives up; a racer that never arrives
   *     (because it failed earlier) must not hang the others forever
   */
  public Gate(int racers, Duration patience) {
    if (racers < 1) {
      throw new IllegalArgumentException("a gate needs at least one racer, but was " + racers);
    }
    this.notYetArrived = new CountDownLatch(racers);
    this.patience = Objects.requireNonNull(patience, "patience");
  }

  /** A gate that never waits: the same code runs with the interleaving the scheduler picks. */
  public static Gate open() {
    return new Gate(1, Duration.ZERO).openedAlready();
  }

  /**
   * Arrives and waits until every racer has arrived.
   *
   * @throws GateTimeoutException if the others did not all arrive within the patience
   * @throws InterruptedException if the wait was interrupted
   */
  public void arriveAndWait() throws InterruptedException {
    notYetArrived.countDown();
    if (!notYetArrived.await(patience.toNanos(), TimeUnit.NANOSECONDS)) {
      throw new GateTimeoutException(
          notYetArrived.getCount() + " racer(s) had not arrived after " + patience);
    }
  }

  private Gate openedAlready() {
    notYetArrived.countDown();
    return this;
  }

  /** The racers did not all arrive in time: the run is invalid and must be reported as such. */
  public static final class GateTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    GateTimeoutException(String message) {
      super(message);
    }
  }
}
