package io.sqlmask.riskserver.stats;

import io.sqlmask.riskserver.model.Alert;
import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskSeverity;
import io.sqlmask.riskserver.model.RuleHit;
import io.sqlmask.riskserver.model.SensitiveColumn;
import io.sqlmask.riskserver.store.MemoryRiskStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dashboard aggregation over the in-memory store: windowed totals, severity
 * distributions, fixed-bucket time series and the Top lists. Computed on
 * demand (tens of thousands of events scan in a few ms).
 */
@Component
public class StatsCalculator {

  private final MemoryRiskStore store;

  public StatsCalculator(MemoryRiskStore store) {
    this.store = store;
  }

  public Map<String, Object> overview(int windowHours, int bucketMinutes) {
    long now = System.currentTimeMillis();
    long windowMs = windowHours * 3_600_000L;
    long since = now - windowMs;
    int bucketMs = Math.max(1, bucketMinutes) * 60_000;
    int bucketCount = (int) Math.max(1, windowMs / bucketMs);

    List<RiskEvent> events = store.eventsSince(since);
    List<Alert> alerts = store.snapshotAlerts();

    long flagged = events.stream().filter(RiskEvent::flagged).count();
    long scoreSum = 0;
    for (RiskEvent event : events) {
      scoreSum += event.riskScore();
    }

    Map<String, Object> eventsBlock = new LinkedHashMap<>();
    eventsBlock.put("total", events.size());
    eventsBlock.put("flagged", flagged);
    eventsBlock.put("hitRate", events.isEmpty() ? 0 : round(flagged * 100.0 / events.size()));
    eventsBlock.put("avgScore", events.isEmpty() ? 0 : round(scoreSum * 1.0 / events.size()));
    Map<String, Long> bySeverity = new LinkedHashMap<>();
    for (RiskSeverity severity : RiskSeverity.values()) {
      bySeverity.put(severity.wire(), 0L);
    }
    for (RiskEvent event : events) {
      bySeverity.merge(event.topSeverity().wire(), 1L, Long::sum);
    }
    eventsBlock.put("bySeverity", bySeverity);

    long open = alerts.stream().filter(a -> Alert.STATUS_OPEN.equals(a.status())).count();
    long acknowledged = alerts.stream()
        .filter(a -> Alert.STATUS_ACKNOWLEDGED.equals(a.status())).count();
    long resolvedInWindow = alerts.stream()
        .filter(a -> Alert.STATUS_RESOLVED.equals(a.status()) && a.updatedAt() >= since)
        .count();
    Map<String, Long> alertsBySeverity = new LinkedHashMap<>();
    for (RiskSeverity severity : RiskSeverity.values()) {
      alertsBySeverity.put(severity.wire(), 0L);
    }
    for (Alert alert : alerts) {
      if (!Alert.STATUS_RESOLVED.equals(alert.status())) {
        alertsBySeverity.merge(alert.severity().wire(), 1L, Long::sum);
      }
    }
    Map<String, Object> alertsBlock = new LinkedHashMap<>();
    alertsBlock.put("open", open);
    alertsBlock.put("acknowledged", acknowledged);
    alertsBlock.put("resolvedInWindow", resolvedInWindow);
    alertsBlock.put("bySeverity", alertsBySeverity);

    // --- time series ---
    long firstBucket = now - (long) bucketCount * bucketMs;
    long[] evCounts = new long[bucketCount];
    long[] flCounts = new long[bucketCount];
    long[] alCounts = new long[bucketCount];
    for (RiskEvent event : events) {
      int idx = bucketIndex(event.timestamp().toEpochMilli(), firstBucket, bucketMs, bucketCount);
      if (idx >= 0) {
        evCounts[idx]++;
        if (event.flagged()) {
          flCounts[idx]++;
        }
      }
    }
    for (Alert alert : alerts) {
      int idx = bucketIndex(alert.createdAt(), firstBucket, bucketMs, bucketCount);
      if (idx >= 0) {
        alCounts[idx]++;
      }
    }
    List<Map<String, Object>> timeseries = new ArrayList<>(bucketCount);
    for (int i = 0; i < bucketCount; i++) {
      Map<String, Object> point = new LinkedHashMap<>();
      point.put("bucket", firstBucket + (long) i * bucketMs);
      point.put("events", evCounts[i]);
      point.put("flagged", flCounts[i]);
      point.put("alerts", alCounts[i]);
      timeseries.add(point);
    }

    // --- top lists ---
    List<Map<String, Object>> topUsers = topUsers(events, alerts);
    List<Map<String, Object>> topRules = topRules(events);
    List<Map<String, Object>> topColumns = topColumns(events);

    List<Map<String, Object>> recentAlerts = new ArrayList<>();
    alerts.stream()
        .sorted(Comparator.comparingLong(Alert::createdAt).reversed())
        .limit(8)
        .forEach(alert -> recentAlerts.add(alertWire(alert)));

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("generatedAt", now);
    out.put("windowHours", windowHours);
    out.put("bucketMinutes", bucketMinutes);
    out.put("events", eventsBlock);
    out.put("alerts", alertsBlock);
    out.put("timeseries", timeseries);
    out.put("topUsers", topUsers);
    out.put("topRules", topRules);
    out.put("topSensitiveColumns", topColumns);
    out.put("recentAlerts", recentAlerts);
    return out;
  }

  private static int bucketIndex(long ts, long firstBucket, int bucketMs, int bucketCount) {
    int idx = (int) ((ts - firstBucket) / bucketMs);
    return idx >= 0 && idx < bucketCount ? idx : -1;
  }

  private List<Map<String, Object>> topUsers(List<RiskEvent> events, List<Alert> alerts) {
    Map<String, long[]> byUser = new HashMap<>();   // [events, flagged, scoreSum]
    Map<String, Long> openAlerts = new HashMap<>();
    for (Alert alert : alerts) {
      if (!Alert.STATUS_RESOLVED.equals(alert.status())) {
        openAlerts.merge(alert.user(), 1L, Long::sum);
      }
    }
    for (RiskEvent event : events) {
      String user = event.user() == null ? "(unknown)" : event.user();
      long[] agg = byUser.computeIfAbsent(user, k -> new long[3]);
      agg[0]++;
      if (event.flagged()) {
        agg[1]++;
      }
      agg[2] += event.riskScore();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    byUser.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue()[2], a.getValue()[2]))
        .limit(8)
        .forEach(entry -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("user", entry.getKey());
          row.put("events", entry.getValue()[0]);
          row.put("flagged", entry.getValue()[1]);
          row.put("riskScore", entry.getValue()[2]);
          row.put("openAlerts", openAlerts.getOrDefault(entry.getKey(), 0L));
          out.add(row);
        });
    return out;
  }

  private List<Map<String, Object>> topRules(List<RiskEvent> events) {
    Map<String, long[]> byRule = new HashMap<>();   // [hits, maxWeight]
    Map<String, RuleHit> sample = new HashMap<>();
    for (RiskEvent event : events) {
      for (RuleHit hit : event.hits()) {
        long[] agg = byRule.computeIfAbsent(hit.ruleId(), k -> new long[2]);
        agg[0]++;
        agg[1] = Math.max(agg[1], hit.severity().weight());
        sample.putIfAbsent(hit.ruleId(), hit);
      }
    }
    List<Map<String, Object>> out = new ArrayList<>();
    byRule.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
        .limit(10)
        .forEach(entry -> {
          RuleHit hit = sample.get(entry.getKey());
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("ruleId", entry.getKey());
          row.put("ruleName", hit.ruleName());
          row.put("category", hit.category());
          row.put("severity", hit.severity().wire());
          row.put("hits", entry.getValue()[0]);
          out.add(row);
        });
    return out;
  }

  private List<Map<String, Object>> topColumns(List<RiskEvent> events) {
    Map<String, Long> counts = new HashMap<>();
    Map<String, Set<String>> users = new HashMap<>();
    Map<String, SensitiveColumn> meta = new HashMap<>();
    for (RiskEvent event : events) {
      for (SensitiveColumn column : event.sensitiveColumns()) {
        counts.merge(column.columnKey(), 1L, Long::sum);
        users.computeIfAbsent(column.columnKey(), k -> new HashSet<>())
            .add(event.user() == null ? "(unknown)" : event.user());
        meta.putIfAbsent(column.columnKey(), column);
      }
    }
    List<Map<String, Object>> out = new ArrayList<>();
    counts.entrySet().stream()
        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
        .limit(10)
        .forEach(entry -> {
          SensitiveColumn column = meta.get(entry.getKey());
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("columnKey", entry.getKey());
          row.put("sensitivity", column.sensitivity().wire());
          row.put("category", column.category());
          row.put("accesses", entry.getValue());
          row.put("distinctUsers", users.get(entry.getKey()).size());
          out.add(row);
        });
    return out;
  }

  /** Wire shape for one alert (shared with AlertController). */
  public static Map<String, Object> alertWire(Alert alert) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", alert.id());
    map.put("ruleId", alert.ruleId());
    map.put("ruleName", alert.ruleName());
    map.put("category", alert.category());
    map.put("severity", alert.severity().wire());
    map.put("user", alert.user());
    map.put("sourceIp", alert.sourceIp());
    map.put("title", alert.title());
    map.put("description", alert.description());
    map.put("sqlSnippet", alert.sqlSnippet());
    map.put("status", alert.status());
    map.put("ackNote", alert.ackNote());
    map.put("eventCount", alert.eventCount());
    map.put("eventIds", alert.eventIds());
    map.put("createdAt", alert.createdAt());
    map.put("updatedAt", alert.updatedAt());
    map.put("lastHitAt", alert.lastHitAt());
    return map;
  }

  /** Wire shape for one event (shared by events + alert detail). */
  public static Map<String, Object> eventWire(RiskEvent event) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", event.id());
    map.put("timestamp", event.timestamp().toString());
    map.put("service", event.service());
    map.put("eventType", event.eventType());
    map.put("outcome", event.outcome());
    map.put("user", event.user());
    map.put("sourceIp", event.sourceIp());
    map.put("dialect", event.dialect());
    map.put("masked", event.masked());
    map.put("rowFiltered", event.rowFiltered());
    map.put("instance", event.instance());
    map.put("errorCode", event.errorCode());
    map.put("errorMessage", event.errorMessage());
    map.put("rowCount", event.rowCount());
    map.put("sql", event.originalSql());
    map.put("rewrittenSql", event.rewrittenSql());
    map.put("sqlTruncated", event.sqlTruncated());
    map.put("tables", event.matchedTables());
    List<String> columns = new ArrayList<>();
    event.sensitiveColumns().forEach(c -> columns.add(c.columnKey()));
    map.put("sensitiveColumns", columns);
    List<Map<String, Object>> hits = new ArrayList<>();
    for (RuleHit hit : event.hits()) {
      Map<String, Object> h = new LinkedHashMap<>();
      h.put("ruleId", hit.ruleId());
      h.put("ruleName", hit.ruleName());
      h.put("severity", hit.severity().wire());
      h.put("evidence", hit.evidence());
      hits.add(h);
    }
    map.put("hits", hits);
    map.put("riskScore", event.riskScore());
    map.put("topSeverity", event.topSeverity().wire());
    return map;
  }

  private static double round(double v) {
    return Math.round(v * 10.0) / 10.0;
  }
}
