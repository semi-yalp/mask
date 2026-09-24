package io.sqlmask.riskserver;

import io.sqlmask.riskserver.model.Alert;
import io.sqlmask.riskserver.model.CustomRuleSpec;
import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RiskSeverity;
import io.sqlmask.riskserver.model.RuleHit;
import io.sqlmask.riskserver.model.SensitiveColumn;
import io.sqlmask.riskserver.store.PersistingRiskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Snapshot round-trip: state written by one store instance comes back intact. */
class PersistingRiskStoreTest {

  @TempDir
  Path tmp;

  private Path snapshot() {
    return tmp.resolve("state").resolve("risk-store.json");
  }

  @Test
  void roundTripPreservesRulesAlertsEventsAssetsBaseline() {
    PersistingRiskStore first = PersistingRiskStore.loadOrCreate(snapshot(), 1000, 100);

    RiskRule custom = new RiskRule("CUSTOM-abc", "外部 IP 访问客户表", "desc",
        RiskRule.KIND_CUSTOM, "CUSTOM", RiskSeverity.HIGH, false, null,
        CustomRuleSpec.of(List.of(new CustomRuleSpec.ConditionSpec("sql", "contains", "customer")),
            new CustomRuleSpec.WindowSpec(60, 5, "user")),
        "2026-09-24T00:00:00Z", "2026-09-24T01:00:00Z");
    first.putRule(custom);
    first.putRule(new RiskRule("SQLI_UNION", "UNION 注入探测", "d", RiskRule.KIND_BUILT_IN,
        "SQLI", RiskSeverity.CRITICAL, false, null, null, "t", "t"));   // disabled built-in
    first.putSensitiveColumn(new SensitiveColumn("crm.public.customer.phone",
        RiskSeverity.HIGH, "CONTACT", true, "MANUAL"));

    RuleHit hit = new RuleHit("SQLI_UNION", "UNION 注入探测", "SQLI", RiskSeverity.CRITICAL, "ev");
    RiskEvent event = RiskEvent.builder()
        .id("evt-x").timestamp(Instant.parse("2026-09-24T02:00:00Z"))
        .eventType("REWRITE").outcome("SUCCESS").user("guest_9").sourceIp("1.2.3.4")
        .originalSql("SELECT 1 UNION SELECT 2")
        .sensitiveColumns(List.of(new SensitiveColumn("crm.public.customer.phone",
            RiskSeverity.HIGH, "CONTACT", true, "SEEDED")))
        .hits(List.of(hit)).riskScore(40)
        .build();
    first.appendEvent(event);
    first.updateEvent(event.toBuilder().riskScore(45).build());

    Alert acknowledged = Alert.restore("alt-7", "SQLI_UNION", "UNION 注入探测", "SQLI",
        RiskSeverity.CRITICAL, "guest_9", "1.2.3.4", "t", "d", "sql",
        1000L, 2000L, 2500L, "ACKNOWLEDGED", "已下发阻断", List.of("evt-x", "evt-y"), 5);
    first.putAlert(acknowledged);
    first.registerBaseline("crm.public.customer.phone", "guest_9");
    String alertIdAfterRestore = first.nextAlertId();   // bump seq beyond nothing yet

    first.saveNow();

    PersistingRiskStore second = PersistingRiskStore.loadOrCreate(snapshot(), 1000, 100);

    // rules (incl. enabled=false and custom spec)
    var sqlRule = second.findRule("SQLI_UNION").orElseThrow();
    assertThat(sqlRule.enabled()).isFalse();
    var restoredCustom = second.findRule("CUSTOM-abc").orElseThrow();
    assertThat(restoredCustom.spec().window().count()).isEqualTo(5);

    // events with enrichment + score
    assertThat(second.snapshotEvents()).hasSize(1);
    assertThat(second.findEvent("evt-x").orElseThrow().riskScore()).isEqualTo(45);
    assertThat(second.findEvent("evt-x").orElseThrow().hits()).hasSize(1);

    // alerts with workflow state
    Alert restoredAlert = second.findAlert("alt-7").orElseThrow();
    assertThat(restoredAlert.status()).isEqualTo("ACKNOWLEDGED");
    assertThat(restoredAlert.ackNote()).isEqualTo("已下发阻断");
    assertThat(restoredAlert.eventCount()).isEqualTo(5);
    assertThat(restoredAlert.eventIds()).containsExactly("evt-x", "evt-y");

    // assets + baseline + id floors
    assertThat(second.snapshotSensitiveColumns()).hasSize(1);
    assertThat(second.baselineSeen("crm.public.customer.phone", "guest_9")).isTrue();
    String newAlertId = second.nextAlertId();
    assertThat(Integer.parseInt(newAlertId.substring(4)))
        .isGreaterThanOrEqualTo(Integer.parseInt(alertIdAfterRestore.substring(4)));
  }

  @Test
  void closeFlushesPendingMutations() {
    Path file = snapshot();
    PersistingRiskStore store = PersistingRiskStore.loadOrCreate(file, 1000, 100);
    store.putRule(new RiskRule("R1", "规则", "d", RiskRule.KIND_BUILT_IN, "SQLI",
        RiskSeverity.HIGH, true, null, null, "t", "t"));
    store.close();
    assertThat(Files.exists(file)).isTrue();
    PersistingRiskStore reloaded = PersistingRiskStore.loadOrCreate(file, 1000, 100);
    assertThat(reloaded.findRule("R1")).isPresent();
    reloaded.close();
  }

  @Test
  void corruptSnapshotStartsFreshInsteadOfFailing() throws Exception {
    Path file = snapshot();
    Files.createDirectories(file.getParent());
    Files.writeString(file, "{ not json ");
    PersistingRiskStore store = PersistingRiskStore.loadOrCreate(file, 1000, 100);
    assertThat(store.snapshotRules()).isEmpty();
    assertThat(store.snapshotEvents()).isEmpty();
    store.close();
  }

  @Test
  void eventCapTrimsPersistedWindow() {
    PersistingRiskStore store = PersistingRiskStore.loadOrCreate(snapshot(), 1000, 10);
    for (int i = 0; i < 25; i++) {
      store.appendEvent(RiskEvent.builder()
          .id("evt-" + i).timestamp(Instant.ofEpochMilli(1_700_000_000_000L + i))
          .eventType("REWRITE").outcome("SUCCESS")
          .originalSql("SELECT " + i)
          .build());
    }
    store.saveNow();
    PersistingRiskStore reloaded = PersistingRiskStore.loadOrCreate(snapshot(), 1000, 10);
    assertThat(reloaded.snapshotEvents()).hasSize(10);
    // newest kept, oldest dropped
    assertThat(reloaded.findEvent("evt-24")).isPresent();
    assertThat(reloaded.findEvent("evt-5")).isEmpty();
    reloaded.close();
  }
}
