package io.plainround.seatwave.transactions.demo;

import io.plainround.seatwave.transactions.demo.InMemoryRaces.LockChoice;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/**
 * Prints the four in-memory races of Section 8: the only code here that prints. Exit status 0 when
 * every race finished, 1 when one did not.
 */
public final class RaceDemoCli {

  // Section 8's numbers: 200 fans on one seat, 50 requests of one customer, Module 13's limit of 4.
  private static final int SEAT_RACERS = 200;
  private static final int LIMIT_RACERS = 50;
  private static final int HOLD_LIMIT = 4;
  // Module 2's illustrative average ticket, and a $5.00 raise by each of two staff.
  private static final BigDecimal START_PRICE = new BigDecimal("80.00");
  private static final BigDecimal RAISE = new BigDecimal("5.00");

  private RaceDemoCli() {}

  /**
   * Runs the races.
   *
   * @param arguments none
   */
  public static void main(String[] arguments) {
    try {
      System.out.printf(Locale.ROOT, "seat race: %d fans, one seat A-7%n", SEAT_RACERS);
      printSeat("check, then write", InMemoryRaces.checkThenWriteSeat(SEAT_RACERS));
      printSeat("conditional insert", InMemoryRaces.conditionalInsertSeat(SEAT_RACERS));
      System.out.printf(
          Locale.ROOT,
          "limit race: %d requests of one customer, limit %d%n",
          LIMIT_RACERS,
          HOLD_LIMIT);
      printLimit(
          "count, then insert",
          InMemoryRaces.countThenInsert(LIMIT_RACERS, HOLD_LIMIT, LockChoice.NO_LOCK));
      printLimit(
          "lock, count, insert",
          InMemoryRaces.countThenInsert(LIMIT_RACERS, HOLD_LIMIT, LockChoice.ROW_LOCK));
      BigDecimal expected = START_PRICE.add(RAISE).add(RAISE);
      System.out.printf(
          Locale.ROOT, "lost update: two raises of %s on a price of %s%n", RAISE, START_PRICE);
      printPrice(
          "read, add, write",
          InMemoryRaces.raiseTwice(START_PRICE, RAISE, LockChoice.NO_LOCK),
          expected);
      printPrice(
          "lock, read, add, write",
          InMemoryRaces.raiseTwice(START_PRICE, RAISE, LockChoice.ROW_LOCK),
          expected);
    } catch (TimeoutException late) {
      System.err.println("a race did not finish in time: " + late.getMessage());
      System.exit(1);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      System.err.println("interrupted before the races finished");
      System.exit(1);
    }
  }

  private static void printSeat(String method, Tally tally) {
    System.out.printf(
        Locale.ROOT,
        "  %-22s granted %3d, refused %3d, holds on A-7 %3d, double bookings %3d%n",
        method,
        tally.granted(),
        tally.refused(),
        tally.recordsAfter(),
        Math.max(0, tally.recordsAfter() - 1));
  }

  private static void printLimit(String method, Tally tally) {
    System.out.printf(
        Locale.ROOT,
        "  %-22s granted %3d, refused %3d, active holds %3d, over the limit by %3d%n",
        method,
        tally.granted(),
        tally.refused(),
        tally.recordsAfter(),
        Math.max(0, tally.recordsAfter() - HOLD_LIMIT));
  }

  private static void printPrice(String method, BigDecimal finalPrice, BigDecimal expected) {
    System.out.printf(
        Locale.ROOT,
        "  %-22s final %s, expected %s, raises lost %d%n",
        method,
        finalPrice,
        expected,
        expected.subtract(finalPrice).divideToIntegralValue(RAISE).intValueExact());
  }
}
