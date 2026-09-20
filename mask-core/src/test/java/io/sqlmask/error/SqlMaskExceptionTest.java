package io.sqlmask.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlMaskExceptionTest {

  @Test
  void v3CodesExist() {
    assertThat(SqlMaskException.Code.valueOf("CONNECTION_FAILED")).isNotNull();
    assertThat(SqlMaskException.Code.valueOf("VERSION_NOT_FOUND")).isNotNull();
    assertThat(SqlMaskException.Code.valueOf("CONCURRENT_MODIFICATION")).isNotNull();
  }
}