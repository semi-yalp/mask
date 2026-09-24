package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.CustomRuleSpec;
import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RuleHit;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Evaluates user-defined rules: AND-combined field predicates, optionally
 * raised to a sliding-window threshold ("N matching events in T seconds,
 * grouped by user or ip"). Field access is computed per event; regex and list
 * operators are compiled once per evaluation from the spec.
 */
public final class CustomConditionRule implements RuleEvaluator {

  @Override
  public List<RuleHit> evaluate(RiskRule rule, RiskEvent event, RuleContext ctx) {
    List<RuleHit> hits = new ArrayList<>();
    CustomRuleSpec spec = rule.spec();
    if (spec == null || spec.conditions().isEmpty()) {
      return hits;
    }
    if (!matches(spec.conditions(), event)) {
      return hits;
    }
    CustomRuleSpec.Window window = spec.window();
    if (window == null) {
      hits.add(new RuleHit(rule.id(), rule.name(), rule.category(), rule.severity(),
          describeConditions(spec)));
      return hits;
    }
    String groupValue = "ip".equals(window.groupBy())
        ? nvl(event.sourceIp())
        : nvl(event.user());
    long count = ctx.countEvents(
        event.timestamp().minusSeconds(window.seconds()),
        e -> groupValue.equals("ip".equals(window.groupBy())
            ? nvl(e.sourceIp()) : nvl(e.user()))
            && matches(spec.conditions(), e));
    if (count >= window.count() && (count == window.count() || count % window.count() == 0)) {
      hits.add(new RuleHit(rule.id(), rule.name(), rule.category(), rule.severity(),
          String.format("%s（%ds 窗口内第 %d 次命中，阈值 %d，按%s分组）",
              describeConditions(spec), window.seconds(), count, window.count(),
              window.groupBy().equals("ip") ? "IP" : "用户")));
    }
    return hits;
  }

  private static String nvl(String s) {
    return s == null ? "" : s;
  }

  private String describeConditions(CustomRuleSpec spec) {
    StringBuilder sb = new StringBuilder("自定义规则命中：");
    for (CustomRuleSpec.Condition condition : spec.conditions()) {
      sb.append(condition.field()).append(' ').append(condition.op()).append(' ')
          .append(condition.value()).append(" 且 ");
    }
    sb.setLength(sb.length() - 2);
    return sb.toString();
  }

  /** @return true when every condition matches the event. */
  public boolean matches(List<CustomRuleSpec.Condition> conditions, RiskEvent event) {
    for (CustomRuleSpec.Condition condition : conditions) {
      if (!one(condition, event)) {
        return false;
      }
    }
    return true;
  }

  private boolean one(CustomRuleSpec.Condition c, RiskEvent e) {
    String field = c.field();
    String op = c.op();
    String expected = c.value() == null ? "" : c.value().trim();

    // Numeric fields compare numerically for gt/lt, by equality otherwise.
    if ("rowCount".equals(field)) {
      long actual = e.rowCount() == null ? -1L : e.rowCount();
      long target = parseLong(expected, Long.MIN_VALUE);
      return switch (op) {
        case "gt" -> actual > target;
        case "lt" -> actual < target && e.rowCount() != null;
        case "eq" -> e.rowCount() != null && actual == target;
        case "neq" -> e.rowCount() == null || actual != target;
        default -> false;
      };
    }
    if ("hour".equals(field)) {
      int hour = e.timestamp().atZone(ZonedDateTime.now().getZone()).getHour();
      long target = parseLong(expected, -1);
      return switch (op) {
        case "eq" -> hour == target;
        case "neq" -> hour != target;
        case "gt" -> hour > target;
        case "lt" -> hour < target;
        default -> false;
      };
    }
    if ("masked".equals(field) || "rowFiltered".equals(field)) {
      boolean actual = "masked".equals(field)
          ? Boolean.TRUE.equals(e.masked())
          : Boolean.TRUE.equals(e.rowFiltered());
      boolean target = Boolean.parseBoolean(expected);
      return switch (op) {
        case "eq" -> actual == target;
        case "neq" -> actual != target;
        default -> false;
      };
    }

    String actual = resolve(field, e);
    return switch (op) {
      case "eq" -> equalsSafe(actual, expected);
      case "neq" -> !equalsSafe(actual, expected);
      case "contains" -> actual != null && actual.toLowerCase(Locale.ROOT)
          .contains(expected.toLowerCase(Locale.ROOT));
      case "not_contains" -> actual == null || !actual.toLowerCase(Locale.ROOT)
          .contains(expected.toLowerCase(Locale.ROOT));
      case "startswith" -> actual != null && actual.toLowerCase(Locale.ROOT)
          .startsWith(expected.toLowerCase(Locale.ROOT));
      case "endswith" -> actual != null && actual.toLowerCase(Locale.ROOT)
          .endsWith(expected.toLowerCase(Locale.ROOT));
      case "regex" -> actual != null
          && Pattern.compile(expected, Pattern.CASE_INSENSITIVE).matcher(actual).find();
      case "in" -> inList(actual, expected, false);
      case "not_in" -> inList(actual, expected, true);
      default -> false;
    };
  }

  private String resolve(String field, RiskEvent e) {
    return switch (field) {
      case "user" -> e.user();
      case "ip" -> e.sourceIp();
      case "eventType" -> e.eventType();
      case "outcome" -> e.outcome();
      case "dialect" -> e.dialect();
      case "sql" -> e.originalSql();
      case "errorCode" -> e.errorCode();
      case "instance" -> e.instance();
      case "table" -> String.join(",", e.matchedTables());
      case "column" -> {
        List<String> keys = new ArrayList<>();
        e.sensitiveColumns().forEach(c -> keys.add(c.columnKey()));
        yield keys.isEmpty() ? null : String.join(",", keys);
      }
      default -> null;
    };
  }

  private boolean equalsSafe(String actual, String expected) {
    if (actual == null) {
      return expected.isEmpty() || "null".equalsIgnoreCase(expected);
    }
    return actual.equalsIgnoreCase(expected);
  }

  private boolean inList(String actual, String listValue, boolean negate) {
    Set<String> values = new HashSet<>();
    for (String part : listValue.split("[,，]")) {
      if (!part.isBlank()) {
        values.add(part.trim().toLowerCase(Locale.ROOT));
      }
    }
    String key = actual == null ? "" : actual.toLowerCase(Locale.ROOT);
    return negate != values.contains(key);
  }

  private long parseLong(String value, long fallback) {
    try {
      return Long.parseLong(value.trim());
    } catch (Exception e) {
      return fallback;
    }
  }
}
