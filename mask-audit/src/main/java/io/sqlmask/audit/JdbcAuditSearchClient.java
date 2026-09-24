package io.sqlmask.audit;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQL search over the {@code audit_event} table, same contract as the ES
 * {@link AuditSearchClient}: fixed filters, time range, newest first,
 * offset/limit paging, payload documents returned as maps.
 */
public final class JdbcAuditSearchClient {

  private final JdbcTemplate jdbc;

  public JdbcAuditSearchClient(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public AuditSearchResult search(AuditQuery q) {
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (q.eventType() != null && !q.eventType().isBlank()) {
      where.append(" AND event_type = ?");
      args.add(q.eventType());
    }
    if (q.outcome() != null && !q.outcome().isBlank()) {
      where.append(" AND outcome = ?");
      args.add(q.outcome());
    }
    if (q.instance() != null && !q.instance().isBlank()) {
      where.append(" AND instance = ?");
      args.add(q.instance());
    }
    if (q.user() != null && !q.user().isBlank()) {
      where.append(" AND actor_user = ?");
      args.add(q.user());
    }
    if (q.resourceType() != null && !q.resourceType().isBlank()) {
      where.append(" AND resource_type = ?");
      args.add(q.resourceType());
    }
    if (q.action() != null && !q.action().isBlank()) {
      where.append(" AND action = ?");
      args.add(q.action());
    }
    if (q.from() != null) {
      where.append(" AND occurred_at >= ?");
      args.add(Timestamp.from(q.from()));
    }
    if (q.to() != null) {
      where.append(" AND occurred_at <= ?");
      args.add(Timestamp.from(q.to()));
    }
    long total = jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_event" + where, Long.class, args.toArray());
    // offset paging, 0-based page (same contract as the ES client)
    String sql = "SELECT payload FROM audit_event" + where
        + " ORDER BY occurred_at DESC, id DESC LIMIT " + q.size()
        + " OFFSET " + (long) q.page() * q.size();
    List<Map<String, Object>> events = jdbc.query(sql,
        (rs, i) -> AuditEventJson.read(rs.getString("payload")), args.toArray());
    return new AuditSearchResult(total, events);
  }
}
