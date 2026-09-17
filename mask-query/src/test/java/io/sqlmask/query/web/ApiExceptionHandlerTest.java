package io.sqlmask.query.web;

import io.sqlmask.query.error.QueryException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

  @Test
  void mapsQueryExceptionToPayload() {
    var handler = new ApiExceptionHandler();
    var response = handler.queryException(new QueryException("QUERY_BUSY", "busy"));
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).containsEntry("code", "QUERY_BUSY")
        .containsEntry("message", "busy").containsKey("details");
  }
}
