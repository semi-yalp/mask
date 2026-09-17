package io.sqlmask.query.web;

import io.sqlmask.query.error.QueryException;
import org.springframework.http.ResponseEntity;
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
}
