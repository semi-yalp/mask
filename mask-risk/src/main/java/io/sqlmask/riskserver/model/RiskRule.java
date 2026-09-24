package io.sqlmask.riskserver.model;

import java.util.List;
import java.util.Map;

/**
 * A detection rule. Built-in rules carry an evaluator id (params editable,
 * enable/disable) while custom rules carry a structured condition spec the
 * console builds visually - see {@link CustomRuleSpec}.
 */
public record RiskRule(
    String id,
    String name,
    String description,
    String kind,
    String category,
    RiskSeverity severity,
    boolean enabled,
    Map<String, Object> params,
    CustomRuleSpec spec,
    String createdAt,
    String updatedAt) {

  public static final String KIND_BUILT_IN = "BUILT_IN";
  public static final String KIND_CUSTOM = "CUSTOM";

  public RiskRule {
    if (spec != null) {
      kind = KIND_CUSTOM;
    }
    params = params == null ? Map.of() : params;
  }

  public RiskRule withEnabled(boolean newEnabled) {
    return new RiskRule(id, name, description, kind, category, severity, newEnabled,
        params, spec, createdAt, updatedAt);
  }

  /** Effective integer parameter with fallback. */
  public int paramInt(String key, int fallback) {
    Object value = params.get(key);
    return value instanceof Number n ? n.intValue() : fallback;
  }

  /** Effective string parameter with fallback. */
  public String paramStr(String key, String fallback) {
    Object value = params.get(key);
    return value == null ? fallback : String.valueOf(value);
  }

  /** Snapshot for API responses (null-tolerant: built-ins carry no spec). */
  public Map<String, Object> summary(long hitCount) {
    Map<String, Object> map = new java.util.LinkedHashMap<>();
    map.put("id", id);
    map.put("name", name);
    map.put("description", description);
    map.put("kind", kind);
    map.put("category", category);
    map.put("severity", severity.wire());
    map.put("enabled", enabled);
    map.put("params", params);
    map.put("spec", spec == null ? null : spec.toWire());
    map.put("createdAt", createdAt);
    map.put("updatedAt", updatedAt);
    map.put("hitCount", hitCount);
    return map;
  }

  /** Fields a custom-rule create/update request may set. */
  public record UpsertRequest(String name, String description, String category,
      String severity, boolean enabled, Map<String, Object> params,
      List<CustomRuleSpec.ConditionSpec> conditions, CustomRuleSpec.WindowSpec window) {
  }
}
