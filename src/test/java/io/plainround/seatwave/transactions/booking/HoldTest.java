package io.plainround.seatwave.transactions.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HoldTest {

  private static final UUID HOLD = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final SeatLabel SEAT = new SeatLabel("A-7");
  private static final Instant EXPIRES_AT = Instant.parse("2026-12-08T10:05:00Z");
  private static final long EVENT = 1;
  private static final long CUSTOMER = 42;

  @Test
  void keepsWhatItWasGiven() {
    Hold hold = new Hold(HOLD, EVENT, SEAT, CUSTOMER, EXPIRES_AT);

    assertThat(hold.seat()).isEqualTo(SEAT);
  }

  @Test
  void namesTheMissingPart() {
    assertThatThrownBy(() -> new Hold(HOLD, EVENT, SEAT, CUSTOMER, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("expiresAt");
  }

  @Test
  void aRefusalNamesItsSeat() {
    assertThatThrownBy(() -> new HoldRefusal.SeatTaken(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("seat");
  }
}
