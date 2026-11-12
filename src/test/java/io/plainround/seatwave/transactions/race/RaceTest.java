package io.plainround.seatwave.transactions.race;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class RaceTest {

  private static final int RACERS = 200;
  private static final Duration DEADLINE = Duration.ofSeconds(10);
  private static final Duration SHORT_DEADLINE = Duration.ofMillis(200);

  @Test
  void returnsOneFinishPerRacerInRacerOrder() throws Exception {
    List<Race.Finish<Integer>> finishes = Race.run(RACERS, index -> () -> index, DEADLINE);

    assertThat(finishes).hasSize(RACERS);
    assertThat(finishes.get(RACERS - 1)).isEqualTo(new Race.Finish.Returned<>(RACERS - 1));
  }

  @Test
  void recordsARacerThatThrowsInsteadOfLosingIt() throws Exception {
    IllegalStateException boom = new IllegalStateException("racer 3 failed");

    List<Race.Finish<Integer>> finishes =
        Race.run(
            5,
            index ->
                () -> {
                  if (index == 3) {
                    throw boom;
                  }
                  return index;
                },
            DEADLINE);

    assertThat(finishes.get(3)).isEqualTo(new Race.Finish.Threw<>(boom));
  }

  @Test
  void startsNoRacerBeforeEveryRacerExists() throws Exception {
    // Each racer arrives at the gate; it opens only if all 200 are running at the same time.
    Gate allRunning = new Gate(RACERS, DEADLINE);

    List<Race.Finish<Boolean>> finishes =
        Race.run(
            RACERS,
            index ->
                () -> {
                  allRunning.arriveAndWait();
                  return true;
                },
            DEADLINE);

    assertThat(finishes).allMatch(finish -> finish.equals(new Race.Finish.Returned<>(true)));
  }

  @Test
  void interruptsTheRacersAndFailsWhenTheDeadlinePasses() {
    CountDownLatch never = new CountDownLatch(1);

    assertThatThrownBy(() ->
            Race.run(
                2,
                index ->
                    () -> {
                      never.await();
                      return index;
                    },
                SHORT_DEADLINE))
        .isInstanceOf(TimeoutException.class);
  }

  @Test
  void aGateThatTimesOutSaysHowManyNeverArrived() {
    Gate gate = new Gate(3, SHORT_DEADLINE);

    assertThatThrownBy(gate::arriveAndWait)
        .isInstanceOf(Gate.GateTimeoutException.class)
        .hasMessageStartingWith("2 racer(s) had not arrived");
  }
}
