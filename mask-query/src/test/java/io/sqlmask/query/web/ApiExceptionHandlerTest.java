package io.sqlmask.query.web;

import io.sqlmask.query.error.QueryException;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

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

  @Test
  void mapsMalformedJsonBodyToConfigError() {
    var handler = new ApiExceptionHandler();
    var response = handler.malformedJsonBody(new HttpMessageNotReadableException(
        "JSON parse error", new MockHttpInputMessage(new byte[0])));
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).containsEntry("code", "CONFIG_ERROR")
        .containsEntry("message", "malformed JSON body")
        .containsEntry("details", java.util.List.of());
  }
}
