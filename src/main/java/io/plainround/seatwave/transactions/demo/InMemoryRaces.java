package io.plainround.seatwave.transactions.demo;

import io.plainround.seatwave.transactions.race.Gate;
import io.plainround.seatwave.transactions.race.Race;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The anomalies of Module 13, and their fixes, with the JDK alone: shared Java objects play the
 * tables, threads play the transactions, and a {@link Gate} forces the worst interleaving. It runs
 * without a database, so it shows the shape of each race, not PostgreSQL's behavior; Section 8's
 * stack tests show that. Each method creates its own state, so calls are independent.
 */
public final class InMemoryRaces {

  private static final Duration GATE_PATIENCE = Duration.ofSeconds(10);
  private static final Duration RACE_DEADLINE = Duration.ofSeconds(30);

  private InMemoryRaces() {}

  /**
   * Many fans hold one seat by "check, then write": every racer checks, all pass the gate, all
   * write. The list of holds plays a holds table with no unique index.
   *
   * @param racers how many fans
   * @return the tally; recordsAfter is the number of holds on the one seat
   * @throws TimeoutException if the race did not finish
   * @throws InterruptedException if interrupted
   */
  public static Tally checkThenWriteSeat(int racers) throws TimeoutException, InterruptedException {
    List<Integer> holdsOnSeat = Collections.synchronizedList(new ArrayList<>());
    Gate afterCheck = new Gate(racers, GATE_PATIENCE);
    List<Race.Finish<Boolean>> finishes =
        Race.run(
            racers,
            fan ->
                () -> {
                  boolean looksFree = holdsOnSeat.isEmpty();
                  afterCheck.arriveAndWait();
                  if (!looksFree) {
                    return false;
                  }
                  holdsOnSeat.add(fan);
                  return true;
                },
            RACE_DEADLINE);
    return tally(finishes, holdsOnSeat.size());
  }

  /**
   * The same race through one atomic "insert if absent", the in-memory twin of a unique index with
   * {@code ON CONFLICT DO NOTHING}: the check and the write are one step, so the gate has no gap to
   * widen.
   *
   * @param racers how many fans
   * @return the tally; recordsAfter is the number of claims on the one seat
   * @throws TimeoutException if the race did not finish
   * @throws InterruptedException if interrupted
   */
  public static Tally conditionalInsertSeat(int racers)
      throws TimeoutException, InterruptedException {
    ConcurrentMap<String, Integer> claimBySeat = new ConcurrentHashMap<>();
    Gate beforeInsert = new Gate(racers, GATE_PATIENCE);
    List<Race.Finish<Boolean>> finishes =
        Race.run(
            racers,
            fan ->
                () -> {
                  beforeInsert.arriveAndWait();
                  return claimBySeat.putIfAbsent("A-7", fan) == null;
                },
            RACE_DEADLINE);
    return tally(finishes, claimBySeat.size());
  }

  /**
   * One customer's racers each count the customer's holds, then add one for a different seat. No
   * two racers write the same thing, so nothing conflicts, yet together they break the limit: write
   * skew.
   *
   * @param racers how many concurrent requests of one customer
   * @param limit the most active holds allowed
   * @param lockChoice NO_LOCK: count then add; ROW_LOCK: take the customer's lock, then count and
   *     add, the twin of {@code SELECT ... FOR UPDATE} on the customer row at read committed
   * @return the tally; recordsAfter is the customer's holds
   * @throws TimeoutException if the race did not finish
   * @throws InterruptedException if interrupted
   */
  public static Tally countThenInsert(int racers, int limit, LockChoice lockChoice)
      throws TimeoutException, InterruptedException {
    List<Integer> holdsOfCustomer = Collections.synchronizedList(new ArrayList<>());
    ReentrantLock customerRow = new ReentrantLock();
    Gate gate = new Gate(racers, GATE_PATIENCE);
    List<Race.Finish<Boolean>> finishes =
        Race.run(
            racers,
            request ->
                () -> {
                  if (lockChoice == LockChoice.NO_LOCK) {
                    int counted = holdsOfCustomer.size();
                    gate.arriveAndWait();
                    return counted < limit && holdsOfCustomer.add(request);
                  }
                  // Everyone has started before anyone locks, as with the database version.
                  gate.arriveAndWait();
                  customerRow.lock();
                  try {
                    return holdsOfCustomer.size() < limit && holdsOfCustomer.add(request);
                  } finally {
                    customerRow.unlock();
                  }
                },
            RACE_DEADLINE);
    return tally(finishes, holdsOfCustomer.size());
  }

  /**
   * Two box-office staff each raise one price by the same amount: read, add, write.
   *
   * @param startPrice the price before both raises
   * @param raise the amount each adds
   * @param lockChoice NO_LOCK: read, gate, write (a lost update); ROW_LOCK: lock, read, write
   * @return the final price
   * @throws TimeoutException if the race did not finish
   * @throws InterruptedException if interrupted
   */
  public static BigDecimal raiseTwice(
      BigDecimal startPrice, BigDecimal raise, LockChoice lockChoice)
      throws TimeoutException, InterruptedException {
    int staff = 2;
    AtomicReference<BigDecimal> price = new AtomicReference<>(startPrice);
    ReentrantLock sectionRow = new ReentrantLock();
    Gate gate = new Gate(staff, GATE_PATIENCE);
    Race.run(
        staff,
        clerk ->
            () -> {
              if (lockChoice == LockChoice.NO_LOCK) {
                BigDecimal read = price.get();
                gate.arriveAndWait();
                price.set(read.add(raise));
                return true;
              }
              gate.arriveAndWait();
              sectionRow.lock();
              try {
                price.set(price.get().add(raise));
                return true;
              } finally {
                sectionRow.unlock();
              }
            },
        RACE_DEADLINE);
    return price.get();
  }

  /** Whether a racer takes the lock before it reads. */
  public enum LockChoice {
    /** Read, wait at the gate, decide, write. */
    NO_LOCK,
    /** Wait at the gate, take the lock, read, decide, write, release. */
    ROW_LOCK
  }

  private static Tally tally(List<Race.Finish<Boolean>> finishes, int recordsAfter) {
    int granted = 0;
    for (Race.Finish<Boolean> finish : finishes) {
      switch (finish) {
        case Race.Finish.Returned<Boolean>(Boolean wasGranted) -> granted += wasGranted ? 1 : 0;
        case Race.Finish.Threw<Boolean>(Exception failure) ->
            throw new IllegalStateException("a racer failed, so the tally would be wrong", failure);
      }
    }
    return new Tally(finishes.size(), granted, finishes.size() - granted, recordsAfter);
  }
}
