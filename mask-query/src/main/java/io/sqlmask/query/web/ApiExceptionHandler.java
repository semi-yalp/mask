package io.sqlmask.query.web;

import io.sqlmask.common.web.ApiError;
import io.sqlmask.common.web.BaseApiExceptionHandler;
import io.sqlmask.query.error.QueryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Query error codes are business 400s; the shared base carries the generic
 * mappings (unexpected 500). {@link #handleUnreadable} overrides the base
 * mapping (same name and signature, so it is a true override, not an
 * ambiguous second mapping) to keep the QueryException code CONFIG_ERROR
 * instead of the base's BAD_REQUEST.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends BaseApiExceptionHandler {

  @ExceptionHandler(QueryException.class)
  public ResponseEntity<ApiError> queryException(QueryException e) {
    return ResponseEntity.status(statusFor(e.code())).body(
        new ApiError(e.code(), e.getMessage() == null ? "" : e.getMessage(), java.util.List.of()));
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

  /** 畸形 JSON 响应体(与 QueryException 映射同形),避免落到默认的 500。 */
  @Override
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(
        new ApiError(QueryException.CONFIG_ERROR, "malformed JSON body", java.util.List.of()));
  }
}
