package io.sqlmask.query.web;

import io.sqlmask.common.web.ApiError;
import io.sqlmask.query.error.QueryException;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

  @Test
  void mapsQueryExceptionToPayloadWithClassSpecificStatus() {
    var handler = new ApiExceptionHandler();
    var busy = handler.queryException(new QueryException("QUERY_BUSY", "busy"));
    assertThat(busy.getStatusCode().value()).isEqualTo(429);
    assertThat(busy.getBody()).isEqualTo(new ApiError("QUERY_BUSY", "busy", java.util.List.of()));

    assertThat(handler.queryException(new QueryException("QUERY_TIMEOUT", "t"))
        .getStatusCode().value()).isEqualTo(504);
    assertThat(handler.queryException(new QueryException("REWRITE_SERVICE_UNAVAILABLE", "u"))
        .getStatusCode().value()).isEqualTo(502);
    assertThat(handler.queryException(new QueryException("METADATA_SERVICE_UNAVAILABLE", "u"))
        .getStatusCode().value()).isEqualTo(502);
    // 请求级问题仍是 400
    assertThat(handler.queryException(new QueryException("QUERY_ERROR", "e"))
        .getStatusCode().value()).isEqualTo(400);
    assertThat(handler.queryException(new QueryException("WRITE_STATEMENT", "w"))
        .getStatusCode().value()).isEqualTo(400);
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
