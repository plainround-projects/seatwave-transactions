package io.plainround.seatwave.transactions.booking;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One granted hold on one seat. Immutable and thread-safe.
 *
 * @param holdId the hold's key in {@code holds}
 * @param eventId the event
 * @param seat the seat
 * @param customerId the customer who holds it
 * @param expiresAt when the hold stops counting, in UTC
 */
public record Hold(UUID holdId, long eventId, SeatLabel seat, long customerId, Instant expiresAt) {

  /** Checks that no part is missing. */
  public Hold {
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(seat, "seat");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }
}
