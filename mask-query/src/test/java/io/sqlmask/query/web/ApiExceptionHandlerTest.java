package io.sqlmask.query.web;

import io.sqlmask.common.web.ApiError;
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
    assertThat(response.getBody()).isEqualTo(new ApiError("QUERY_BUSY", "busy", java.util.List.of()));
  }

  @Test
  void mapsMalformedJsonBodyToConfigError() {
    var handler = new ApiExceptionHandler();
    var response = handler.handleUnreadable(new HttpMessageNotReadableException(
        "JSON parse error", new MockHttpInputMessage(new byte[0])));
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).isEqualTo(new ApiError("CONFIG_ERROR", "malformed JSON body", java.util.List.of()));
  }
}
