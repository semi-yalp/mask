package io.sqlmask.riskserver;

import io.sqlmask.riskserver.model.CustomRuleSpec;
import io.sqlmask.riskserver.stats.StatsCalculator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DSL validation and stats aggregation unit checks. */
class CustomRuleSpecAndStatsTest {

  @Test
  void rejectsUnknownFieldOpAndBadRegex() {
    assertThatThrownBy(() -> CustomRuleSpec.of(
        List.of(new CustomRuleSpec.ConditionSpec("hacker", "eq", "x")), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported field");

    assertThatThrownBy(() -> CustomRuleSpec.of(
        List.of(new CustomRuleSpec.ConditionSpec("sql", "explode", "x")), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported op");

    assertThatThrownBy(() -> CustomRuleSpec.of(
        List.of(new CustomRuleSpec.ConditionSpec("sql", "regex", "(unclosed")), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid regex");

    assertThatThrownBy(() -> CustomRuleSpec.of(List.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one condition");
  }

  @Test
  void windowDefaultsToUserGrouping() {
    CustomRuleSpec spec = CustomRuleSpec.of(
        List.of(new CustomRuleSpec.ConditionSpec("user", "startswith", "svc_")),
        new CustomRuleSpec.WindowSpec(120, 8, null));
    assertThat(spec.window().groupBy()).isEqualTo("user");
    assertThat(spec.window().seconds()).isEqualTo(120);
  }

  @Test
  void sensitiveColumnValidatesAndSplits() {
    var column = new io.sqlmask.riskserver.model.SensitiveColumn(
        "crm.public.customer.phone",
        io.sqlmask.riskserver.model.RiskSeverity.HIGH, "CONTACT", true, "MANUAL");
    assertThat(column.tableName()).isEqualTo("customer");
    assertThat(column.columnName()).isEqualTo("phone");
    assertThatThrownBy(() -> new io.sqlmask.riskserver.model.SensitiveColumn(
        " ", null, null, true, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void severityParseTolerant() {
    assertThat(io.sqlmask.riskserver.model.RiskSeverity.parse("critical"))
        .isEqualTo(io.sqlmask.riskserver.model.RiskSeverity.CRITICAL);
    assertThat(io.sqlmask.riskserver.model.RiskSeverity.parse("bogus"))
        .isEqualTo(io.sqlmask.riskserver.model.RiskSeverity.MEDIUM);
    assertThat(io.sqlmask.riskserver.model.RiskSeverity.CRITICAL.weight()).isEqualTo(40);
  }

  @Test
  void statsOverviewAggregatesWindowsAndBuckets() {
    var store = new io.sqlmask.riskserver.store.MemoryRiskStore(10_000);
    var calculator = new StatsCalculator(store);
    long now = System.currentTimeMillis();
    for (int i = 0; i < 5; i++) {
      var hit = new io.sqlmask.riskserver.model.RuleHit("SQLI_UNION", "UNION 注入探测",
          "SQLI", io.sqlmask.riskserver.model.RiskSeverity.CRITICAL, "evidence");
      var event = io.sqlmask.riskserver.model.RiskEvent.builder()
          .id("e" + i)
          .timestamp(Instant.ofEpochMilli(now - i * 60_000L))
          .eventType("REWRITE").outcome("SUCCESS")
          .user("u" + (i % 2)).sourceIp("10.0.0.1")
          .originalSql("SELECT 1 UNION SELECT 2")
          .hits(List.of(hit)).riskScore(40)
          .build();
      store.appendEvent(event);
    }
    store.putAlert(new io.sqlmask.riskserver.model.Alert("a1", "SQLI_UNION", "UNION 注入探测",
        "SQLI", io.sqlmask.riskserver.model.RiskSeverity.CRITICAL, "u0", "10.0.0.1",
        "t", "d", "s", now, "e0"));

    Map<String, Object> overview = calculator.overview(24, 30);
    assertThat(((Number) ((Map<?, ?>) overview.get("events")).get("total")).longValue()).isEqualTo(5);
    assertThat(((Number) ((Map<?, ?>) overview.get("events")).get("flagged")).longValue()).isEqualTo(5);
    assertThat((List<?>) overview.get("timeseries")).hasSize(48);
    assertThat(((Number) ((Map<?, ?>) overview.get("alerts")).get("open")).longValue()).isEqualTo(1);
    List<?> topRules = (List<?>) overview.get("topRules");
    assertThat(topRules).hasSize(1);
    Map<?, ?> rule = (Map<?, ?>) topRules.get(0);
    assertThat(rule.get("ruleId")).isEqualTo("SQLI_UNION");
    assertThat(((Number) rule.get("hits")).longValue()).isEqualTo(5);
  }

  @Test
  void eventWireFlattensForConsole() {
    var event = io.sqlmask.riskserver.model.RiskEvent.builder()
        .id("e1").timestamp(Instant.ofEpochMilli(1_700_000_000_000L))
        .eventType("REWRITE").outcome("SUCCESS").user("u").sourceIp("1.2.3.4")
        .originalSql("SELECT phone FROM crm.public.customer")
        .sensitiveColumns(List.of(new io.sqlmask.riskserver.model.SensitiveColumn(
            "crm.public.customer.phone",
            io.sqlmask.riskserver.model.RiskSeverity.HIGH, "CONTACT", true, "SEEDED")))
        .hits(List.of(new io.sqlmask.riskserver.model.RuleHit("MASK_BYPASS",
            "敏感列未脱敏直出", "DATA_EXPOSURE",
            io.sqlmask.riskserver.model.RiskSeverity.HIGH, "e")))
        .riskScore(20)
        .build();
    Map<String, Object> wire = StatsCalculator.eventWire(event);
    assertThat(wire.get("user")).isEqualTo("u");
    assertThat((List<String>) wire.get("sensitiveColumns"))
        .containsExactly("crm.public.customer.phone");
    assertThat(wire.get("topSeverity")).isEqualTo("high");
    assertThat((int) wire.get("riskScore")).isEqualTo(20);
  }
}
