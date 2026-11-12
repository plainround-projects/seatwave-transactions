package io.plainround.seatwave.transactions.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SqlStatesTest {

  @ParameterizedTest
  @CsvSource({
    "40001, true", // serialization_failure: run the whole transaction again
    "40P01, true", // deadlock_detected: the database chose this transaction as the victim
    "23505, false", // unique_violation: the seat is taken; a retry gets the same answer
    "23514, false", // check_violation, the hold limit included
    "55P03, false", // lock_not_available: refuse with 503, do not pile on more waiting
    "08006, false" // connection failure: not this layer's retry (Module 36)
  })
  void retriesOnlyWhatTheDatabaseAbortedToProtectIsolation(String sqlState, boolean retryable) {
    assertThat(SqlStates.isRetryable(new SQLException("failed", sqlState))).isEqualTo(retryable);
  }
}
