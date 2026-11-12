package io.plainround.seatwave.transactions.stack;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.transactions.labs.ConditionalUpdateHold;
import io.plainround.seatwave.transactions.race.Race;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Lab 1's solution: 200 fans, one conditional UPDATE each, one seat. Needs the stack; the first run
 * creates Lab 1's two tables from lab1/lab1-schema.sql, as the owner, in the guarded schema.
 */
@Tag("stack")
class ConditionalUpdateStackTest {

  private static final Duration DEADLINE = Duration.ofSeconds(60);

  private static StackDatabase database;

  @BeforeAll
  static void openTheLabDatabase() throws Exception {
    database = StackDatabase.open();
    if (StackDatabase.count(
            database.guarded, "SELECT count(*) FROM pg_tables WHERE tablename = 'lab1_seats'")
        == 0) {
      try (var schema =
          ConditionalUpdateStackTest.class.getResourceAsStream("/lab1/lab1-schema.sql")) {
        if (schema == null) {
          throw new IllegalStateException("lab1/lab1-schema.sql is not on the test class path");
        }
        database.runAsOwner(new String(schema.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
  }

  @AfterAll
  static void closeIt() {
    database.close();
  }

  @BeforeEach
  void freeTheSeat() throws Exception {
    database.runAsOwner(
        "UPDATE lab1_seats SET status = 'AVAILABLE', hold_customer = NULL, hold_expires_at = NULL");
  }

  @Test
  void exactlyOneOfTwoHundredFansHoldsTheSeat() throws Exception {
    ConditionalUpdateHold hold =
        new ConditionalUpdateHold(
            database.guarded, StackDatabase.CLOCK, database.settings.holdDuration());
    int fans = database.settings.seatRacers();

    List<Race.Finish<Boolean>> finishes =
        Race.run(
            fans, fan -> () -> hold.hold(StackDatabase.EVENT, "A-7", "fan_" + (fan + 1)), DEADLINE);

    assertThat(finishes)
        .filteredOn(finish -> finish.equals(new Race.Finish.Returned<>(true)))
        .hasSize(1);
    assertThat(finishes)
        .filteredOn(finish -> finish.equals(new Race.Finish.Returned<>(false)))
        .hasSize(fans - 1);
  }
}
