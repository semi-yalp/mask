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
 * code + message instead of a stack trace. Status mirrors the failure class
 * (404 missing instance, 503 downstream outage, 400 request problems);
 * generic mappings (malformed JSON, unexpected 500) come from the shared
<<<<<<< HEAD
 * {@link BaseApiExceptionHandler} — unexpected 500s are logged server-side
 * and answered with a generic message so JDBC/host internals never reach the
 * caller.
=======
 * {@link BaseApiExceptionHandler}.
>>>>>>> origin/main
 */
@RestControllerAdvice
public class ApiExceptionHandler extends BaseApiExceptionHandler {

<<<<<<< HEAD
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

=======
>>>>>>> origin/main
  @ExceptionHandler(SqlMaskException.class)
  public ResponseEntity<ApiError> handle(SqlMaskException e) {
    return ResponseEntity.status(statusFor(e.getCode().name()))
        .body(ApiError.of(e.getCode().name(), e.getMessage()));
<<<<<<< HEAD
  }

  private static HttpStatus statusFor(String code) {
    return switch (code) {
      case "POLICY_INSTANCE_NOT_FOUND", "METADATA_INSTANCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
      case "POLICY_SERVICE_UNAVAILABLE", "METADATA_SERVICE_UNAVAILABLE",
          "METADATA_CREDENTIAL_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
      default -> HttpStatus.BAD_REQUEST;
    };
=======
>>>>>>> origin/main
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

<<<<<<< HEAD
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception e) {
    log.error("unhandled error on the rewrite service", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiError.internal("internal server error"));
=======
  private static HttpStatus statusFor(String code) {
    return switch (code) {
      case "POLICY_INSTANCE_NOT_FOUND", "METADATA_INSTANCE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
      case "POLICY_SERVICE_UNAVAILABLE", "METADATA_SERVICE_UNAVAILABLE",
          "METADATA_CREDENTIAL_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
      default -> HttpStatus.BAD_REQUEST;
    };
>>>>>>> origin/main
  }
}
