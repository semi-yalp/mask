package io.sqlmask.policyserver.web;

import io.sqlmask.error.SqlMaskException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps processing failures to structured JSON errors, byte-identical to the
 * shapes this service served while embedded in mask-core, with per-code
 * status dispatch (aligned with mask-metadata) so the core client's
 * {@code 404 → POLICY_INSTANCE_NOT_FOUND} mapping holds at the seam.
 */
@RestControllerAdvice
public class PolicyApiExceptionHandler {

  public record ApiError(String code, String message) {
  }

  @ExceptionHandler(SqlMaskException.class)
  public ResponseEntity<ApiError> handle(SqlMaskException e) {
    return ResponseEntity.status(statusFor(e.getCode()))
        .body(new ApiError(e.getCode().name(), e.getMessage()));
  }

  private static HttpStatus statusFor(SqlMaskException.Code code) {
    if (code == SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND) {
      return HttpStatus.NOT_FOUND;
    }
    return HttpStatus.BAD_REQUEST;
  }

  /** Policy subsystem errors arrive as plain configuration errors. */
  @ExceptionHandler(io.sqlmask.policy.PolicyException.class)
  public ResponseEntity<ApiError> handlePolicy(io.sqlmask.policy.PolicyException e) {
    return ResponseEntity.badRequest().body(new ApiError("CONFIG_ERROR", e.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST",
        "request body is not valid JSON: " + e.getMessage()));
  }

  /** Unmatched paths (this service serves no /api/audit surface — audit query
   * lives in mask-core) are a 404, not the catch-all 500. */
  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ResponseEntity<ApiError> handleNoResource(
      org.springframework.web.servlet.resource.NoResourceFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ApiError("NOT_FOUND", "no such endpoint: " + e.getResourcePath()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception e) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
        "INTERNAL_ERROR", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
  }
}
