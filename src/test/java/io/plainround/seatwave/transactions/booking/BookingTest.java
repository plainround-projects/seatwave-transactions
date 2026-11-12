package io.plainround.seatwave.transactions.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookingTest {

  private static final UUID BOOKING = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID HOLD = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final SeatLabel SEAT = new SeatLabel("A-7");
  private static final Instant CONFIRMED_AT = Instant.parse("2026-12-08T10:00:00Z");

  @Test
  void keepsThePricePaidExactly() {
    Booking booking =
        new Booking(BOOKING, HOLD, SEAT, new BigDecimal("80.00"), "USD", CONFIRMED_AT);

    assertThat(booking.price()).isEqualByComparingTo("80.00");
  }

  @Test
  void acceptsAFreeTicket() {
    Booking booking = new Booking(BOOKING, HOLD, SEAT, BigDecimal.ZERO, "USD", CONFIRMED_AT);

    assertThat(booking.price()).isZero();
  }

  @Test
  void refusesANegativePrice() {
    assertThatThrownBy(
            () -> new Booking(BOOKING, HOLD, SEAT, new BigDecimal("-0.01"), "USD", CONFIRMED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("-0.01");
  }

  @Test
  void namesTheMissingPart() {
    assertThatThrownBy(() -> new Booking(BOOKING, HOLD, SEAT, null, "USD", CONFIRMED_AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("price");
  }
}
