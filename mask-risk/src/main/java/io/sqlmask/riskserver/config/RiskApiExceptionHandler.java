package io.sqlmask.riskserver.config;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Uniform {@code {"code","message"}} error body like the other services. */
@RestControllerAdvice("io.sqlmask.riskserver")
@org.springframework.core.annotation.Order(0)
public class RiskApiExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, String> badRequest(IllegalArgumentException e) {
    return Map.of("code", "BAD_REQUEST", "message", String.valueOf(e.getMessage()));
  }

  @ExceptionHandler(java.util.NoSuchElementException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public Map<String, String> notFound(java.util.NoSuchElementException e) {
    return Map.of("code", "NOT_FOUND", "message", String.valueOf(e.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public Map<String, String> internal(Exception e) {
    return Map.of("code", "INTERNAL_ERROR",
        "message", e.getClass().getSimpleName() + ": " + e.getMessage());
  }
}
