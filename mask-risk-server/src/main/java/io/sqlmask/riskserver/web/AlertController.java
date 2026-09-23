package io.sqlmask.riskserver.web;

import io.sqlmask.riskserver.model.Alert;
import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskSeverity;
import io.sqlmask.riskserver.stats.StatsCalculator;
import io.sqlmask.riskserver.store.MemoryRiskStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Alert center: filtered list + detail (with hit events) + status workflow. */
@RestController
@RequestMapping("/api/risk/alerts")
public class AlertController {

  private final MemoryRiskStore store;

  public AlertController(MemoryRiskStore store) {
    this.store = store;
  }

  @GetMapping
  public Map<String, Object> alerts(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String ruleId,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    int pageSize = Math.max(1, Math.min(200, size));
    RiskSeverity severityFilter = severity == null || severity.isBlank()
        ? null : RiskSeverity.parse(severity);

    List<Alert> matched = new ArrayList<>();
    for (Alert alert : store.snapshotAlerts()) {
      if (status != null && !status.isBlank() && !status.equalsIgnoreCase(alert.status())) {
        continue;
      }
      if (severityFilter != null && alert.severity() != severityFilter) {
        continue;
      }
      if (ruleId != null && !ruleId.isBlank() && !alert.ruleId().equals(ruleId)) {
        continue;
      }
      if (keyword != null && !keyword.isBlank() && !contains(alert, keyword)) {
        continue;
      }
      matched.add(alert);
    }
    matched.sort(Comparator.comparingLong(Alert::lastHitAt).reversed());

    int from = Math.min(page * pageSize, matched.size());
    int to = Math.min(from + pageSize, matched.size());
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Alert alert : matched.subList(from, to)) {
      rows.add(StatsCalculator.alertWire(alert));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("total", matched.size());
    out.put("page", page);
    out.put("size", pageSize);
    out.put("alerts", rows);
    return out;
  }

  @GetMapping("/{id}")
  public Map<String, Object> alert(@PathVariable String id) {
    Alert alert = store.findAlert(id)
        .orElseThrow(() -> new java.util.NoSuchElementException("alert not found: " + id));
    Map<String, Object> wire = StatsCalculator.alertWire(alert);
    List<Map<String, Object>> events = new ArrayList<>();
    for (String eventId : alert.eventIds()) {
      store.findEvent(eventId)
          .map(StatsCalculator::eventWire)
          .ifPresent(events::add);
    }
    wire.put("events", events);
    return wire;
  }

  /** Status workflow: ACKNOWLEDGED / RESOLVED (reopen with OPEN). */
  @PutMapping("/{id}/status")
  public Map<String, Object> updateStatus(@PathVariable String id,
      @RequestBody StatusRequest request) {
    Alert alert = store.findAlert(id)
        .orElseThrow(() -> new java.util.NoSuchElementException("alert not found: " + id));
    String status = request.status() == null ? "" : request.status().trim().toUpperCase(Locale.ROOT);
    if (!alert.transition(status, request.note())) {
      throw new IllegalArgumentException(
          "invalid status transition " + alert.status() + " -> " + status
              + " (allowed: OPEN -> ACKNOWLEDGED -> RESOLVED, ACKNOWLEDGED -> OPEN)");
    }
    // transition mutates in place; re-put so persistence layers observe the change
    store.putAlert(alert);
    return StatsCalculator.alertWire(alert);
  }

  private static boolean contains(Alert alert, String keyword) {
    String k = keyword.toLowerCase(Locale.ROOT);
    return (alert.title() != null && alert.title().toLowerCase(Locale.ROOT).contains(k))
        || (alert.user() != null && alert.user().toLowerCase(Locale.ROOT).contains(k))
        || (alert.ruleName() != null && alert.ruleName().toLowerCase(Locale.ROOT).contains(k))
        || (alert.sqlSnippet() != null && alert.sqlSnippet().toLowerCase(Locale.ROOT).contains(k));
  }

  public record StatusRequest(String status, String note) {
  }
}
