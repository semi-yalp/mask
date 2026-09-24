package io.sqlmask.riskserver;

import io.sqlmask.riskserver.engine.AlertManager;
import io.sqlmask.riskserver.engine.BuiltInRules;
import io.sqlmask.riskserver.engine.RiskEngine;
import io.sqlmask.riskserver.model.Alert;
import io.sqlmask.riskserver.model.CustomRuleSpec;
import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RiskSeverity;
import io.sqlmask.riskserver.model.SensitiveColumn;
import io.sqlmask.riskserver.store.MemoryRiskStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end engine behavior: rules fire, scores accumulate, alerts group. */
class RiskEngineTest {

  private final MemoryRiskStore store = new MemoryRiskStore(100_000);
  private final RiskEngine engine = new RiskEngine(store,
      new AlertManager(store, RiskSeverity.MEDIUM, 15 * 60_000L));

  RiskEngineTest() {
    for (RiskRule rule : BuiltInRules.catalog()) {
      store.putRule(rule);
    }
    for (SensitiveColumn column : BuiltInRules.defaultSensitiveColumns()) {
      store.putSensitiveColumn(column);
    }
  }

  private static Map<String, Object> doc(String sql, String user, String ip,
      String outcome, Instant at, Boolean masked) {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("@timestamp", at.toEpochMilli());
    doc.put("eventType", "REWRITE");
    doc.put("service", "sql-mask");
    doc.put("outcome", outcome);
    doc.put("sourceIp", ip);
    doc.put("actor", Map.of("user", user, "authKind", "API_KEY"));
    doc.put("dialect", "postgresql");
    doc.put("statementCount", 1);
    doc.put("masked", masked);
    doc.put("originalSql", sql);
    doc.put("instance", "crm");
    return doc;
  }

  private RiskEvent ingest(String sql, String user, String ip, String outcome,
      Instant at, Boolean masked) {
    return engine.ingest(List.of(doc(sql, user, ip, outcome, at, masked)))
        .accepted() == 1
        ? store.snapshotEvents().get(store.snapshotEvents().size() - 1)
        : null;
  }

  @Test
  void tautologyInjectionFiresCriticalAlert() {
    Instant now = Instant.now();
    RiskEvent event = ingest(
        "SELECT phone FROM crm.public.customer WHERE id_card = '' OR 'a'='a'",
        "attacker_x", "203.0.113.5", "SUCCESS", now, false);
    assertThat(event).isNotNull();
    assertThat(event.hits().stream().anyMatch(h -> h.ruleId().equals("SQLI_TAUTOLOGY")))
        .as("should hit SQLI_TAUTOLOGY, hits=" + event.hits())
        .isTrue();
    assertThat(event.riskScore()).isGreaterThanOrEqualTo(RiskSeverity.CRITICAL.weight());
    assertThat(store.snapshotAlerts())
        .anyMatch(a -> a.ruleId().equals("SQLI_TAUTOLOGY") && a.severity() == RiskSeverity.CRITICAL);
  }

  @Test
  void stackedAndUnionAndSleepSignaturesFire() {
    Instant now = Instant.now();
    RiskEvent stacked = ingest("SELECT 1; DROP TABLE crm.public.customer",
        "attacker_x", "203.0.113.5", "SUCCESS", now, false);
    assertThat(stacked.hits()).anyMatch(h -> h.ruleId().equals("SQLI_STACKED"));

    RiskEvent union = ingest(
        "SELECT name FROM crm.public.customer WHERE id = 1 UNION SELECT table_name, 1 FROM information_schema.tables",
        "attacker_x", "203.0.113.5", "SUCCESS", now.plusSeconds(1), false);
    assertThat(union.hits()).anyMatch(h -> h.ruleId().equals("SQLI_UNION"));
    assertThat(union.hits()).anyMatch(h -> h.ruleId().equals("SQLI_META_PROBE"));

    RiskEvent blind = ingest(
        "SELECT id FROM crm.public.customer WHERE name = 'x' AND pg_sleep(5) IS NULL",
        "attacker_x", "203.0.113.5", "SUCCESS", now.plusSeconds(2), false);
    assertThat(blind.hits()).anyMatch(h -> h.ruleId().equals("SQLI_TIME_BASED"));
  }

  private static Instant daytime() {
    return LocalDateTime.of(2026, 9, 24, 11, 30)
        .atZone(ZoneId.systemDefault()).toInstant();
  }

  @Test
  void cleanAnalystQueryStaysUnflagged() {
    RiskEvent event = ingest(
        "SELECT count(*) FROM crm.public.orders WHERE pay_status = 'PAID'",
        "analyst_ok", "10.0.0.1", "SUCCESS", daytime(), false);
    assertThat(event.hits()).isEmpty();
    assertThat(event.riskScore()).isZero();
    assertThat(store.snapshotAlerts()).isEmpty();
  }

  @Test
  void repeatHitsFoldIntoOneAlertWithinCooldown() {
    Instant now = Instant.now();
    for (int i = 0; i < 3; i++) {
      ingest("SELECT phone FROM crm.public.customer WHERE id_card = '' OR 1=1",
          "attacker_dup", "203.0.113.9", "SUCCESS", now.plusSeconds(i), false);
    }
    List<Alert> alerts = store.snapshotAlerts().stream()
        .filter(a -> "attacker_dup".equals(a.user()) && a.ruleId().equals("SQLI_TAUTOLOGY"))
        .toList();
    assertThat(alerts).hasSize(1);
    assertThat(alerts.get(0).eventCount()).isEqualTo(3);
  }

  @Test
  void sensitiveBurstFiresAtThreshold() {
    Instant start = Instant.now().minusSeconds(120);
    RiskEvent tenth = null;
    for (int i = 0; i < 10; i++) {
      tenth = ingest("SELECT phone, id_card FROM crm.public.customer WHERE id = " + i,
          "intern_burst", "10.1.1.1", "SUCCESS", start.plusSeconds(i * 5L), true);
    }
    assertThat(tenth.hits()).anyMatch(h -> h.ruleId().equals("SENSITIVE_BURST"));
    assertThat(store.snapshotAlerts())
        .anyMatch(a -> a.ruleId().equals("SENSITIVE_BURST") && "intern_burst".equals(a.user()));
  }

  @Test
  void failureStormFiresAtThreshold() {
    Instant start = Instant.now().minusSeconds(120);
    RiskEvent fifth = null;
    for (int i = 0; i < 5; i++) {
      Map<String, Object> d = doc("SELECT broken" + i + " FROM crm.public.customer",
          "prober_q", "203.0.113.7", "FAILURE", start.plusSeconds(i * 3L), false);
      d.put("error", Map.of("code", "PARSE_ERROR", "message", "bad"));
      engine.ingest(List.of(d));
      fifth = store.snapshotEvents().get(store.snapshotEvents().size() - 1);
    }
    assertThat(fifth.hits()).anyMatch(h -> h.ruleId().equals("FAILURE_BURST"));
  }

  @Test
  void offHoursAndLargeResultFire() {
    Instant night = LocalDateTime.of(2026, 1, 15, 3, 0)
        .atZone(ZoneId.systemDefault()).toInstant();
    Map<String, Object> d = doc(
        "SELECT customer_id, amount FROM crm.public.orders WHERE created_at >= current_date - 30",
        "svc_batch", "10.2.2.2", "SUCCESS", night, false);
    d.put("detail", Map.of("rowCount", 25_000));
    engine.ingest(List.of(d));
    RiskEvent event = store.snapshotEvents().get(store.snapshotEvents().size() - 1);
    assertThat(event.hits()).anyMatch(h -> h.ruleId().equals("OFF_HOURS_ACCESS"));
    assertThat(event.hits()).anyMatch(h -> h.ruleId().equals("LARGE_RESULT"));
  }

  @Test
  void selectStarOnSensitiveTableFiresExposureAndBypass() {
    RiskEvent event = ingest(
        "SELECT * FROM crm.public.customer WHERE status = 'active'",
        "consultant_c", "10.3.3.3", "SUCCESS", Instant.now(), false);
    assertThat(event.hits()).anyMatch(h -> h.ruleId().equals("SELECT_STAR_SENSITIVE"));
    assertThat(event.hits()).anyMatch(h -> h.ruleId().equals("MASK_BYPASS"));
    assertThat(event.sensitiveColumns())
        .anyMatch(c -> c.columnKey().equals("crm.public.customer.id_card"));
  }

  @Test
  void maskedSelectStarAvoidsBypassButStillExposes() {
    RiskEvent event = ingest(
        "SELECT * FROM crm.public.customer WHERE status = 'active'",
        "analyst_masked", "10.0.0.2", "SUCCESS", Instant.now(), true);
    assertThat(event.hits()).anyMatch(h -> h.ruleId().equals("SELECT_STAR_SENSITIVE"));
    assertThat(event.hits()).noneMatch(h -> h.ruleId().equals("MASK_BYPASS"));
  }

  @Test
  void newSubjectFiresOnlyOnce() {
    Instant now = daytime();
    RiskEvent first = ingest("SELECT id_card FROM crm.public.customer WHERE id = 1",
        "newbie_a", "10.4.4.4", "SUCCESS", now, true);
    assertThat(first.hits()).anyMatch(h -> h.ruleId().equals("NEW_SUBJECT_SENSITIVE"));
    // LOW severity: marks the event but never alerts
    assertThat(store.snapshotAlerts()).isEmpty();

    RiskEvent second = ingest("SELECT id_card FROM crm.public.customer WHERE id = 2",
        "newbie_a", "10.4.4.4", "SUCCESS", now.plusSeconds(10), true);
    assertThat(second.hits()).noneMatch(h -> h.ruleId().equals("NEW_SUBJECT_SENSITIVE"));
  }

  @Test
  void customSingleConditionRuleFires() {
    String id = "CUSTOM-t1";
    store.putRule(new RiskRule(id, "禁止访问 customer 表", "test", RiskRule.KIND_CUSTOM,
        "CUSTOM", RiskSeverity.MEDIUM, true, null,
        CustomRuleSpec.of(
            List.of(new CustomRuleSpec.ConditionSpec("table", "contains", "customer")),
            null),
        Instant.now().toString(), Instant.now().toString()));
    RiskEvent event = ingest("SELECT name FROM crm.public.customer LIMIT 5",
        "custom_user", "10.5.5.5", "SUCCESS", Instant.now(), false);
    assertThat(event.hits()).anyMatch(h -> h.ruleId().equals(id));
    assertThat(store.snapshotAlerts()).anyMatch(a -> a.ruleId().equals(id));
  }

  @Test
  void customWindowRuleNeedsRepeatHits() {
    String id = "CUSTOM-w1";
    store.putRule(new RiskRule(id, "5分钟内3次失败即告警", "test", RiskRule.KIND_CUSTOM,
        "CUSTOM", RiskSeverity.HIGH, true, null,
        CustomRuleSpec.of(
            List.of(new CustomRuleSpec.ConditionSpec("outcome", "eq", "FAILURE")),
            new CustomRuleSpec.WindowSpec(300, 3, "user")),
        Instant.now().toString(), Instant.now().toString()));
    Instant start = Instant.now().minusSeconds(200);
    ingest("SELECT 1", "flaky_u", "10.6.6.6", "FAILURE", start, false);
    ingest("SELECT 2", "flaky_u", "10.6.6.6", "FAILURE", start.plusSeconds(30), false);
    RiskEvent third = ingest("SELECT 3", "flaky_u", "10.6.6.6", "FAILURE",
        start.plusSeconds(60), false);
    assertThat(third.hits()).anyMatch(h -> h.ruleId().equals(id)
        && h.evidence().contains("阈值 3"));
  }

  @Test
  void malformedDocumentIsRejectedNotFatal() {
    Map<String, Object> bad = new HashMap<>();
    bad.put("eventType", "REWRITE");
    var summary = engine.ingest(List.of(bad));
    assertThat(summary.rejected()).isEqualTo(1);
    assertThat(summary.accepted()).isZero();
  }

  @Test
  void riskScoreCapsAt100() {
    String worst = "SELECT * FROM information_schema.columns; DROP TABLE x "
        + "WHERE id = '' OR 1=1 AND pg_sleep(9) IS NULL AND 0xdeadbeefcafe = 1";
    RiskEvent event = ingest(worst, "max_score", "203.0.113.1", "SUCCESS", Instant.now(), false);
    assertThat(event.hits().size()).isGreaterThanOrEqualTo(5);
    assertThat(event.riskScore()).isEqualTo(100);
  }
}
