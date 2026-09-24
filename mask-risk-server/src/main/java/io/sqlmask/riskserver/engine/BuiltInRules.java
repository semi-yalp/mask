package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RiskSeverity;
import io.sqlmask.riskserver.model.SensitiveColumn;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.sqlmask.riskserver.engine.SqliPatternRule.sig;

/**
 * The built-in detection catalog: rule metadata (shown in the console), the
 * evaluator each rule binds to, default parameters, and the seeded
 * sensitive-asset registry for the demo instance set.
 */
public final class BuiltInRules {

  private BuiltInRules() {
  }

  /** @return evaluator bindings by rule id (custom rules use CUSTOM key). */
  public static Map<String, RuleEvaluator> evaluators() {
    Map<String, RuleEvaluator> map = new LinkedHashMap<>();
    map.put("SQLI_TAUTOLOGY", new SqliPatternRule(List.of(
        sig("永真表达式 OR 1=1", "\\bor\\s+1\\s*=\\s*1\\b"),
        sig("字符串永真 OR 'a'='a'", "\\bor\\s+'([^']*)'\\s*=\\s*'\\1'"),
        sig("布尔永真 OR TRUE", "\\bor\\s+(true|false)\\b"),
        sig("恒真条件 'x'='x'", "(?<!['\\w])'(\\w)'\\s*=\\s*'\\1'(?!\\w)"))));
    map.put("SQLI_UNION", new SqliPatternRule(List.of(
        sig("UNION SELECT 组合查询", "\\bunion\\s+(all\\s+)?select\\b"))));
    map.put("SQLI_STACKED", new SqliPatternRule(List.of(
        sig("堆叠语句", ";\\s*(select|insert|update|delete|drop|alter|create|truncate|grant|revoke|copy|merge)\\b"))));
    map.put("SQLI_COMMENT", new SqliPatternRule(List.of(
        sig("引号后注释截断", "'\\s*(--|#|/\\*)"),
        sig("行尾注释注入", "\\bwhere\\s+\\S+\\s*=\\s*\\S+\\s*--"),
        sig("块注释混淆", "/\\*.*\\*/"))));
    map.put("SQLI_TIME_BASED", new SqliPatternRule(List.of(
        sig("延时函数 pg_sleep", "\\bpg_sleep\\s*\\("),
        sig("延时函数 sleep", "\\bsleep\\s*\\("),
        sig("延时函数 benchmark", "\\bbenchmark\\s*\\("),
        sig("SQLServer 延时 waitfor delay", "waitfor\\s+delay"))));
    map.put("SQLI_META_PROBE", new SqliPatternRule(List.of(
        sig("信息架构探测 information_schema", "\\binformation_schema\\b"),
        sig("系统目录探测 pg_catalog", "\\bpg_catalog\\b"),
        sig("系统目录探测 pg_tables", "\\bpg_tables\\b"),
        sig("MySQL 系统表探测", "\\bmysql\\s*\\.\\s*(user|schema|db)\\b"),
        sig("SQLite 系统表探测", "\\bsqlite_master\\b"))));
    map.put("SQLI_OBFUSCATION", new SqliPatternRule(List.of(
        sig("十六进制字面量", "0x[0-9a-fA-F]{8,}"),
        sig("CHR 编码链", "\\bchr?\\s*\\(\\s*\\d+\\s*\\)"),
        sig("空串拼接 '||'", "'\\s*\\|\\|\\s*'"))));
    map.put("SENSITIVE_BURST", new SensitiveBurstRule());
    map.put("FAILURE_BURST", new FailureBurstRule());
    map.put("OFF_HOURS_ACCESS", new OffHoursRule());
    map.put("LARGE_RESULT", new LargeResultRule());
    map.put("SELECT_STAR_SENSITIVE", new SelectStarSensitiveRule());
    map.put("MASK_BYPASS", new MaskBypassRule());
    map.put("NEW_SUBJECT_SENSITIVE", new NewSubjectSensitiveRule());
    map.put("BEHAVIOR_BASELINE", new BaselineDeviationRule());
    map.put("CUSTOM", new CustomConditionRule());
    return map;
  }

  /** @return default built-in rules (all enabled, catalog order). */
  public static List<RiskRule> catalog() {
    String now = Instant.now().toString();
    return List.of(
        rule("SQLI_TAUTOLOGY", "永真条件注入", "SQLI", RiskSeverity.CRITICAL,
            "检测 OR 1=1 / OR 'a'='a' 等永真条件——注入测试与绕过认证的典型载荷。", null, now),
        rule("SQLI_UNION", "UNION 注入探测", "SQLI", RiskSeverity.CRITICAL,
            "检测 UNION [ALL] SELECT 组合查询——用于拼接额外结果集窃取数据。", null, now),
        rule("SQLI_STACKED", "堆叠语句注入", "SQLI", RiskSeverity.CRITICAL,
            "检测分号后追加第二条语句（UPDATE/DROP/GRANT 等）——构造异常语句的直接信号。", null, now),
        rule("SQLI_COMMENT", "注释截断注入", "SQLI", RiskSeverity.HIGH,
            "检测引号后接 --/#//* 等注释截断模式——用于吞掉原语句尾部实现注入。", null, now),
        rule("SQLI_TIME_BASED", "延时盲注", "SQLI", RiskSeverity.CRITICAL,
            "检测 pg_sleep / sleep / benchmark / waitfor delay——基于时间侧信道的盲注。", null, now),
        rule("SQLI_META_PROBE", "元数据探测", "SQLI", RiskSeverity.HIGH,
            "检测 information_schema / pg_catalog / mysql.user 等系统对象访问——库表结构踩点。", null, now),
        rule("SQLI_OBFUSCATION", "混淆载荷", "SQLI", RiskSeverity.HIGH,
            "检测长十六进制字面量、CHR() 编码链、空串 '||' 拼接等绕过特征。", null, now),
        rule("SENSITIVE_BURST", "敏感列高频访问", "BEHAVIOR", RiskSeverity.HIGH,
            "同一主体在滑动窗口内访问敏感列达到阈值——异常多次查询/拖库前兆。",
            Map.of("windowSeconds", 60, "count", 10, "sensitivityLevel", "MEDIUM"), now),
        rule("FAILURE_BURST", "失败风暴（探测/枚举）", "BEHAVIOR", RiskSeverity.HIGH,
            "同一主体在滑动窗口内连续失败达到阈值——通常是扫描器、枚举或攻击工具。",
            Map.of("windowSeconds", 60, "count", 5), now),
        rule("OFF_HOURS_ACCESS", "非工作时间访问", "COMPLIANCE", RiskSeverity.MEDIUM,
            "事件发生在配置的业务时段之外——合规审计信号，结合其他命中可升级为攻击。",
            Map.of("startHour", 8, "endHour", 19), now),
        rule("LARGE_RESULT", "大结果集拉取", "DATA_EXPOSURE", RiskSeverity.MEDIUM,
            "单次查询返回行数超过阈值——批量数据外泄信号（消费 QUERY 事件的 rowCount）。",
            Map.of("thresholdRows", 1000), now),
        rule("SELECT_STAR_SENSITIVE", "敏感表全列拉取", "DATA_EXPOSURE", RiskSeverity.MEDIUM,
            "SELECT * 命中含敏感列的表——绕过最小列集合的大范围暴露。", null, now),
        rule("MASK_BYPASS", "敏感列未脱敏直出", "DATA_EXPOSURE", RiskSeverity.HIGH,
            "触碰高敏列但管道报告 masked=false——脱敏策略缺口或人为旁路，数据明文出站。",
            Map.of("sensitivityLevel", "HIGH"), now),
        rule("NEW_SUBJECT_SENSITIVE", "新主体首访高敏列", "BEHAVIOR", RiskSeverity.LOW,
            "用户首次访问某个高敏列（UEBA 基线）——单独低风险，作为关联分析的输入。",
            Map.of("sensitivityLevel", "HIGH"), now),
        rule("BEHAVIOR_BASELINE", "UEBA 行为基线偏离", "BEHAVIOR", RiskSeverity.MEDIUM,
            "对比个人行为画像（访问频率/惯常时段/结果集规模，自动从历史学习）：突发高频、异常大结果集、首次出现于陌生时段时命中。历史样本不足的用户不参与判定（冷启动保护）。",
            Map.of("minHistoryEvents", 20, "deviationWindowMinutes", 10, "burstFloor", 8, "rateMultiplier", 5, "rowCountSamplesMin", 10, "rowCountMultiplier", 5, "hourMinHistory", 30), now));
  }

  /** Demo sensitive-asset registry (crm 样例库). */
  public static List<SensitiveColumn> defaultSensitiveColumns() {
    return List.of(
        new SensitiveColumn("crm.public.customer.id_card", RiskSeverity.HIGH, "IDENTITY", true, "SEEDED"),
        new SensitiveColumn("crm.public.customer.bank_card", RiskSeverity.HIGH, "FINANCE", true, "SEEDED"),
        new SensitiveColumn("crm.public.customer.phone", RiskSeverity.HIGH, "CONTACT", true, "SEEDED"),
        new SensitiveColumn("crm.public.customer.email", RiskSeverity.MEDIUM, "CONTACT", true, "SEEDED"),
        new SensitiveColumn("crm.public.customer.address", RiskSeverity.MEDIUM, "LOCATION", true, "SEEDED"),
        new SensitiveColumn("crm.public.customer.name", RiskSeverity.MEDIUM, "PII", true, "SEEDED"),
        new SensitiveColumn("crm.public.orders.pay_account", RiskSeverity.HIGH, "FINANCE", true, "SEEDED"),
        new SensitiveColumn("crm.public.orders.amount", RiskSeverity.LOW, "FINANCE", true, "SEEDED"));
  }

  private static RiskRule rule(String id, String name, String category,
      RiskSeverity severity, String description, Map<String, Object> params, String now) {
    return new RiskRule(id, name, description, RiskRule.KIND_BUILT_IN, category,
        severity, true, params, null, now, now);
  }
}
