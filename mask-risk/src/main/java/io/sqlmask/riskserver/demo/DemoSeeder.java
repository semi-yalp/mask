package io.sqlmask.riskserver.demo;

import io.sqlmask.riskserver.engine.BuiltInRules;
import io.sqlmask.riskserver.engine.RiskEngine;
import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.SensitiveColumn;
import io.sqlmask.riskserver.store.MemoryRiskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic 48-hour demo traffic: normal analysts, a service account with
 * heavy exports, and five risk stories (SQLi campaign, failure storm, off-hours
 * burst, unmasked SELECT *, first-access). Everything flows through the real
 * engine so scores, hits and alerts are consistent with live traffic.
 */
public class DemoSeeder {

  private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

  private static final ZoneId ZONE = ZoneId.systemDefault();

  private final RiskEngine engine;
  private final MemoryRiskStore store;

  public DemoSeeder(RiskEngine engine) {
    this.engine = engine;
    this.store = engine.store();
  }

  /** Wipes events/alerts, restores default rules + assets, replays the story. */
  public synchronized int seed() {
    store.clearAll();
    for (RiskRule rule : BuiltInRules.catalog()) {
      store.putRule(rule);
    }
    for (SensitiveColumn column : BuiltInRules.defaultSensitiveColumns()) {
      store.putSensitiveColumn(column);
    }
    int count = replay();
    log.info("risk: demo seed complete - {} events, {} alerts", count, store.snapshotAlerts().size());
    return count;
  }

  private int replay() {
    Random random = new Random(42L);
    int count = 0;
    Instant now = Instant.now();

    // --- Two working days of normal analyst traffic (9:00-18:30) ---
    for (int day = 2; day >= 1; day--) {
      count += normalDay(random, now.minus(java.time.Duration.ofHours(day * 24L)));
    }
    // A little this morning, up to five minutes ago
    count += normalMorning(random, now);

    // --- Risk story 1: guest_202 SQLi campaign + failure storm (last evening) ---
    count += sqliCampaign(random, now.minus(java.time.Duration.ofHours(14)));

    // --- Risk story 2: intern_liu off-hours sensitive burst (23:35 tonight) ---
    count += internBurst(random, lastNightAt(23, 35));

    // --- Risk story 3: svc_report heavy evening exports ---
    count += serviceExports(random, now.minus(java.time.Duration.ofHours(10)));

    // --- Risk story 4: consultant_he unmasked SELECT * (2h ago, repeated) ---
    count += consultantBypass(now.minus(java.time.Duration.ofHours(2)));

    // --- Risk story 5: fresh contractor first-touching id_card (40m ago) ---
    count += contractorFirstTouch(now.minus(java.time.Duration.ofMinutes(40)));
    return count;
  }

  private int normalDay(Random random, Instant dayStart) {
    LocalDateTime start = dayStart.atZone(ZONE).toLocalDateTime().withHour(9).withMinute(0);
    int count = 0;
    String[] users = {"zhang_san", "li_si", "wang_wu"};
    String[] ips = {"10.20.1.31", "10.20.1.32", "10.20.2.15"};
    for (int i = 0; i < users.length; i++) {
      int queries = 14 + random.nextInt(8);
      for (int q = 0; q < queries; q++) {
        int minute = random.nextInt(570);   // 9:00-18:30
        Instant at = start.plusMinutes(minute).atZone(ZONE).toInstant();
        boolean touchedSensitive = random.nextInt(3) > 0;
        engine.evaluate(analystQuery(at, users[i], ips[i], touchedSensitive, random));
        count++;
      }
    }
    return count;
  }

  private int normalMorning(Random random, Instant now) {
    // Today since 8:00 up to now-5m; when started before 08:00 local, use the
    // last four hours instead so nothing is stamped into the future.
    LocalDateTime nowLocal = LocalDateTime.ofInstant(now, ZONE);
    LocalDateTime start = nowLocal.withHour(8).withMinute(0);
    if (start.isAfter(nowLocal)) {
      start = nowLocal.minusMinutes(240);
    }
    int count = 0;
    String[] users = {"zhang_san", "li_si", "wang_wu", "chen_jie"};
    String[] ips = {"10.20.1.31", "10.20.1.32", "10.20.2.15", "10.20.3.9"};
    long spanMinutes = Math.max(10, java.time.Duration.between(start, nowLocal).toMinutes() - 5);
    for (int i = 0; i < users.length; i++) {
      int queries = 4 + random.nextInt(5);
      for (int q = 0; q < queries; q++) {
        int minute = random.nextInt((int) spanMinutes);
        Instant at = start.plusMinutes(minute).atZone(ZONE).toInstant();
        engine.evaluate(analystQuery(at, users[i], ips[i], random.nextInt(3) > 0, random));
        count++;
      }
    }
    return count;
  }

  private RiskEvent.Builder analystQuery(Instant at, String user, String ip,
      boolean touchedSensitive, Random random) {
    String sql = touchedSensitive
        ? pick(random, new String[]{
            "SELECT id, name, phone FROM crm.public.customer WHERE status = 'active' LIMIT 50",
            "SELECT email, created_at FROM crm.public.customer WHERE created_at >= date '2026-01-01'",
            "SELECT name, phone, email FROM crm.public.customer WHERE id = " + (100 + random.nextInt(900)),
            "SELECT c.name, c.phone, o.amount FROM crm.public.customer c "
                + "JOIN crm.public.orders o ON o.customer_id = c.id WHERE o.amount > 500"})
        : pick(random, new String[]{
            "SELECT count(*) FROM crm.public.orders WHERE pay_status = 'PAID'",
            "SELECT o.amount, o.created_at FROM crm.public.orders o WHERE o.created_at >= current_date - 7",
            "SELECT c.name, c.address FROM crm.public.customer c LIMIT 100"});
    boolean masked = touchedSensitive;
    return doc(at, "sql-mask", "REWRITE", "SUCCESS", user, ip, "analyst", sql, masked, null, null);
  }

  private int sqliCampaign(Random random, Instant evening) {
    int count = 0;
    String user = "guest_202";
    String ip = "203.0.113.66";
    String[] payloads = {
        "SELECT * FROM crm.public.customer WHERE id_card = '' OR 'a'='a'",
        "SELECT name, phone FROM crm.public.customer WHERE id = 1 UNION SELECT table_name, 1 FROM information_schema.tables",
        "SELECT phone FROM crm.public.customer WHERE id = 1; DROP TABLE crm.public.customer",
        "SELECT id FROM crm.public.customer WHERE name = 'x' AND pg_sleep(5) IS NULL",
        "SELECT * FROM information_schema.columns WHERE table_name = 'customer'",
        "SELECT name FROM crm.public.customer WHERE id_card = 0x27204f52202761273d2761",
        "SELECT phone FROM crm.public.customer WHERE name = 'ab' || 'c' -- ",
    };
    for (int i = 0; i < payloads.length; i++) {
      Instant at = evening.plusSeconds(i * 90L + random.nextInt(20));
      engine.evaluate(doc(at, "sql-mask", "REWRITE", "SUCCESS",
          user, ip, "external", payloads[i], false, null, null));
      count++;
    }
    // failure storm right after: broken probes, PARSE_ERROR / VALIDATION_ERROR
    String[] broken = {
        "SELECT' phone FROM crm.public.customer WHERE 1=1'--",
        "SELECT phone FROM crm.public.customer WHERE id_card = ''' OR 1=1",
        "SELECT FROM FROM crm.public.customer",
        "SELECT phone) FROM crm.public.customer WHERE (id = 1",
        "SELECT phone FROM crm.public.customer WHERE id = 1 UNION",
        "SELECT phone FROM crm.public.customer WHERE name = 'x' AND 1=1; --",
    };
    for (int i = 0; i < broken.length; i++) {
      Instant at = evening.plusSeconds(700L + i * 25L);
      engine.evaluate(doc(at, "sql-mask", "REWRITE", "FAILURE",
          user, ip, "external", broken[i], false,
          i % 2 == 0 ? "PARSE_ERROR" : "VALIDATION_ERROR", "语句解析失败"));
      count++;
    }
    return count;
  }

  private int internBurst(Random random, Instant at) {
    int count = 0;
    String user = "intern_liu";
    String ip = "10.20.9.77";
    // one warm-up normal query (name only), then a rapid burst on phone/id_card
    engine.evaluate(doc(at.minusSeconds(600), "sql-mask", "REWRITE", "SUCCESS",
        user, ip, "intern", "SELECT name FROM crm.public.customer LIMIT 10", true, null, null));
    count++;
    for (int i = 0; i < 12; i++) {
      Instant t = at.plusSeconds(i * 5L);
      String sql = i % 2 == 0
          ? "SELECT phone, id_card FROM crm.public.customer WHERE id = " + (2000 + i)
          : "SELECT phone FROM crm.public.customer WHERE created_at >= current_date - 1 LIMIT 200";
      engine.evaluate(doc(t, "sql-mask", "REWRITE", "SUCCESS",
          user, ip, "intern", sql, true, null, null));
      count++;
    }
    return count;
  }

  private int serviceExports(Random random, Instant evening) {
    int count = 0;
    for (int i = 0; i < 8; i++) {
      Instant at = evening.plusSeconds(i * 1200L + random.nextInt(60));
      long rows = 2000 + random.nextInt(70000);
      RiskEvent.Builder builder = doc(at, "mask-query", "QUERY", "SUCCESS",
          "svc_report", "10.30.0.8", "service",
          "SELECT customer_id, amount, pay_status FROM crm.public.orders WHERE created_at >= current_date - 30",
          false, null, null)
          .rowCount(rows)
          .detail(Map.of("rowCount", rows, "engine", "postgresql", "truncated", rows >= 10000));
      engine.evaluate(builder);
      count++;
    }
    return count;
  }

  private int consultantBypass(Instant at) {
    int count = 0;
    for (int i = 0; i < 4; i++) {
      engine.evaluate(doc(at.plusSeconds(i * 240L), "sql-mask", "REWRITE", "SUCCESS",
          "consultant_he", "10.44.7.5", "partner",
          "SELECT * FROM crm.public.customer WHERE status = 'active' LIMIT 5000",
          false, null, null));
      count++;
    }
    return count;
  }

  private int contractorFirstTouch(Instant at) {
    engine.evaluate(doc(at, "sql-mask", "REWRITE", "SUCCESS",
        "contractor_zhou", "10.44.8.20", "partner",
        "SELECT id_card, bank_card FROM crm.public.customer WHERE id = 42",
        true, null, null));
    return 1;
  }

  /** Builds an event shaped exactly like the mask-audit forwarder payload. */
  private static RiskEvent.Builder doc(Instant at, String service, String eventType,
      String outcome, String user, String ip, String authKind, String sql,
      Boolean masked, String errorCode, String errorMessage) {
    return RiskEvent.builder()
        .timestamp(at)
        .service(service)
        .eventType(eventType)
        .outcome(outcome)
        .durationMs(8L + (errorCode == null ? 40 : 5))
        .sourceIp(ip)
        .user(user)
        .authKind("API_KEY")
        .dialect("postgresql")
        .statementCount(1)
        .masked(masked)
        .rowFiltered(false)
        .originalSql(sql)
        .instance("crm")
        .errorCode(errorCode)
        .errorMessage(errorMessage);
  }

  private static <T> T pick(Random random, T[] options) {
    return options[random.nextInt(options.length)];
  }

  /** Tonight (or last night if early morning) at the given wall clock. */
  private static Instant lastNightAt(int hour, int minute) {
    LocalDateTime target = LocalDateTime.now(ZONE).withHour(hour).withMinute(minute).withSecond(0);
    if (target.isAfter(LocalDateTime.now(ZONE))) {
      target = target.minusDays(1);
    }
    return target.atZone(ZONE).toInstant();
  }
}
