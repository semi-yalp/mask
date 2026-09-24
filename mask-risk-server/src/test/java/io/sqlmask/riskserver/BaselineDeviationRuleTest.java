package io.sqlmask.riskserver;

import io.sqlmask.riskserver.engine.AlertManager;
import io.sqlmask.riskserver.engine.BaselineService;
import io.sqlmask.riskserver.engine.BuiltInRules;
import io.sqlmask.riskserver.engine.RiskEngine;
import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RiskSeverity;
import io.sqlmask.riskserver.model.SensitiveColumn;
import io.sqlmask.riskserver.store.MemoryRiskStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** UEBA baseline learning and deviation detection over the real engine. */
class BaselineDeviationRuleTest {

  private static final ZoneId ZONE = ZoneId.systemDefault();

  private final MemoryRiskStore store = new MemoryRiskStore(100_000);
  private final BaselineService baseline = new BaselineService(store, 3_600_000);
  private final RiskEngine engine = new RiskEngine(store,
      new AlertManager(store, RiskSeverity.MEDIUM, 15 * 60_000L), baseline);

  BaselineDeviationRuleTest() {
    for (RiskRule rule : BuiltInRules.catalog()) {
      store.putRule(rule);
    }
    for (SensitiveColumn column : BuiltInRules.defaultSensitiveColumns()) {
      store.putSensitiveColumn(column);
    }
  }

  private static Map<String, Object> doc(Instant at, String user, String outcome,
      String sql, Long rowCount) {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("@timestamp", at.toEpochMilli());
    doc.put("eventType", rowCount == null ? "REWRITE" : "QUERY");
    doc.put("service", rowCount == null ? "sql-mask" : "mask-query");
    doc.put("outcome", outcome);
    doc.put("sourceIp", "10.0.0.1");
    doc.put("actor", Map.of("user", user, "authKind", "API_KEY"));
    doc.put("dialect", "postgresql");
    doc.put("statementCount", 1);
    doc.put("masked", true);
    doc.put("originalSql", sql);
    doc.put("instance", "crm");
    if (rowCount != null) {
      doc.put("detail", Map.of("rowCount", rowCount));
    }
    return doc;
  }

  private RiskEvent ingest(Map<String, Object> doc) {
    engine.ingest(List.of(doc));
    List<RiskEvent> events = store.snapshotEvents();
    return events.get(events.size() - 1);
  }

  /** Builds a work-hour history: one query per day at 10:00 local. */
  private void seedDailyHistory(String user, int days) {
    LocalDateTime base = LocalDateTime.now(ZONE).withHour(10).withMinute(15).withSecond(0);
    for (int i = days; i >= 1; i--) {
      Instant at = base.minusDays(i).atZone(ZONE).toInstant();
      ingest(doc(at, user, "SUCCESS", "SELECT name, phone FROM crm.public.customer", null));
    }
  }

  @Test
  void rateDeviationFiresOnBurst() {
    seedDailyHistory("u_rate", 30);
    baseline.invalidate();

    Instant now = Instant.now();
    RiskEvent last = null;
    for (int i = 0; i < 8; i++) {
      last = ingest(doc(now.minusSeconds((8 - i) * 20L), "u_rate", "SUCCESS",
          "SELECT phone FROM crm.public.customer WHERE id = " + i, null));
    }
    assertThat(last.hits())
        .anyMatch(h -> h.ruleId().equals("BEHAVIOR_BASELINE") && h.evidence().contains("频率偏离"));
  }

  @Test
  void hourDeviationFiresForUnfamiliarHour() {
    seedDailyHistory("u_hour", 30);
    baseline.invalidate();

    // 23:00 on the day after the last history point - never seen before
    LocalDateTime night = LocalDateTime.now(ZONE).plusDays(1).withHour(23).withMinute(5);
    RiskEvent event = ingest(doc(night.atZone(ZONE).toInstant(), "u_hour", "SUCCESS",
        "SELECT name FROM crm.public.customer", null));
    assertThat(event.hits())
        .anyMatch(h -> h.ruleId().equals("BEHAVIOR_BASELINE") && h.evidence().contains("时段偏离"));
  }

  @Test
  void volumeDeviationFiresOnOutlierResultSize() {
    // 20 QUERY samples with a stable 100-row norm satisfy minHistory + samples
    LocalDateTime base = LocalDateTime.now(ZONE).withHour(11).withMinute(0).withSecond(0);
    for (int i = 20; i >= 1; i--) {
      Instant at = base.minusHours(i * 3L).atZone(ZONE).toInstant();
      ingest(doc(at, "u_vol", "SUCCESS",
          "SELECT customer_id FROM crm.public.orders", 100L));
    }
    baseline.invalidate();

    RiskEvent event = ingest(doc(Instant.now(), "u_vol", "SUCCESS",
        "SELECT customer_id FROM crm.public.orders", 900L));
    assertThat(event.hits())
        .anyMatch(h -> h.ruleId().equals("BEHAVIOR_BASELINE") && h.evidence().contains("结果集偏离"));
  }

  @Test
  void coldStartUsersAreNeverJudged() {
    seedDailyHistory("u_new", 5);
    baseline.invalidate();

    Instant now = Instant.now();
    RiskEvent last = null;
    for (int i = 0; i < 8; i++) {
      last = ingest(doc(now.minusSeconds((8 - i) * 10L), "u_new", "SUCCESS",
          "SELECT phone FROM crm.public.customer", null));
    }
    assertThat(last.hits())
        .noneMatch(h -> h.ruleId().equals("BEHAVIOR_BASELINE"));
  }

  @Test
  void familiarHourAndNormalVolumeStayQuiet() {
    seedDailyHistory("u_calm", 30);
    // same work hour, normal single query
    baseline.invalidate();
    RiskEvent event = ingest(doc(
        LocalDateTime.now(ZONE).withHour(10).withMinute(45).plusDays(1)
            .atZone(ZONE).toInstant(),
        "u_calm", "SUCCESS", "SELECT name FROM crm.public.customer", null));
    assertThat(event.hits())
        .noneMatch(h -> h.ruleId().equals("BEHAVIOR_BASELINE"));
  }
}
