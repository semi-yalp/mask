package io.sqlmask.riskserver.demo;

import io.sqlmask.riskserver.engine.RiskEngine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live attack scenarios for the console's "模拟攻击" toolbar: each injects a
 * small burst of now-timestamped events through the public ingest path, so the
 * dashboard/alert pages update in real time exactly as production traffic would.
 */
public class AttackScenarios {

  /** Scenario id → display info. */
  public record ScenarioInfo(String id, String name, String description, String severity) {
  }

  private static final Map<String, ScenarioInfo> CATALOG = new LinkedHashMap<>();

  static {
    add("sqli-tautology", "永真条件注入", "外部用户提交 OR 'a'='a' 永真载荷试探认证绕过", "CRITICAL");
    add("sqli-union", "UNION 注入", "UNION SELECT 拼接 information_schema 窃取库表结构", "CRITICAL");
    add("sqli-stacked", "堆叠语句", "分号后追加 DROP TABLE——破坏性构造语句", "CRITICAL");
    add("sqli-blind-time", "延时盲注", "pg_sleep 时间侧信道探测", "CRITICAL");
    add("sqli-meta", "元数据探测", "遍历 information_schema 踩点", "HIGH");
    add("burst-sensitive", "敏感列高频访问", "营销账号 60 秒内 12 次连续拉取手机号/身份证", "HIGH");
    add("failure-storm", "失败风暴", "同一用户 60 秒内 6 次解析失败——自动化探测工具特征", "HIGH");
    add("mass-export", "批量拖库", "服务账号单次拉取 6 万行订单数据", "MEDIUM");
    add("select-star-bypass", "SELECT * 未脱敏", "顾问账号全列拉取客户表且 masked=false", "HIGH");
    add("new-user-probe", "新主体首访", "全新用户首次触碰高敏列（UEBA 基线）", "LOW");
    add("baseline-deviation", "UEBA 基线偏离", "低频用户 zhang_san 10 分钟内突发 12 次查询，偏离个人频率基线", "MEDIUM");
  }

  private static void add(String id, String name, String description, String severity) {
    CATALOG.put(id, new ScenarioInfo(id, name, description, severity));
  }

  public static List<ScenarioInfo> catalog() {
    return new ArrayList<>(CATALOG.values());
  }

  private final RiskEngine engine;

  public AttackScenarios(RiskEngine engine) {
    this.engine = engine;
  }

  /** @throws IllegalArgumentException for unknown scenario ids. */
  public RiskEngine.IngestSummary run(String scenarioId) {
    ScenarioInfo info = CATALOG.get(scenarioId);
    if (info == null) {
      throw new IllegalArgumentException("unknown scenario: " + scenarioId
          + " (available: " + String.join(", ", CATALOG.keySet()) + ")");
    }
    return engine.ingest(docsFor(scenarioId));
  }

  private List<Map<String, Object>> docsFor(String id) {
    return switch (id) {
      case "sqli-tautology" -> List.of(
          doc(now(), "REWRITE", "SUCCESS", "demo_attacker", "203.0.113.99",
              "SELECT phone FROM crm.public.customer WHERE id_card = '' OR 'a'='a'", false, null, null));
      case "sqli-union" -> List.of(
          doc(now(), "REWRITE", "SUCCESS", "demo_attacker", "203.0.113.99",
              "SELECT name FROM crm.public.customer WHERE id = 1 UNION SELECT table_name, 1 FROM information_schema.tables",
              false, null, null));
      case "sqli-stacked" -> List.of(
          doc(now(), "REWRITE", "SUCCESS", "demo_attacker", "203.0.113.99",
              "SELECT phone FROM crm.public.customer WHERE id = 1; DROP TABLE crm.public.customer",
              false, null, null));
      case "sqli-blind-time" -> List.of(
          doc(now(), "REWRITE", "SUCCESS", "demo_attacker", "203.0.113.99",
              "SELECT id FROM crm.public.customer WHERE name = 'x' AND pg_sleep(8) IS NULL",
              false, null, null));
      case "sqli-meta" -> List.of(
          doc(now(), "REWRITE", "SUCCESS", "demo_attacker", "203.0.113.99",
              "SELECT * FROM information_schema.columns WHERE table_name = 'customer'",
              false, null, null));
      case "burst-sensitive" -> burst("demo_marketing", "10.66.1.20", 12, 4);
      case "failure-storm" -> List.of(
          doc(now(), "REWRITE", "FAILURE", "demo_attacker", "203.0.113.99",
              "SELECT' phone FROM crm.public.customer WHERE 1=1'--", false, "PARSE_ERROR", "语句解析失败"),
          doc(now(), "REWRITE", "FAILURE", "demo_attacker", "203.0.113.99",
              "SELECT FROM FROM crm.public.customer", false, "PARSE_ERROR", "语句解析失败"),
          doc(now(), "REWRITE", "FAILURE", "demo_attacker", "203.0.113.99",
              "SELECT phone) FROM crm.public.customer WHERE (id = 1", false, "PARSE_ERROR", "语句解析失败"),
          doc(now(), "REWRITE", "FAILURE", "demo_attacker", "203.0.113.99",
              "SELECT phone FROM crm.public.customer WHERE id = 1 UNION", false, "VALIDATION_ERROR", "UNION 子句不完整"),
          doc(now(), "REWRITE", "FAILURE", "demo_attacker", "203.0.113.99",
              "SELECT id_card = ''' OR 1=1 FROM crm.public.customer", false, "VALIDATION_ERROR", "非法表达式"),
          doc(now(), "REWRITE", "FAILURE", "demo_attacker", "203.0.113.99",
              "SELECT phone FROM crm.public.customer WHERE name = 'x' AND 1=1; --", false, "PARSE_ERROR", "语句解析失败"));
      case "mass-export" -> List.of(docWithRows(now(), "QUERY", "SUCCESS", "svc_demo", "10.30.0.99",
          "SELECT customer_id, amount, pay_status FROM crm.public.orders WHERE created_at >= current_date - 90",
          false, 61234L));
      case "select-star-bypass" -> List.of(
          doc(now(), "REWRITE", "SUCCESS", "consultant_demo", "10.44.7.99",
              "SELECT * FROM crm.public.customer WHERE status = 'active' LIMIT 5000", false, null, null));
      case "new-user-probe" -> List.of(
          doc(now(), "REWRITE", "SUCCESS", "brand_new_user", "10.77.7.7",
              "SELECT id_card, bank_card FROM crm.public.customer WHERE id = 7", true, null, null));
      case "baseline-deviation" -> {
        // zhang_san has a low-rate baseline from the seeded 48h story; a rapid
        // 12-query burst within the 10-minute deviation window breaks it.
        List<Map<String, Object>> burst = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
          String sql = i % 3 == 0
              ? "SELECT name, phone, email FROM crm.public.customer WHERE id = " + (300 + i)
              : i % 3 == 1
                  ? "SELECT count(*) FROM crm.public.orders WHERE pay_status = 'PAID' AND id > " + i
                  : "SELECT c.name, c.phone, o.amount FROM crm.public.customer c "
                      + "JOIN crm.public.orders o ON o.customer_id = c.id LIMIT 200";
          burst.add(doc(nowMinus((11 - i) * 8L), "REWRITE", "SUCCESS", "zhang_san",
              "10.20.1.31", sql, true, null, null));
        }
        yield burst;
      }
      default -> throw new IllegalArgumentException("unknown scenario: " + id);
    };
  }

  private List<Map<String, Object>> burst(String user, String ip, int count, int intervalSeconds) {
    List<Map<String, Object>> docs = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String sql = i % 2 == 0
          ? "SELECT phone, id_card FROM crm.public.customer WHERE id = " + (5000 + i)
          : "SELECT phone FROM crm.public.customer WHERE created_at >= current_date - 1 LIMIT 500";
      docs.add(doc(nowMinus(i * intervalSeconds), "REWRITE", "SUCCESS", user, ip, sql, true, null, null));
    }
    return docs;
  }

  private static long now() {
    return Instant.now().toEpochMilli();
  }

  private static long nowMinus(long seconds) {
    return Instant.now().minusSeconds(seconds).toEpochMilli();
  }

  /** Audit-document shape (what the mask-audit forwarder posts). */
  private static Map<String, Object> doc(long timestampMs, String eventType, String outcome,
      String user, String ip, String sql, Boolean masked, String errorCode, String errorMessage) {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("@timestamp", timestampMs);
    doc.put("eventType", eventType);
    doc.put("service", "sql-mask");
    doc.put("outcome", outcome);
    doc.put("durationMs", 12);
    doc.put("sourceIp", ip);
    doc.put("actor", Map.of("user", user, "authKind", "API_KEY"));
    doc.put("dialect", "postgresql");
    doc.put("statementCount", 1);
    doc.put("masked", masked);
    doc.put("rowFiltered", false);
    doc.put("originalSql", sql);
    doc.put("instance", "crm");
    if (errorCode != null) {
      doc.put("error", Map.of("code", errorCode, "message", errorMessage == null ? "" : errorMessage));
    }
    return doc;
  }

  private static Map<String, Object> docWithRows(long timestampMs, String eventType, String outcome,
      String user, String ip, String sql, Boolean masked, long rows) {
    Map<String, Object> doc = doc(timestampMs, eventType, outcome, user, ip, sql, masked, null, null);
    doc.put("service", "mask-query");
    doc.put("detail", Map.of("rowCount", rows, "engine", "postgresql"));
    return doc;
  }
}
