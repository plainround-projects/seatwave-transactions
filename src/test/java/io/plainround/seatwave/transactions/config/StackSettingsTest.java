package io.plainround.seatwave.transactions.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class StackSettingsTest {

  @Test
  void readsTheShippedSettingsFile() throws IOException {
    Result<StackSettings, List<String>> read = StackSettings.read(reader(shipped()));

    assertThat(read.discardError())
        .hasValueSatisfying(settings -> assertThat(settings.limitRacers()).isEqualTo(50));
  }

  @Test
  void refusesADatabaseThatIsNotOnTheLoopbackAddress() throws IOException {
    Properties settings = shipped();
    settings.setProperty("db.url", "jdbc:postgresql://0.0.0.0:21300/seatwave");

    assertThat(problems(settings)).singleElement().asString().startsWith("db.url must match");
  }

  @Test
  void refusesAConnectTimeoutHikariWouldRejectAsASettingsProblem() throws IOException {
    Properties settings = shipped();
    settings.setProperty("db.connectTimeoutMillis", "100");

    assertThat(problems(settings))
        .containsExactly("db.connectTimeoutMillis must be from 250 to 120000, but was 100");
  }

  @Test
  void refusesMoreLimitRacersThanConnections() throws IOException {
    Properties settings = shipped();
    settings.setProperty("race.limitRacers", "65");

    assertThat(problems(settings))
        .containsExactly("race.limitRacers must not be larger than db.poolSize");
  }

  @Test
  void reportsAMissingKeyOnceWithoutASecondCrossKeyLine() throws IOException {
    Properties settings = shipped();
    settings.remove("db.poolSize");

    assertThat(problems(settings)).containsExactly("db.poolSize is missing");
  }

  @Test
  void reportsEveryProblemTogether() throws IOException {
    Properties settings = shipped();
    settings.remove("db.appUser");
    settings.setProperty("retry.maxAttempts", "0");

    assertThat(problems(settings)).hasSize(2);
  }

  private static Properties shipped() throws IOException {
    return SettingsReader.load(Path.of("config/stack.properties"));
  }

  private static SettingsReader reader(Properties properties) {
    return new SettingsReader(properties);
  }

  private static List<String> problems(Properties properties) {
    return switch (StackSettings.read(reader(properties))) {
      case Result.Error<StackSettings, List<String>>(List<String> found) -> found;
      case Result.Success<StackSettings, List<String>>(StackSettings settings) -> List.of();
    };
  }
}
