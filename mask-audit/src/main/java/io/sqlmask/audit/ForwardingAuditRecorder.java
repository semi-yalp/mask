package io.sqlmask.audit;

/**
 * Decorates another {@link AuditRecorder} with best-effort risk forwarding:
 * the delegate keeps its existing behavior (jdbc write, ES write or no-op)
 * and every event is additionally offered to the configured {@link RiskEventSink}
 * — the HTTP {@link RiskForwarder} for standalone risk deployments, or the
 * in-process {@link RiskIngestSink} bridge in the monolith. Neither leg may
 * throw or block the request path.
 */
public final class ForwardingAuditRecorder implements AuditRecorder {

  /** What the forwarding decorator feeds: any best-effort risk consumer. */
  public interface RiskEventSink {
    void ship(AuditEvent event);
  }

  private final AuditRecorder delegate;
  private final RiskEventSink sink;

  public ForwardingAuditRecorder(AuditRecorder delegate, RiskEventSink sink) {
    this.delegate = delegate;
    this.sink = sink;
  }

  /** The recorder that would have been injected without forwarding. */
  public AuditRecorder delegate() {
    return delegate;
  }

  @Override
  public void record(AuditEvent event) {
    try {
      delegate.record(event);
    } catch (RuntimeException e) {
      // SPI promise: record never throws - defensive only.
    }
    sink.ship(event);
  }
}
