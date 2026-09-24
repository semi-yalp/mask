package io.sqlmask.audit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * JDBC audit store: the same best-effort async discipline as the ES recorder
 * (bounded queue, single daemon writer, batch inserts, drop-on-overflow with
 * counters) writing to the shared SQL datasource's {@code audit_event} table.
 * The document JSON keeps the full event shape so the search API can return
 * the same maps the ES backend returns.
 */
public final class JdbcAuditRecorder implements AuditRecorder, AutoCloseable {

  private static final System.Logger LOG = System.getLogger(JdbcAuditRecorder.class.getName());

  private final JdbcTemplate jdbc;
  private final AuditProperties properties;
  private final BlockingQueue<Map<String, Object>> queue;
  private final Thread writer;
  private final MeterRegistry meters;
  private volatile boolean running = true;

  public JdbcAuditRecorder(JdbcTemplate jdbc, AuditProperties properties, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.properties = properties;
    this.meters = meters;
    this.queue = new LinkedBlockingQueue<>(properties.getQueueCapacity());
    if (meters != null) {
      meters.gauge("sqlmask.audit.jdbc.queue.depth", queue, q -> q.size());
      meters.gauge("sqlmask.audit.jdbc.queue.capacity", queue, q -> (double) q.remainingCapacity());
    }
    this.writer = new Thread(this::drain, "audit-jdbc-writer");
    this.writer.setDaemon(true);
    this.writer.start();
  }

  @Override
  public void record(AuditEvent event) {
    Map<String, Object> document = AuditEventJson.toDocument(event, properties.getSqlMaxChars());
    if (!queue.offer(document)) {
      LOG.log(System.Logger.Level.WARNING, "audit: jdbc queue full, dropping event (dropped"
          + "-total={0})", ++dropped);
      count("dropped");
    }
  }

  private long dropped;

  private void drain() {
    while (running || !queue.isEmpty()) {
      try {
        Map<String, Object> first = queue.poll(500, TimeUnit.MILLISECONDS);
        if (first == null) {
          continue;
        }
        List<Map<String, Object>> batch = new ArrayList<>(properties.getBatchSize());
        batch.add(first);
        queue.drainTo(batch, properties.getBatchSize() - 1);
        flush(batch);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (RuntimeException e) {
        LOG.log(System.Logger.Level.WARNING, "audit: jdbc writer failed: {0}", e.getMessage());
      }
    }
  }

  private void flush(List<Map<String, Object>> batch) {
    try {
      jdbc.batchUpdate(
          "INSERT INTO audit_event (event_type, outcome, occurred_at, actor_user, instance, "
              + "resource_type, action, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
          batch, batch.size(),
          (ps, doc) -> {
            ps.setString(1, str(doc.get("eventType")));
            ps.setString(2, str(doc.get("outcome")));
            Object timestamp = doc.get("@timestamp");
            ps.setObject(3, timestamp instanceof Number number
                ? new java.sql.Timestamp(number.longValue())
                : java.sql.Timestamp.from(java.time.Instant.now()));
            ps.setString(4, str(doc.get("actor") instanceof Map<?, ?> actor
                ? actor.get("user") : null));
            ps.setString(5, str(doc.get("instance")));
            ps.setString(6, str(doc.get("resourceType")));
            ps.setString(7, str(doc.get("action")));
            ps.setString(8, AuditEventJson.write(doc));
          });
      count("written");
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.WARNING,
          "audit: jdbc write failing, dropping {0} events: {1}", batch.size(), e.getMessage());
      count("dropped");
    }
  }

  private void count(String action) {
    if (meters != null) {
      meters.counter("sqlmask.audit.jdbc", "action", action).increment();
    }
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  @Override
  public void close() {
    running = false;
    writer.interrupt();
    try {
      writer.join(2000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
