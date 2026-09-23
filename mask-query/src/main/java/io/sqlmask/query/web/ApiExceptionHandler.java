package io.sqlmask.query.web;

import io.sqlmask.query.error.QueryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(QueryException.class)
  public ResponseEntity<Map<String, Object>> queryException(QueryException e) {
    return ResponseEntity.status(statusFor(e.code())).body(Map.of(
        "code", e.code(),
        "message", e.getMessage() == null ? "" : e.getMessage(),
        "details", List.of()));
  }

  /** Status mirrors the failure class so gateways and standard retry/backoff
   * machinery can act on it: saturation 429, upstream timeouts 504, upstream
   * outage 502; request-level problems stay 400. The async container timeout
   * answers 503 from the controller's onTimeout, and the API key filter
   * answers 401 — neither passes through here. */
  private static HttpStatus statusFor(String code) {
    return switch (code) {
      case QueryException.QUERY_BUSY -> HttpStatus.TOO_MANY_REQUESTS;
      case QueryException.QUERY_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
      // METADATA_SERVICE_UNAVAILABLE is thrown as a literal (no constant on QueryException)
      case QueryException.REWRITE_SERVICE_UNAVAILABLE, "METADATA_SERVICE_UNAVAILABLE" ->
          HttpStatus.BAD_GATEWAY;
      default -> HttpStatus.BAD_REQUEST;
    };
  }

  /** 畸形 JSON 响应体（与 QueryException 映射同形），避免落到默认的 500。 */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> malformedJsonBody(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(Map.of(
        "code", QueryException.CONFIG_ERROR,
        "message", "malformed JSON body",
        "details", List.of()));
  }
}
