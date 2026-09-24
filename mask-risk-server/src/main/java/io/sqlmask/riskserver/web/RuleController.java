package io.sqlmask.riskserver.web;

import io.sqlmask.riskserver.engine.RiskEngine;
import io.sqlmask.riskserver.model.CustomRuleSpec;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RiskSeverity;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Detection-rule management: list (with hit counts), built-in parameter
 * editing / toggling, custom-rule CRUD with the condition DSL, and dry-run
 * rule testing against recent events.
 */
@RestController
@RequestMapping("/api/risk/rules")
public class RuleController {

  private final RiskEngine engine;
  private final MemoryRiskStore store;

  public RuleController(RiskEngine engine, MemoryRiskStore store) {
    this.engine = engine;
    this.store = store;
  }

  @GetMapping
  public List<Map<String, Object>> rules() {
    Map<String, Long> hitCounts = new HashMap<>();
    for (var event : store.snapshotEvents()) {
      for (var hit : event.hits()) {
        hitCounts.merge(hit.ruleId(), 1L, Long::sum);
      }
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (RiskRule rule : store.snapshotRules()) {
      out.add(rule.summary(hitCounts.getOrDefault(rule.id(), 0L)));
    }
    return out;
  }

  /** Builder metadata: allowed fields and operators for the condition DSL. */
  @GetMapping("/meta")
  public Map<String, Object> meta() {
    return Map.of(
        "fields", CustomRuleSpec.fields(),
        "ops", CustomRuleSpec.ops(),
        "severities", List.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL"));
  }

  /** Creates a custom rule from the console's rule builder. */
  @PostMapping
  public Map<String, Object> create(@RequestBody RiskRule.UpsertRequest request) {
    requireName(request);
    CustomRuleSpec spec = CustomRuleSpec.of(request.conditions(), request.window());
    String now = Instant.now().toString();
    String id = "CUSTOM-" + UUID.randomUUID().toString().substring(0, 8);
    RiskRule rule = new RiskRule(id, request.name().trim(),
        orDefault(request.description(), "自定义规则"), RiskRule.KIND_CUSTOM,
        orDefault(request.category(), "CUSTOM"),
        RiskSeverity.parse(request.severity()), request.enabled() || true,
        request.params(), spec, now, now);
    store.putRule(rule);
    return wired(rule, 0L);
  }

  /**
   * Updates a rule. Built-ins allow toggling/severity/params (identity is
   * fixed); custom rules can be fully rewritten.
   */
  @PutMapping("/{id}")
  public Map<String, Object> update(@PathVariable String id,
      @RequestBody RiskRule.UpsertRequest request) {
    RiskRule existing = store.findRule(id)
        .orElseThrow(() -> new java.util.NoSuchElementException("rule not found: " + id));
    RiskRule updated;
    if (RiskRule.KIND_CUSTOM.equals(existing.kind())) {
      requireName(request);
      CustomRuleSpec spec = CustomRuleSpec.of(request.conditions(), request.window());
      updated = new RiskRule(existing.id(), request.name().trim(),
          orDefault(request.description(), existing.description()), RiskRule.KIND_CUSTOM,
          orDefault(request.category(), existing.category()),
          RiskSeverity.parse(request.severity()), request.enabled(),
          request.params() == null ? existing.params() : request.params(), spec,
          existing.createdAt(), Instant.now().toString());
    } else {
      updated = new RiskRule(existing.id(), existing.name(), existing.description(),
          existing.kind(), existing.category(),
          request.severity() == null || request.severity().isBlank()
              ? existing.severity() : RiskSeverity.parse(request.severity()),
          request.enabled(),
          request.params() == null ? existing.params() : request.params(),
          null, existing.createdAt(), Instant.now().toString());
    }
    store.putRule(updated);
    return wired(updated, 0L);
  }

  @PutMapping("/{id}/enabled")
  public Map<String, Object> toggle(@PathVariable String id,
      @RequestParam boolean enabled) {
    RiskRule existing = store.findRule(id)
        .orElseThrow(() -> new java.util.NoSuchElementException("rule not found: " + id));
    RiskRule updated = new RiskRule(existing.id(), existing.name(), existing.description(),
        existing.kind(), existing.category(), existing.severity(), enabled,
        existing.params(), existing.spec(), existing.createdAt(), Instant.now().toString());
    store.putRule(updated);
    return wired(updated, 0L);
  }

  @DeleteMapping("/{id}")
  public Map<String, Object> delete(@PathVariable String id) {
    RiskRule existing = store.findRule(id)
        .orElseThrow(() -> new java.util.NoSuchElementException("rule not found: " + id));
    if (!RiskRule.KIND_CUSTOM.equals(existing.kind())) {
      throw new IllegalArgumentException("built-in rules cannot be deleted, only disabled");
    }
    store.removeRule(id);
    return Map.of("deleted", id);
  }

  /** Dry-runs a rule draft (existing id or full inline draft) over recent events. */
  @PostMapping("/test")
  public Map<String, Object> test(@RequestBody TestRequest request) {
    RiskRule draft;
    if (request.id() != null && !request.id().isBlank()) {
      RiskRule existing = store.findRule(request.id())
          .orElseThrow(() -> new java.util.NoSuchElementException("rule not found: " + request.id()));
      draft = existing;
    } else {
      if (request.name() == null || request.name().isBlank()) {
        throw new IllegalArgumentException("rule name is required");
      }
      CustomRuleSpec spec = CustomRuleSpec.of(request.conditions(), request.window());
      draft = new RiskRule("DRAFT", request.name().trim(),
          orDefault(request.description(), "draft"), RiskRule.KIND_CUSTOM,
          orDefault(request.category(), "CUSTOM"),
          RiskSeverity.parse(request.severity()), true, null, spec,
          Instant.now().toString(), Instant.now().toString());
    }
    return engine.testRule(draft, Math.max(10, Math.min(5000, request.limit())));
  }

  private static void requireName(RiskRule.UpsertRequest request) {
    if (request == null || request.name() == null || request.name().isBlank()) {
      throw new IllegalArgumentException("rule name is required");
    }
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private Map<String, Object> wired(RiskRule rule, long hits) {
    return rule.summary(hits);
  }

  /** Rule-test request: either an existing rule id or a full custom draft. */
  public record TestRequest(String id, String name, String description, String category,
      String severity, Map<String, Object> params,
      List<CustomRuleSpec.ConditionSpec> conditions, CustomRuleSpec.WindowSpec window,
      Integer limit) {
  }
}
