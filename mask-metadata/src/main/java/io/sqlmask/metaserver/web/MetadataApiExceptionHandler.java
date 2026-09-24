package io.sqlmask.metaserver.web;

import io.sqlmask.common.web.ApiError;
import io.sqlmask.common.web.BaseApiExceptionHandler;
import io.sqlmask.error.SqlMaskException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps failures to the shared {code, message, details[]} body with spec §4.3
 * status mapping. Generic mappings come from {@link BaseApiExceptionHandler};
 * the 404 and logged-500 overrides below keep the metadata-specific behaviour.
 */
@RestControllerAdvice
public class MetadataApiExceptionHandler extends BaseApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(MetadataApiExceptionHandler.class);

  @ExceptionHandler(SqlMaskException.class)
  public ResponseEntity<ApiError> handle(SqlMaskException e) {
    return ResponseEntity.status(statusFor(e.getCode()))
        .body(ApiError.of(e.getCode().name(), e.getMessage()));
  }

  private static HttpStatus statusFor(SqlMaskException.Code code) {
    return switch (code) {
      case METADATA_INSTANCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
      case METADATA_INSTANCE_EXISTS -> HttpStatus.CONFLICT;
      case METADATA_CREDENTIAL_UNAVAILABLE -> HttpStatus.BAD_REQUEST;
      case INTROSPECT_ERROR -> HttpStatus.BAD_GATEWAY;
      default -> HttpStatus.BAD_REQUEST;
    };
  }

  /** Unmatched paths are a 404, not the catch-all 500. */
  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  public ResponseEntity<ApiError> handleNoResource(
      org.springframework.web.servlet.resource.NoResourceFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ApiError("NOT_FOUND", "no such endpoint: " + e.getResourcePath(), java.util.List.of()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception e) {
    // log the full story server-side; the response stays generic so store
    // internals never reach the caller
    log.error("unhandled error on the metadata service", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ApiError("INTERNAL_ERROR", "internal server error", java.util.List.of()));
  }
}
