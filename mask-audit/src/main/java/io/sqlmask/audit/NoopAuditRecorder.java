package io.sqlmask.audit;

/** Discards every event (audit.enabled=false). */
public final class NoopAuditRecorder implements AuditRecorder {

  @Override
  public void record(AuditEvent event) {
  }
}
