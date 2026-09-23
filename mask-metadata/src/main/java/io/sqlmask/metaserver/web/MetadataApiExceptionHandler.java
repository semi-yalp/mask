package io.sqlmask.metaserver.web;

import io.sqlmask.common.web.ApiError;
import io.sqlmask.common.web.BaseApiExceptionHandler;
import io.sqlmask.error.SqlMaskException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps failures to the shared {code, message, details[]} body with spec §4.3
 * status mapping. Generic mappings come from {@link BaseApiExceptionHandler}.
 */
@RestControllerAdvice
public class MetadataApiExceptionHandler extends BaseApiExceptionHandler {

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
}
