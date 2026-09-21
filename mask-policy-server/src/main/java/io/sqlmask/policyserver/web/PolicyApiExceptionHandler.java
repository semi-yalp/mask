package io.sqlmask.policyserver.web;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.PolicyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps policy-service failures to the {@code {code,message}} contract:
 * unknown instances → 404 with that code, everything else business → 400, an
 * unreadable body → 400 BAD_REQUEST, and a fallback 500.
 */
@RestControllerAdvice
public class PolicyApiExceptionHandler {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(PolicyApiExceptionHandler.class);

  @ExceptionHandler(SqlMaskException.class)
  public ResponseEntity<Map<String, String>> handleSqlMask(SqlMaskException e) {
    HttpStatus status = e.getCode() == SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND
        ? HttpStatus.NOT_FOUND
        : HttpStatus.BAD_REQUEST;
    return ResponseEntity.status(status).body(Map.of("code", e.getCode().name(),
        "message", e.getMessage() == null ? "" : e.getMessage()));
  }

  @ExceptionHandler(PolicyException.class)
  public ResponseEntity<Map<String, String>> handlePolicy(PolicyException e) {
    return ResponseEntity.badRequest().body(Map.of(
        "code", SqlMaskException.Code.CONFIG_ERROR.name(),
        "message", e.getMessage() == null ? "" : e.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(Map.of(
        "code", "BAD_REQUEST",
        "message", "request body is not valid: " + e.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, String>> handleOther(Exception e) {
    log.error("unhandled exception in policy service request", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
        "code", "INTERNAL_ERROR",
        "message", "internal error"));
  }
}