package io.sqlmask.server;

import io.sqlmask.common.web.ApiError;
import io.sqlmask.common.web.BaseApiExceptionHandler;
import io.sqlmask.error.SqlMaskException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps processing failures to structured JSON errors so the UI can show
 * code + message instead of a stack trace. Generic mappings (malformed JSON,
 * unexpected 500) come from the shared {@link BaseApiExceptionHandler}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends BaseApiExceptionHandler {

  @ExceptionHandler(SqlMaskException.class)
  public ResponseEntity<ApiError> handle(SqlMaskException e) {
    return ResponseEntity.badRequest().body(ApiError.of(e.getCode().name(), e.getMessage()));
  }

  /** Policy subsystem errors arrive as plain configuration errors. */
  @ExceptionHandler(io.sqlmask.policy.PolicyException.class)
  public ResponseEntity<ApiError> handlePolicy(io.sqlmask.policy.PolicyException e) {
    return ResponseEntity.badRequest().body(ApiError.of("CONFIG_ERROR", e.getMessage()));
  }

  /** ES outage behind the audit query surface surfaces as a 502, not a 500. */
  @ExceptionHandler(io.sqlmask.audit.AuditSearchUnavailableException.class)
  public ResponseEntity<ApiError> handleAuditUnavailable(
      io.sqlmask.audit.AuditSearchUnavailableException e) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiError.of(
        "AUDIT_SEARCH_UNAVAILABLE",
        e.getMessage() == null ? "elasticsearch unavailable" : e.getMessage()));
  }
}
