package io.plainround.seatwave.transactions.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SeatLabelTest {

  @ParameterizedTest
  @ValueSource(strings = {"A-1", "T-1000", "ABCD-9999"})
  void acceptsASectionAndANumber(String text) {
    assertThat(new SeatLabel(text).text()).isEqualTo(text);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "A7", "a-7", "A-0", "A-07", "A-10000", "ABCDE-1", "A-7 ", "A-7;--"})
  void refusesAnythingElse(String text) {
    assertThatThrownBy(() -> new SeatLabel(text))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'" + text + "'");
  }
}
