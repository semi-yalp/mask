package io.sqlmask.policyserver.web;

import io.sqlmask.common.web.ApiError;
import io.sqlmask.common.web.BaseApiExceptionHandler;
import io.sqlmask.error.SqlMaskException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps processing failures to the shared structured error body, with
 * per-code status dispatch (aligned with mask-metadata) so the core client's
 * {@code 404 → POLICY_INSTANCE_NOT_FOUND} mapping holds at the seam. Generic
 * mappings (malformed JSON, unexpected 500) come from the shared
 * {@link BaseApiExceptionHandler}.
 */
@RestControllerAdvice
public class PolicyApiExceptionHandler extends BaseApiExceptionHandler {

  @ExceptionHandler(SqlMaskException.class)
  public ResponseEntity<ApiError> handle(SqlMaskException e) {
    return ResponseEntity.status(statusFor(e.getCode()))
        .body(ApiError.of(e.getCode().name(), e.getMessage()));
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
    return ResponseEntity.badRequest().body(ApiError.of("CONFIG_ERROR", e.getMessage()));
  }

  /** Unmatched paths (this service serves no /api/audit surface — audit query
   * lives in mask-core) are a 404, not the catch-all 500. */
  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ResponseEntity<ApiError> handleNoResource(
      org.springframework.web.servlet.resource.NoResourceFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiError.of("NOT_FOUND", "no such endpoint: " + e.getResourcePath()));
  }
}
