package io.sqlmask.audit;

/** Raised when ES cannot answer an audit search (mapped to HTTP 502 upstream). */
public class AuditSearchUnavailableException extends RuntimeException {

  public AuditSearchUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
