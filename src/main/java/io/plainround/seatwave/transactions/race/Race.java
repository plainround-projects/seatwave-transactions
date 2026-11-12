package io.plainround.seatwave.transactions.race;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;

/**
 * Runs N racers at the same moment, each on its own virtual thread, and returns exactly one finish
 * per racer, in racer order. A start barrier holds every racer until all threads exist, so they
 * really start together instead of one after another as the threads are created.
 *
 * <p>Stateless and thread-safe; each call creates and closes its own executor.
 */
public final class Race {

  private Race() {}

  /**
   * How one racer ended: with a value, or with the exception it threw.
   *
   * @param <T> the value type
   */
  public sealed interface Finish<T> {
    /**
     * The racer returned a value.
     *
     * @param value the value
     * @param <T> the value type
     */
    record Returned<T>(T value) implements Finish<T> {}

    /**
     * The racer threw.
     *
     * @param failure what it threw
     * @param <T> the value type
     */
    record Threw<T>(Exception failure) implements Finish<T> {}
  }

  /**
   * Runs the racers.
   *
   * @param racers how many
   * @param racer builds racer number {@code index} (from 0)
   * @param deadline how long the whole race may take
   * @param <T> what a racer returns
   * @return one finish per racer, index for index
   * @throws TimeoutException if a racer had not finished by the deadline; every racer still running
   *     is interrupted before this is thrown
   * @throws InterruptedException if the caller was interrupted
   */
  public static <T> List<Finish<T>> run(
      int racers, IntFunction<Callable<T>> racer, Duration deadline)
      throws TimeoutException, InterruptedException {
    if (racers < 1) {
      throw new IllegalArgumentException("a race needs at least one racer, but was " + racers);
    }
    Objects.requireNonNull(racer, "racer");
    Objects.requireNonNull(deadline, "deadline");
    CountDownLatch startLine = new CountDownLatch(1);
    List<Future<T>> running = new ArrayList<>(racers);
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int racerIndex = 0; racerIndex < racers; racerIndex++) {
        Callable<T> work = Objects.requireNonNull(racer.apply(racerIndex), "racer " + racerIndex);
        running.add(
            threads.submit(() -> {
              startLine.await();
              return work.call();
            }));
      }
      startLine.countDown();
      long deadlineNanos = System.nanoTime() + deadline.toNanos();
      List<Finish<T>> finishes = new ArrayList<>(racers);
      for (Future<T> future : running) {
        finishes.add(await(future, deadlineNanos - System.nanoTime(), running));
      }
      return List.copyOf(finishes);
    }
  }

  private static <T> Finish<T> await(Future<T> future, long nanosLeft, List<Future<T>> all)
      throws TimeoutException, InterruptedException {
    try {
      return new Finish.Returned<>(future.get(Math.max(0, nanosLeft), TimeUnit.NANOSECONDS));
    } catch (ExecutionException threw) {
      return new Finish.Threw<>(threw.getCause() instanceof Exception failure ? failure : threw);
    } catch (TimeoutException late) {
      // Interrupt every racer so closing the executor does not wait for them forever.
      all.forEach(other -> other.cancel(true));
      throw late;
    }
  }
}
