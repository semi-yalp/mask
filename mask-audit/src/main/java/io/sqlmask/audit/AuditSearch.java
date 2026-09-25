package io.sqlmask.audit;

/**
 * Search seam of the audit pipeline: fixed filters, time range, newest first,
 * offset paging. Implementations: {@link AuditSearchClient} (Elasticsearch)
 * and {@link JdbcAuditSearchClient} (shared SQL datasource) — chosen by
 * {@code audit.store}.
 */
public interface AuditSearch {

  AuditSearchResult search(AuditQuery q);
}
