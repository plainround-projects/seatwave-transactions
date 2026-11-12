package io.plainround.seatwave.transactions.booking;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * One fan's request to hold one to four seats of one event, all or nothing. Validated at
 * construction, so an invalid request cannot exist. Immutable and thread-safe.
 *
 * @param customerId the customer's key in {@code customers}
 * @param eventId the event's key in {@code events}
 * @param seats the seats, in the order the client sent them
 */
public record HoldRequest(long customerId, long eventId, List<SeatLabel> seats) {

  /** The most seats one request may hold: the per-customer limit of active holds (V2's trigger). */
  public static final int MAX_SEATS_PER_REQUEST = 4;

  /**
   * Checks the keys and the seat list.
   *
   * @throws IllegalArgumentException if a key is not positive, the list is empty, longer than
   *     {@link #MAX_SEATS_PER_REQUEST} or names a seat twice
   */
  public HoldRequest {
    if (customerId < 1 || eventId < 1) {
      throw new IllegalArgumentException(
          "customerId and eventId must be at least 1, but were " + customerId + " and " + eventId);
    }
    seats = List.copyOf(Objects.requireNonNull(seats, "seats"));
    if (seats.isEmpty() || seats.size() > MAX_SEATS_PER_REQUEST) {
      throw new IllegalArgumentException(
          "a hold names 1 to " + MAX_SEATS_PER_REQUEST + " seats, but named " + seats.size());
    }
    if (new HashSet<>(seats).size() != seats.size()) {
      throw new IllegalArgumentException("a hold names each seat once, but was " + seats);
    }
  }

  /**
   * Returns the seats in the one order every transaction uses, so two requests that share seats
   * wait for each other instead of deadlocking.
   *
   * @return the seats sorted by label
   */
  public List<SeatLabel> seatsInLockOrder() {
    return seats.stream().sorted().toList();
  }
}
