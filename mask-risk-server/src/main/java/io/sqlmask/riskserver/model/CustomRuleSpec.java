package io.sqlmask.riskserver.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Structured condition DSL for custom rules: an AND-combined list of field
 * predicates, plus an optional sliding-window threshold ("N matching events in
 * T seconds grouped by user or ip"). Kept deliberately tiny - the console
 * renders it as visual rows, no free-form expression parsing.
 */
public record CustomRuleSpec(List<Condition> conditions, Window window) {

  /** One field predicate. */
  public record Condition(String field, String op, String value) {
  }

  /** Optional threshold window; absent means single-event evaluation. */
  public record Window(int seconds, int count, String groupBy) {
  }

  /** JSON wire shape used by the API (value kept as string for regex/in lists). */
  public record ConditionSpec(String field, String op, String value) {
  }

  public record WindowSpec(Integer seconds, Integer count, String groupBy) {
  }

  private static final List<String> FIELDS = List.of(
      "user", "ip", "eventType", "outcome", "dialect", "masked", "rowFiltered",
      "sql", "errorCode", "rowCount", "hour", "instance", "table", "column");

  private static final List<String> OPS = List.of(
      "eq", "neq", "contains", "not_contains", "regex", "in", "not_in",
      "gt", "lt", "startswith", "endswith");

  public CustomRuleSpec {
    if (conditions == null || conditions.isEmpty()) {
      throw new IllegalArgumentException("custom rule requires at least one condition");
    }
    conditions = List.copyOf(conditions);
  }

  /** Validates and compiles a spec from the wire shape. */
  public static CustomRuleSpec of(List<ConditionSpec> conditions, WindowSpec window) {
    if (conditions == null || conditions.isEmpty()) {
      throw new IllegalArgumentException("custom rule requires at least one condition");
    }
    List<Condition> compiled = new ArrayList<>();
    for (ConditionSpec spec : conditions) {
      if (spec == null || isBlank(spec.field()) || isBlank(spec.op())) {
        throw new IllegalArgumentException("each condition needs field and op");
      }
      String field = spec.field().trim();
      String op = spec.op().trim();
      if (!FIELDS.contains(field)) {
        throw new IllegalArgumentException("unsupported field: " + field
            + " (allowed: " + String.join(", ", FIELDS) + ")");
      }
      if (!OPS.contains(op)) {
        throw new IllegalArgumentException("unsupported op: " + op
            + " (allowed: " + String.join(", ", OPS) + ")");
      }
      if (isBlank(spec.value()) && !"eq".equals(op) && !"neq".equals(op)) {
        throw new IllegalArgumentException("condition value is required for op " + op);
      }
      if ("regex".equals(op)) {
        try {
          Pattern.compile(spec.value());
        } catch (PatternSyntaxException e) {
          throw new IllegalArgumentException("invalid regex: " + e.getMessage());
        }
      }
      compiled.add(new Condition(field, op, spec.value()));
    }
    Window compiledWindow = null;
    if (window != null && window.seconds() != null && window.count() != null) {
      if (window.seconds() <= 0 || window.count() <= 0) {
        throw new IllegalArgumentException("window seconds and count must be positive");
      }
      String groupBy = window.groupBy() == null || window.groupBy().isBlank()
          ? "user" : window.groupBy();
      if (!groupBy.equals("user") && !groupBy.equals("ip")) {
        throw new IllegalArgumentException("window groupBy must be user or ip");
      }
      compiledWindow = new Window(window.seconds(), window.count(), groupBy);
    }
    return new CustomRuleSpec(compiled, compiledWindow);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /** Allowed field list for API docs / console selects. */
  public static List<String> fields() {
    return FIELDS;
  }

  /** Allowed operator list for API docs / console selects. */
  public static List<String> ops() {
    return OPS;
  }

  /** Wire shape for API responses. */
  public Map<String, Object> toWire() {
    List<Map<String, String>> cs = conditions.stream()
        .map(c -> Map.<String, String>of("field", c.field(), "op", c.op(), "value", c.value()))
        .toList();
    if (window == null) {
      return Map.of("conditions", cs);
    }
    return Map.of("conditions", cs, "window",
        Map.of("seconds", window.seconds(), "count", window.count(),
            "groupBy", window.groupBy()));
  }
}
