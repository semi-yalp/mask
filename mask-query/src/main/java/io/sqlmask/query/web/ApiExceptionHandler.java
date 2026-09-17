package io.sqlmask.query.web;

import io.sqlmask.query.error.QueryException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(QueryException.class)
  public ResponseEntity<Map<String, Object>> queryException(QueryException e) {
    return ResponseEntity.badRequest().body(Map.of(
        "code", e.code(),
        "message", e.getMessage() == null ? "" : e.getMessage(),
        "details", List.of()));
  }

  /** 畸形 JSON 响应体（与 QueryException 映射同形），避免落到默认的 500。 */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> malformedJsonBody(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(Map.of(
        "code", QueryException.CONFIG_ERROR,
        "message", "malformed JSON body",
        "details", List.of()));
  }
}
