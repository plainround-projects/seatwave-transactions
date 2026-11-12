package io.plainround.seatwave.transactions.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.transactions.demo.InMemoryRaces.LockChoice;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InMemoryRacesTest {

  private static final int SEAT_RACERS = 200;
  private static final int LIMIT_RACERS = 50;
  private static final int LIMIT = 4;
  private static final BigDecimal START = new BigDecimal("80.00");
  private static final BigDecimal RAISE = new BigDecimal("5.00");

  @Test
  void checkThenWriteGivesTheSeatToEveryRacer() throws Exception {
    assertThat(InMemoryRaces.checkThenWriteSeat(SEAT_RACERS))
        .isEqualTo(new Tally(SEAT_RACERS, SEAT_RACERS, 0, SEAT_RACERS));
  }

  @Test
  void conditionalInsertGivesTheSeatToExactlyOne() throws Exception {
    assertThat(InMemoryRaces.conditionalInsertSeat(SEAT_RACERS))
        .isEqualTo(new Tally(SEAT_RACERS, 1, SEAT_RACERS - 1, 1));
  }

  @Test
  void countThenInsertBreaksTheLimit() throws Exception {
    assertThat(
            InMemoryRaces.countThenInsert(LIMIT_RACERS, LIMIT, LockChoice.NO_LOCK).recordsAfter())
        .isEqualTo(LIMIT_RACERS);
  }

  @Test
  void theCustomerLockKeepsTheLimit() throws Exception {
    assertThat(InMemoryRaces.countThenInsert(LIMIT_RACERS, LIMIT, LockChoice.ROW_LOCK))
        .isEqualTo(new Tally(LIMIT_RACERS, LIMIT, LIMIT_RACERS - LIMIT, LIMIT));
  }

  @Test
  void readAddWriteLosesOneOfTwoRaises() throws Exception {
    assertThat(InMemoryRaces.raiseTwice(START, RAISE, LockChoice.NO_LOCK))
        .isEqualByComparingTo("85.00");
  }

  @Test
  void theRowLockKeepsBothRaises() throws Exception {
    assertThat(InMemoryRaces.raiseTwice(START, RAISE, LockChoice.ROW_LOCK))
        .isEqualByComparingTo("90.00");
  }
}
