package io.sqlmask.audit;

/** Fire-and-forget audit sink; implementations must never throw into the
 * caller's request path. */
public interface AuditRecorder {

  void record(AuditEvent event);
}
