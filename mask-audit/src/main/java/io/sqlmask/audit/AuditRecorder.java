package io.sqlmask.audit;

/** Emit one audit event. Implementations must never throw. */
public interface AuditRecorder {

  void record(AuditEvent event);
}
