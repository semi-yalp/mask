package io.sqlmask.common.web;

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
 */
public abstract class BaseApiExceptionHandler {

  /** Malformed JSON bodies map to 400 instead of falling through to 500. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST",
        "request body is not valid JSON: " + e.getMessage(), List.of()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception e) {
    return ResponseEntity.internalServerError().body(ApiError.internal(
        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
  }
}
