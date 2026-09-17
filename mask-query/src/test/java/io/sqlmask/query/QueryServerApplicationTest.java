package io.sqlmask.query;

import io.sqlmask.query.config.QueryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class QueryServerApplicationTest {

  @Autowired QueryProperties props;

  @Test
  void contextLoadsWithDefaults() {
    assertThat(props.timeoutSeconds()).isEqualTo(30);
    assertThat(props.maxRows()).isEqualTo(1000);
    assertThat(props.maxRowsHard()).isEqualTo(10000);
    assertThat(props.fetchSize()).isEqualTo(500);
    assertThat(props.maxConcurrentPerInstance()).isEqualTo(10);
  }
}
