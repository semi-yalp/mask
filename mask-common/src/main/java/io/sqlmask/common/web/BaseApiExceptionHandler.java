package io.sqlmask.common.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.List;

/**
 * Shared error-mapping base for every service's {@code @RestControllerAdvice}.
 * Subclasses extend it and add their service-specific handlers (kernel
 * {@code SqlMaskException} status mapping, query error codes, audit
 * unavailability, ...); Spring resolves inherited handler methods, so the
 * generic mappings below apply everywhere without being re-declared.
 *
 * <p>The generic mappings never echo {@code e.getMessage()} to the client:
 * unexpected messages can carry JDBC URLs, upstream URIs or SQLState — those
 * details are logged server-side only.
 */
public abstract class BaseApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(BaseApiExceptionHandler.class);

  /** Malformed JSON bodies map to 400 instead of falling through to 500. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
    log.warn("request body is not valid JSON: {}", e.getMessage());
    return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST",
        "request body is not valid JSON", List.of()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception e) {
    log.error("unexpected server error", e);
    return ResponseEntity.internalServerError().body(ApiError.internal("internal server error"));
  }
}
