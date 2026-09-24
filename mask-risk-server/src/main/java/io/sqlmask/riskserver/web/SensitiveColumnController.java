package io.sqlmask.riskserver.web;

import io.sqlmask.riskserver.model.RiskSeverity;
import io.sqlmask.riskserver.model.SensitiveColumn;
import io.sqlmask.riskserver.store.MemoryRiskStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sensitive-asset registry CRUD: which columns count as sensitive, how
 * sensitive, and in which category - the asset context every behavior rule
 * scores against.
 */
@RestController
@RequestMapping("/api/risk/sensitive-columns")
public class SensitiveColumnController {

  private final MemoryRiskStore store;

  public SensitiveColumnController(MemoryRiskStore store) {
    this.store = store;
  }

  @GetMapping
  public Map<String, Object> list(@RequestParam(required = false) String keyword) {
    long now = System.currentTimeMillis() - 24 * 3_600_000L;
    Map<String, long[]> access = new HashMap<>();   // [accesses, distinctUserHashless]
    Map<String, List<String>> users = new HashMap<>();
    for (var event : store.eventsSince(now)) {
      for (SensitiveColumn column : event.sensitiveColumns()) {
        access.computeIfAbsent(column.columnKey(), k -> new long[1])[0]++;
        users.computeIfAbsent(column.columnKey(), k -> new ArrayList<>());
        String user = event.user() == null ? "(unknown)" : event.user();
        if (!users.get(column.columnKey()).contains(user)) {
          users.get(column.columnKey()).add(user);
        }
      }
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (SensitiveColumn column : store.snapshotSensitiveColumns()) {
      if (keyword != null && !keyword.isBlank()
          && !column.columnKey().toLowerCase().contains(keyword.toLowerCase())) {
        continue;
      }
      Map<String, Object> row = new HashMap<>();
      row.put("columnKey", column.columnKey());
      row.put("sensitivity", column.sensitivity().wire());
      row.put("category", column.category());
      row.put("enabled", column.enabled());
      row.put("source", column.source());
      row.put("accesses24h", access.getOrDefault(column.columnKey(), new long[1])[0]);
      row.put("users24h", users.getOrDefault(column.columnKey(), List.of()).size());
      rows.add(row);
    }
    rows.sort((a, b) -> Long.compare((long) b.get("accesses24h"), (long) a.get("accesses24h")));
    return Map.of("total", rows.size(), "columns", rows);
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody ColumnRequest request) {
    if (request.columnKey() == null || request.columnKey().isBlank()) {
      throw new IllegalArgumentException("columnKey is required (catalog.schema.table.column)");
    }
    String key = request.columnKey().trim().toLowerCase();
    if (store.snapshotSensitiveColumns().stream()
        .anyMatch(c -> c.columnKey().equals(key))) {
      throw new IllegalArgumentException("column already registered: " + key);
    }
    SensitiveColumn column = new SensitiveColumn(key,
        RiskSeverity.parse(request.sensitivity()), request.category(),
        request.enabled() == null || request.enabled(), "MANUAL");
    store.putSensitiveColumn(column);
    return wire(column);
  }

  @PutMapping("/{key}")
  public Map<String, Object> update(@org.springframework.web.bind.annotation.PathVariable("key")
      String key, @RequestBody ColumnRequest request) {
    String normalized = normalizePath(key);
    SensitiveColumn existing = store.snapshotSensitiveColumns().stream()
        .filter(c -> c.columnKey().equals(normalized))
        .findFirst()
        .orElseThrow(() -> new java.util.NoSuchElementException("column not found: " + normalized));
    SensitiveColumn updated = new SensitiveColumn(existing.columnKey(),
        request.sensitivity() == null || request.sensitivity().isBlank()
            ? existing.sensitivity() : RiskSeverity.parse(request.sensitivity()),
        request.category() == null || request.category().isBlank()
            ? existing.category() : request.category(),
        request.enabled() == null ? existing.enabled() : request.enabled(),
        existing.source());
    store.putSensitiveColumn(updated);
    return wire(updated);
  }

  @DeleteMapping("/{key}")
  public Map<String, Object> delete(@org.springframework.web.bind.annotation.PathVariable("key")
      String key) {
    String normalized = normalizePath(key);
    if (store.snapshotSensitiveColumns().stream()
        .noneMatch(c -> c.columnKey().equals(normalized))) {
      throw new java.util.NoSuchElementException("column not found: " + normalized);
    }
    store.removeSensitiveColumn(normalized);
    return Map.of("deleted", normalized);
  }

  /** Path variables keep their dots (catalog.schema.table.column). */
  private static String normalizePath(String key) {
    return key == null ? "" : key.trim().toLowerCase();
  }

  private static Map<String, Object> wire(SensitiveColumn column) {
    return Map.of(
        "columnKey", column.columnKey(),
        "sensitivity", column.sensitivity().wire(),
        "category", column.category(),
        "enabled", column.enabled(),
        "source", column.source());
  }

  public record ColumnRequest(String columnKey, String sensitivity, String category,
      Boolean enabled) {
  }
}
