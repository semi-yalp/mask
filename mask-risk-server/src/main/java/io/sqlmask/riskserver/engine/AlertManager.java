package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.Alert;
import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskSeverity;
import io.sqlmask.riskserver.model.RuleHit;
import io.sqlmask.riskserver.store.MemoryRiskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns qualifying rule hits into grouped alerts (Alertmanager semantics):
 * hits below {@code minSeverity} only mark the event; qualifying hits fold
 * into an existing open/acknowledged alert for the same rule × user when the
 * last hit is inside the cooldown window, otherwise spawn a new alert.
 */
public class AlertManager {

  private static final Logger log = LoggerFactory.getLogger(AlertManager.class);

  private final MemoryRiskStore store;
  private final RiskSeverity minSeverity;
  private final long cooldownMs;
  private final io.sqlmask.riskserver.notify.NotificationSink notifications;

  public AlertManager(MemoryRiskStore store, RiskSeverity minSeverity, long cooldownMs) {
    this(store, minSeverity, cooldownMs, null);
  }

  public AlertManager(MemoryRiskStore store, RiskSeverity minSeverity, long cooldownMs,
      io.sqlmask.riskserver.notify.NotificationSink notifications) {
    this.store = store;
    this.minSeverity = minSeverity;
    this.cooldownMs = cooldownMs;
    this.notifications = notifications;
  }

  /** @return [created, updated] alert counts for the ingest summary. */
  public int[] process(RiskEvent event, List<RuleHit> hits) {
    int created = 0;
    int updated = 0;
    for (RuleHit hit : hits) {
      if (!hit.severity().atLeast(minSeverity)) {
        continue;
      }
      String user = event.user() == null ? "(unknown)" : event.user();
      long now = event.timestamp().toEpochMilli();
      Optional<Alert> existing =
          store.findActiveGroupedAlert(hit.ruleId(), user, cooldownMs, now);
      if (existing.isPresent()) {
        existing.get().addHit(event.id(), hit.severity(), now);
        // addHit mutates in place; re-put so persistence layers observe the change
        store.putAlert(existing.get());
        updated++;
      } else {
        Alert alert = newAlert(event, hit, user, now);
        store.putAlert(alert);
        created++;
        if (notifications != null) {
          notifications.notify(alert);
        }
      }
    }
    if (created > 0) {
      log.info("risk: {} new alert(s) from event {} (user={}, hits={})",
          created, event.id(), event.user(), hits.size());
    }
    return new int[]{created, updated};
  }

  private Alert newAlert(RiskEvent event, RuleHit hit, String user, long now) {
    String snippet = event.originalSql() == null ? "" : event.originalSql();
    if (snippet.length() > 240) {
      snippet = snippet.substring(0, 240) + "…";
    }
    String title = String.format("[%s] %s — %s", hit.severity().wire().toUpperCase(),
        hit.ruleName(), user);
    String description = String.format(
        "用户 %s（IP %s）触发规则「%s」（类别 %s，级别 %s）。%s。涉及表：%s。",
        user, event.sourceIp() == null ? "-" : event.sourceIp(), hit.ruleName(),
        hit.category(), hit.severity().wire(), hit.evidence(),
        event.matchedTables().isEmpty() ? "-" : String.join(", ", event.matchedTables()));
    return new Alert(store.nextAlertId(), hit.ruleId(), hit.ruleName(), hit.category(),
        hit.severity(), user, event.sourceIp(), title, description, snippet, now, event.id());
  }

  /** Convenience for tests/controllers. */
  public List<Alert> openAlerts() {
    List<Alert> out = new ArrayList<>();
    for (Alert alert : store.snapshotAlerts()) {
      if (!Alert.STATUS_RESOLVED.equals(alert.status())) {
        out.add(alert);
      }
    }
    return out;
  }
}
