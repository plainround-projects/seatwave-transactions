package io.plainround.seatwave.transactions.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import org.junit.jupiter.api.Test;

class IsolationLevelTest {

  @Test
  void mapsEachLevelToItsJdbcConstant() {
    assertThat(IsolationLevel.values())
        .extracting(IsolationLevel::jdbcLevel)
        .containsExactly(
            Connection.TRANSACTION_READ_COMMITTED,
            Connection.TRANSACTION_REPEATABLE_READ,
            Connection.TRANSACTION_SERIALIZABLE);
  }
}
