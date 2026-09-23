package io.sqlmask.query.web;

import io.sqlmask.common.web.ApiError;
import io.sqlmask.common.web.BaseApiExceptionHandler;
import io.sqlmask.query.error.QueryException;
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
    return ResponseEntity.badRequest().body(
        new ApiError(e.code(), e.getMessage() == null ? "" : e.getMessage(), java.util.List.of()));
  }

  /** 畸形 JSON 响应体(与 QueryException 映射同形),避免落到默认的 500。 */
  @Override
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(
        new ApiError(QueryException.CONFIG_ERROR, "malformed JSON body", java.util.List.of()));
  }
}
