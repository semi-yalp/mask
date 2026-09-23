package io.sqlmask.audit;

/**
 * Decorates another {@link AuditRecorder} with best-effort forwarding to the
 * risk monitoring service: the delegate keeps its existing behavior (ES write
 * or no-op) and every event is additionally offered to the {@link RiskForwarder}
 * queue. Neither leg may throw or block the request path.
 */
public final class ForwardingAuditRecorder implements AuditRecorder {

  private final AuditRecorder delegate;
  private final RiskForwarder forwarder;

  public ForwardingAuditRecorder(AuditRecorder delegate, RiskForwarder forwarder) {
    this.delegate = delegate;
    this.forwarder = forwarder;
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
    forwarder.record(event);
  }
}
