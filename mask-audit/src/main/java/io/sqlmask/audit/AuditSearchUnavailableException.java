package io.sqlmask.audit;

/**
 * Raised when Elasticsearch cannot answer an audit search (I/O failure,
 * transport error or 5xx). Mapped to HTTP 502 upstream: the audit store is
 * unavailable, not the audited service.
 */
public class AuditSearchUnavailableException extends RuntimeException {

  public AuditSearchUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
