package io.plainround.seatwave.transactions.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class HoldRequestTest {

  private static final long CUSTOMER = 7;
  private static final long EVENT = 1;

  @Test
  void sortsSeatsIntoTheOneLockOrder() {
    HoldRequest request =
        new HoldRequest(CUSTOMER, EVENT, List.of(new SeatLabel("A-8"), new SeatLabel("A-7")));

    assertThat(request.seatsInLockOrder())
        .containsExactly(new SeatLabel("A-7"), new SeatLabel("A-8"));
  }

  @Test
  void keepsTheClientOrderInSeats() {
    HoldRequest request =
        new HoldRequest(CUSTOMER, EVENT, List.of(new SeatLabel("A-8"), new SeatLabel("A-7")));

    assertThat(request.seats()).containsExactly(new SeatLabel("A-8"), new SeatLabel("A-7"));
  }

  @Test
  void refusesAnEmptyRequest() {
    assertThatThrownBy(() -> new HoldRequest(CUSTOMER, EVENT, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1 to 4 seats");
  }

  @Test
  void refusesFiveSeats() {
    List<SeatLabel> five =
        List.of("A-1", "A-2", "A-3", "A-4", "A-5").stream().map(SeatLabel::new).toList();

    assertThatThrownBy(() -> new HoldRequest(CUSTOMER, EVENT, five))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("named 5");
  }

  @Test
  void refusesTheSameSeatTwice() {
    List<SeatLabel> twice = List.of(new SeatLabel("A-7"), new SeatLabel("A-7"));

    assertThatThrownBy(() -> new HoldRequest(CUSTOMER, EVENT, twice))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("each seat once");
  }

  @Test
  void refusesACustomerIdOfZero() {
    List<SeatLabel> one = List.of(new SeatLabel("A-7"));

    assertThatThrownBy(() -> new HoldRequest(0, EVENT, one))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 1");
  }
}
