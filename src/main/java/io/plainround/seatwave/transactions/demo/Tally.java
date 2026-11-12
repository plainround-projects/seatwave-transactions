package io.plainround.seatwave.transactions.demo;

/**
 * What one in-memory race ended with. Immutable and thread-safe.
 *
 * @param racers how many racers ran
 * @param granted how many were told "yes"
 * @param refused how many were told "no"
 * @param recordsAfter how many records the shared state holds at the end
 */
public record Tally(int racers, int granted, int refused, int recordsAfter) {

  /** Checks that every racer has exactly one outcome. */
  public Tally {
    if (granted < 0 || refused < 0 || granted + refused != racers) {
      throw new IllegalArgumentException(
          "every racer ends granted or refused: "
              + granted
              + " + "
              + refused
              + " is not "
              + racers);
    }
  }
}
