package io.sqlmask.riskserver.web;

import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskSeverity;
import io.sqlmask.riskserver.stats.StatsCalculator;
import io.sqlmask.riskserver.store.MemoryRiskStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Query-at-risk events: filters over the rolling window, newest first. */
@RestController
@RequestMapping("/api/risk/events")
public class RiskEventController {

  private final MemoryRiskStore store;

  public RiskEventController(MemoryRiskStore store) {
    this.store = store;
  }

  @GetMapping
  public Map<String, Object> events(
      @RequestParam(required = false) String user,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String ruleId,
      @RequestParam(required = false) String eventType,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1440") int minutes,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    int pageSize = Math.max(1, Math.min(200, size));
    long since = System.currentTimeMillis() - minutes * 60_000L;
    RiskSeverity severityFilter = severity == null || severity.isBlank()
        ? null : RiskSeverity.parse(severity);

    List<RiskEvent> matched = new ArrayList<>();
    for (RiskEvent event : store.eventsSince(since)) {
      if (user != null && !user.isBlank() && !equalsIgnoreCase(event.user(), user)) {
        continue;
      }
      if (eventType != null && !eventType.isBlank()
          && !eventType.equalsIgnoreCase(event.eventType())) {
        continue;
      }
      if (outcome != null && !outcome.isBlank()
          && !outcome.equalsIgnoreCase(event.outcome())) {
        continue;
      }
      if (severityFilter != null && event.topSeverity() != severityFilter) {
        continue;
      }
      if (ruleId != null && !ruleId.isBlank()
          && event.hits().stream().noneMatch(h -> h.ruleId().equals(ruleId))) {
        continue;
      }
      if (keyword != null && !keyword.isBlank() && !containsKeyword(event, keyword)) {
        continue;
      }
      matched.add(event);
    }
    matched.sort(Comparator.comparing(RiskEvent::timestamp).reversed());

    int from = Math.min(page * pageSize, matched.size());
    int to = Math.min(from + pageSize, matched.size());
    List<Map<String, Object>> rows = new ArrayList<>();
    for (RiskEvent event : matched.subList(from, to)) {
      rows.add(StatsCalculator.eventWire(event));
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("total", matched.size());
    out.put("page", page);
    out.put("size", pageSize);
    out.put("events", rows);
    return out;
  }

  @GetMapping("/{id}")
  public Map<String, Object> event(@org.springframework.web.bind.annotation.PathVariable String id) {
    return store.findEvent(id)
        .map(StatsCalculator::eventWire)
        .orElseThrow(() -> new java.util.NoSuchElementException("event not found: " + id));
  }

  private static boolean equalsIgnoreCase(String actual, String expected) {
    return actual != null && actual.toLowerCase(Locale.ROOT)
        .contains(expected.toLowerCase(Locale.ROOT));
  }

  private static boolean containsKeyword(RiskEvent event, String keyword) {
    String k = keyword.toLowerCase(Locale.ROOT);
    if (event.originalSql() != null && event.originalSql().toLowerCase(Locale.ROOT).contains(k)) {
      return true;
    }
    if (event.user() != null && event.user().toLowerCase(Locale.ROOT).contains(k)) {
      return true;
    }
    if (event.sourceIp() != null && event.sourceIp().toLowerCase(Locale.ROOT).contains(k)) {
      return true;
    }
    if (event.errorCode() != null && event.errorCode().toLowerCase(Locale.ROOT).contains(k)) {
      return true;
    }
    for (var hit : event.hits()) {
      if (hit.ruleName().toLowerCase(Locale.ROOT).contains(k)) {
        return true;
      }
    }
    return false;
  }
}
