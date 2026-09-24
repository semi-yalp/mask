package io.sqlmask.riskserver.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.riskserver.model.Alert;
import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RiskSeverity;
import io.sqlmask.riskserver.model.SensitiveColumn;
import io.sqlmask.riskserver.stats.StatsCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link MemoryRiskStore} decorated with file persistence: every mutation
 * marks the snapshot dirty, a daemon thread flushes at most once every few
 * seconds (single JSON file, atomic rename), and a fresh process restores the
 * full working set - rules (incl. custom + param edits), alerts (incl. status
 * workflow and folded events), sensitive assets, first-access baseline, the
 * newest events, and id sequence floors. Built for single-process demo scale;
 * multi-instance deployments belong on the ES/PG store from the roadmap.
 */
public class PersistingRiskStore extends MemoryRiskStore implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(PersistingRiskStore.class);

  /** On-disk shape; alerts ride in their wire-map form (Alert is mutable). */
  public record SnapshotFile(long version, long savedAt, List<RiskRule> rules,
      List<Map<String, Object>> alerts, List<SensitiveColumn> sensitiveColumns,
      Map<String, List<String>> baseline, List<RiskEvent> events,
      long eventSeq, long alertSeq) {
  }

  private final Path file;
  private final int maxPersistedEvents;
  private final ObjectMapper mapper = new ObjectMapper()
      .findAndRegisterModules()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private final ScheduledExecutorService scheduler;
  private final AtomicBoolean dirty = new AtomicBoolean();
  private boolean closed;

  /** Creates the store and restores any previous snapshot; never throws. */
  public static PersistingRiskStore loadOrCreate(Path file, int maxEvents,
      int maxPersistedEvents) {
    PersistingRiskStore store = new PersistingRiskStore(file, maxEvents, maxPersistedEvents);
    store.restoreIfPresent();
    return store;
  }

  private PersistingRiskStore(Path file, int maxEvents, int maxPersistedEvents) {
    super(maxEvents);
    this.file = file;
    this.maxPersistedEvents = Math.max(1, maxPersistedEvents);
    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "risk-store-persist");
      thread.setDaemon(true);
      return thread;
    });
    scheduler.scheduleWithFixedDelay(this::saveIfDirty, 3, 3, TimeUnit.SECONDS);
  }

  // ---- mutation hooks ----

  @Override
  public synchronized void appendEvent(RiskEvent event) {
    super.appendEvent(event);
    dirty.set(true);
  }

  @Override
  public synchronized void updateEvent(RiskEvent event) {
    super.updateEvent(event);
    dirty.set(true);
  }

  @Override
  public synchronized void putRule(RiskRule rule) {
    super.putRule(rule);
    dirty.set(true);
  }

  @Override
  public synchronized void removeRule(String id) {
    super.removeRule(id);
    dirty.set(true);
  }

  @Override
  public synchronized void putAlert(Alert alert) {
    super.putAlert(alert);
    dirty.set(true);
  }

  @Override
  public synchronized void putSensitiveColumn(SensitiveColumn column) {
    super.putSensitiveColumn(column);
    dirty.set(true);
  }

  @Override
  public synchronized void removeSensitiveColumn(String columnKey) {
    super.removeSensitiveColumn(columnKey);
    dirty.set(true);
  }

  @Override
  public synchronized boolean registerBaseline(String columnKey, String user) {
    boolean seen = super.registerBaseline(columnKey, user);
    dirty.set(true);
    return seen;
  }

  @Override
  public synchronized void clearBaseline() {
    super.clearBaseline();
    dirty.set(true);
  }

  @Override
  public synchronized void clearAll() {
    super.clearAll();
    dirty.set(true);
  }

  // ---- persistence ----

  /** Flushes the snapshot immediately (atomic tmp-file + rename). */
  public synchronized void saveNow() {
    try {
      List<RiskEvent> events = snapshotEvents();
      int from = Math.max(0, events.size() - maxPersistedEvents);
      SnapshotFile snapshot = new SnapshotFile(1, System.currentTimeMillis(),
          snapshotRules(),
          snapshotAlerts().stream().map(StatsCalculator::alertWire).toList(),
          snapshotSensitiveColumns(),
          baselineSnapshot().entrySet().stream()
              .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                  e -> new ArrayList<>(e.getValue()),
                  (a, b) -> a, java.util.LinkedHashMap::new)),
          new ArrayList<>(events.subList(from, events.size())),
          eventSeqValue(), alertSeqValue());
      if (file.getParent() != null) {
        Files.createDirectories(file.getParent());
      }
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      mapper.writeValue(tmp.toFile(), snapshot);
      try {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (Exception e) {
      dirty.set(true);
      throw new IllegalStateException("snapshot save failed: " + e.getMessage(), e);
    }
  }

  private void saveIfDirty() {
    if (closed || !dirty.compareAndSet(true, false)) {
      return;
    }
    try {
      saveNow();
    } catch (RuntimeException e) {
      log.warn("risk: periodic snapshot save failed, will retry: {}", e.getMessage());
    }
  }

  private void restoreIfPresent() {
    if (!Files.exists(file)) {
      log.info("risk: no snapshot at {} - starting fresh", file);
      return;
    }
    try {
      SnapshotFile snapshot = mapper.readValue(file.toFile(), SnapshotFile.class);
      if (snapshot.rules() != null) {
        snapshot.rules().forEach(this::putRule);
      }
      if (snapshot.sensitiveColumns() != null) {
        snapshot.sensitiveColumns().forEach(this::putSensitiveColumn);
      }
      if (snapshot.alerts() != null) {
        snapshot.alerts().stream().map(PersistingRiskStore::alertFrom).forEach(this::putAlert);
      }
      if (snapshot.baseline() != null) {
        snapshot.baseline().forEach((column, users) -> {
          if (users != null) {
            users.forEach(user -> registerBaseline(column, user));
          }
        });
      }
      if (snapshot.events() != null) {
        snapshot.events().forEach(this::appendEvent);
      }
      advanceSeqs(snapshot.eventSeq(), snapshot.alertSeq());
      log.info("risk: restored {} rule(s), {} alert(s), {} event(s), {} asset(s), "
              + "{} baseline column(s) from {}",
          snapshotRules().size(), snapshotAlerts().size(), snapshotEvents().size(),
          snapshotSensitiveColumns().size(), baselineSnapshot().size(), file);
    } catch (Exception e) {
      log.warn("risk: snapshot {} unreadable ({}), starting fresh", file, e.toString());
    }
  }

  /** Final flush; safe to call multiple times. */
  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    scheduler.shutdownNow();
    if (dirty.compareAndSet(true, false)) {
      try {
        saveNow();
        log.info("risk: final snapshot saved to {}", file);
      } catch (RuntimeException e) {
        log.warn("risk: final snapshot save failed: {}", e.getMessage());
      }
    }
  }

  private static Alert alertFrom(Map<String, Object> map) {
    return Alert.restore(
        str(map.get("id")),
        str(map.get("ruleId")),
        str(map.get("ruleName")),
        str(map.get("category")),
        RiskSeverity.parse(str(map.get("severity"))),
        str(map.get("user")),
        str(map.get("sourceIp")),
        str(map.get("title")),
        str(map.get("description")),
        str(map.get("sqlSnippet")),
        num(map.get("createdAt")),
        num(map.get("updatedAt")),
        num(map.get("lastHitAt")),
        str(map.get("status")),
        str(map.get("ackNote")),
        strings(map.get("eventIds")),
        (int) num(map.get("eventCount")));
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static long num(Object value) {
    return value instanceof Number n ? n.longValue() : 0L;
  }

  @SuppressWarnings("unchecked")
  private static List<String> strings(Object value) {
    return value instanceof List ? (List<String>) value : List.of();
  }
}
