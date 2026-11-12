package io.plainround.seatwave.transactions.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One sold seat, with the price that was paid. Immutable and thread-safe.
 *
 * @param bookingId the booking's key in {@code bookings}
 * @param holdId the hold it confirmed
 * @param seat the seat
 * @param price the price paid, exact
 * @param currency the ISO 4217 code, such as USD
 * @param confirmedAt when it was confirmed, in UTC
 */
public record Booking(
    UUID bookingId,
    UUID holdId,
    SeatLabel seat,
    BigDecimal price,
    String currency,
    Instant confirmedAt) {

  /** Checks that no part is missing and the price is not negative. */
  public Booking {
    Objects.requireNonNull(bookingId, "bookingId");
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(seat, "seat");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(confirmedAt, "confirmedAt");
    if (Objects.requireNonNull(price, "price").signum() < 0) {
      throw new IllegalArgumentException("a price cannot be negative, but was " + price);
    }
  }
}
