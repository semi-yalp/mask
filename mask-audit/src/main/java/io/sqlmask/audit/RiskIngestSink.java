package io.sqlmask.audit;

/**
 * In-process risk ingestion: implemented by the monolith's risk domain bridge
 * so the audit pipeline can feed detection without an HTTP hop. The remote
 * path (RiskForwarder, {@code risk.forward.url}) stays available for
 * standalone risk deployments; the local sink wins when both exist.
 */
public interface RiskIngestSink {

  void ingest(AuditEvent event);
}
