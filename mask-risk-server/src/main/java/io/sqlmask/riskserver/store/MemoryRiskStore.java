package io.sqlmask.riskserver.store;

import io.sqlmask.riskserver.model.Alert;
import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.SensitiveColumn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * In-memory working set: a rolling event window plus the rule / alert /
 * sensitive-asset registries. All mutating access synchronizes on this object
 * (single-process demo scale, tens of thousands of events); the interface
 * mirrors what an ES/PG-backed store would eventually implement.
 */
public class MemoryRiskStore {

  private static final int HIT_IDS_CAP = 50;

  private final int maxEvents;
  private final List<RiskEvent> events = new ArrayList<>();
  private final Map<String, RiskRule> rules = new LinkedHashMap<>();
  private final Map<String, Alert> alerts = new LinkedHashMap<>();
  private final Map<String, SensitiveColumn> sensitiveColumns = new LinkedHashMap<>();
  /** Baseline: columnKey -> users that have already touched it. */
  private final Map<String, java.util.Set<String>> subjectBaseline = new LinkedHashMap<>();

  private final AtomicLong eventSeq = new AtomicLong();
  private final AtomicLong alertSeq = new AtomicLong();

  public MemoryRiskStore(int maxEvents) {
    this.maxEvents = Math.max(1_000, maxEvents);
  }

  public synchronized String nextEventId() {
    return "evt-" + Long.toHexString(System.currentTimeMillis()) + "-" + eventSeq.incrementAndGet();
  }

  public synchronized String nextAlertId() {
    return "alt-" + alertSeq.incrementAndGet();
  }

  /** Restores sequence floors so ids generated after a reload never collide. */
  public synchronized void advanceSeqs(long minEventSeq, long minAlertSeq) {
    eventSeq.accumulateAndGet(minEventSeq, Math::max);
    alertSeq.accumulateAndGet(minAlertSeq, Math::max);
  }

  public synchronized long eventSeqValue() {
    return eventSeq.get();
  }

  public synchronized long alertSeqValue() {
    return alertSeq.get();
  }

  /** Deep copy of the first-access baseline (for persistence). */
  public synchronized Map<String, java.util.Set<String>> baselineSnapshot() {
    Map<String, java.util.Set<String>> copy = new LinkedHashMap<>();
    subjectBaseline.forEach((key, users) -> copy.put(key, new java.util.HashSet<>(users)));
    return copy;
  }

  public synchronized void appendEvent(RiskEvent event) {
    events.add(event);
    if (events.size() > maxEvents) {
      events.subList(0, events.size() - maxEvents).clear();
    }
  }

  /** Replaces the stored event (same id) with the enriched/scored version. */
  public synchronized void updateEvent(RiskEvent event) {
    for (int i = events.size() - 1; i >= 0; i--) {
      if (events.get(i).id().equals(event.id())) {
        events.set(i, event);
        return;
      }
    }
    events.add(event);
  }

  public synchronized List<RiskEvent> snapshotEvents() {
    return new ArrayList<>(events);
  }

  /** Events whose timestamp is at or after {@code since} (oldest first). */
  public synchronized List<RiskEvent> eventsSince(long sinceEpochMs) {
    List<RiskEvent> out = new ArrayList<>();
    for (RiskEvent event : events) {
      if (event.timestamp().toEpochMilli() >= sinceEpochMs) {
        out.add(event);
      }
    }
    return out;
  }

  public synchronized long countEventsSince(long sinceEpochMs, Predicate<RiskEvent> filter) {
    long count = 0;
    for (RiskEvent event : events) {
      if (event.timestamp().toEpochMilli() >= sinceEpochMs && filter.test(event)) {
        count++;
      }
    }
    return count;
  }

  public synchronized Optional<RiskEvent> findEvent(String id) {
    return events.stream().filter(e -> e.id().equals(id)).findFirst();
  }

  // ---- rules ----

  public synchronized void putRule(RiskRule rule) {
    rules.put(rule.id(), rule);
  }

  public synchronized void removeRule(String id) {
    rules.remove(id);
  }

  public synchronized Optional<RiskRule> findRule(String id) {
    return Optional.ofNullable(rules.get(id));
  }

  public synchronized List<RiskRule> snapshotRules() {
    return new ArrayList<>(rules.values());
  }

  // ---- alerts ----

  public synchronized void putAlert(Alert alert) {
    alerts.put(alert.id(), alert);
  }

  public synchronized Optional<Alert> findAlert(String id) {
    return Optional.ofNullable(alerts.get(id));
  }

  public synchronized List<Alert> snapshotAlerts() {
    return new ArrayList<>(alerts.values());
  }

  /**
   * The open-or-acknowledged alert for one rule × subject whose last hit is
   * inside the cooldown window - the dedup target for a new hit.
   */
  public synchronized Optional<Alert> findActiveGroupedAlert(String ruleId, String user,
      long cooldownMs, long now) {
    Alert best = null;
    for (Alert alert : alerts.values()) {
      if (!alert.ruleId().equals(ruleId) || !alert.user().equals(user)) {
        continue;
      }
      if (Alert.STATUS_RESOLVED.equals(alert.status())) {
        continue;
      }
      if (now - alert.lastHitAt() > cooldownMs) {
        continue;
      }
      if (best == null || alert.lastHitAt() > best.lastHitAt()) {
        best = alert;
      }
    }
    return Optional.ofNullable(best);
  }

  // ---- sensitive assets ----

  public synchronized void putSensitiveColumn(SensitiveColumn column) {
    sensitiveColumns.put(column.columnKey(), column);
  }

  public synchronized void removeSensitiveColumn(String columnKey) {
    sensitiveColumns.remove(columnKey);
  }

  public synchronized List<SensitiveColumn> snapshotSensitiveColumns() {
    return new ArrayList<>(sensitiveColumns.values());
  }

  // ---- first-access baseline ----

  /**
   * Registers {@code user} against {@code columnKey}; returns true when the
   * user had already been seen (i.e. false means this is a first access).
   */
  public synchronized boolean registerBaseline(String columnKey, String user) {
    return !subjectBaseline
        .computeIfAbsent(columnKey, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
        .add(user);
  }

  /** Read-only baseline check (rule dry-runs). */
  public synchronized boolean baselineSeen(String columnKey, String user) {
    java.util.Set<String> seen = subjectBaseline.get(columnKey);
    return seen != null && seen.contains(user);
  }

  public synchronized void clearBaseline() {
    subjectBaseline.clear();
  }

  // ---- reset ----

  public synchronized void clearAll() {
    events.clear();
    alerts.clear();
    subjectBaseline.clear();
    eventSeq.set(0);
    alertSeq.set(0);
  }
}
