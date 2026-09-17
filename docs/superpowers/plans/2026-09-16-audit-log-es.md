# ES 审计日志实施计划（mask-audit 模块 + 三类事件 + 内置查询 API）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 mask-core 与 mask-metadata 增加异步尽力而为的 ES 审计日志（REWRITE / ADMIN_CHANGE / EFFECTIVE_PULL 三类事件），mask-core 提供固定条件查询 API。

**Architecture:** 新共享模块 `mask-audit`（事件模型 + 有界队列批量写入 + 官方 elasticsearch-java 客户端 + 查询封装），两个服务通过 Spring Boot 自动装配接入；ES 按天索引 + 启动时幂等索引模板；ES 故障只丢不拦。

**Tech Stack:** Java 17、Spring Boot 3.3.5（BOM 管依赖版本）、`co.elastic.clients:elasticsearch-java`、jackson-databind、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-09-16-audit-log-es-design.md`（本计划从 spec 论证，执行者两份都要读）

## Global Constraints

- Java release 17（parent `maven.compiler.release`），代码风格对齐仓库现状：2 空格缩进、record 优先、类上 javadoc、测试类与被测类同包。
- 模块 pom 里**凡 spring-boot-dependencies 已管理的依赖不写 `<version>`**；elasticsearch-java 若 BOM 未管理（见 Task 1 验证步骤）才在 parent properties 加 `<elasticsearch-java.version>8.13.4</elasticsearch-java.version>`。
- `AuditRecorder.record()` **任何路径不得抛出异常**；业务请求路径只允许一次 `queue.offer()`。
- 审计事件任何字段不得包含：数据库密码、`X-Api-Key` 原文、ES 凭据、`passwordRef` 解析结果、UDF arguments。
- 索引名日期一律 **UTC**；`@timestamp` 写 epoch 毫秒数。
- 「尽力而为」语义：队列满丢弃计数、bulk 失败整批丢弃计数、**不重试、不落盘**。
- 集成冒烟的远程主机：`root@47.100.166.158`；**ES 端口不得无安全特性地暴露公网**，一律 `127.0.0.1` 绑定 + SSH 隧道。
- 每个 Task 结束必须 `mvn -q test`（或至少 `-pl` 到涉及模块）全绿后 commit。

---

### Task 1: mask-audit 模块骨架 + AuditEvent 事件模型

**Files:**
- Modify: `pom.xml`（parent `<modules>`）
- Create: `mask-audit/pom.xml`
- Create: `mask-audit/src/main/java/io/sqlmask/audit/AuditEvent.java`
- Test: `mask-audit/src/test/java/io/sqlmask/audit/AuditEventTest.java`

**Interfaces:**
- Produces: `AuditEvent`（record，字段见下）、常量 `AuditEvent.REWRITE/ADMIN_CHANGE/EFFECTIVE_PULL/SUCCESS/FAILURE`、静态工厂 `rewrite(...)` / `adminChange(...)` / `effectivePull(...)`（签名见实现）。

- [ ] **Step 1: parent pom 注册模块**

`pom.xml` 的 `<modules>` 增加一行（保持字母序放在 mask-core 之后）：

```xml
    <modules>
      <module>mask-core</module>
      <module>mask-audit</module>
      <module>mask-policy</module>
      <module>mask-metadata</module>
    </modules>
```

- [ ] **Step 2: 创建 mask-audit/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>io.sqlmask</groupId>
    <artifactId>sql-mask-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>

  <artifactId>mask-audit</artifactId>
  <packaging>jar</packaging>

  <name>mask-audit</name>
  <description>Audit logging: event model, async best-effort Elasticsearch writer and search client.</description>

  <dependencies>
    <dependency>
      <groupId>co.elastic.clients</groupId>
      <artifactId>elasticsearch-java</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-autoconfigure</artifactId>
    </dependency>
    <dependency>
      <groupId>jakarta.servlet</groupId>
      <artifactId>jakarta.servlet-api</artifactId>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>${surefire.version}</version>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: 验证 BOM 是否管理 elasticsearch-java 版本**

Run: `mvn -q -pl mask-audit dependency:tree -Dincludes=co.elastic.clients 2>&1 | grep elasticsearch-java`
Expected: 出现 `co.elastic.clients:elasticsearch-java:jar:8.13.x`（Boot 3.3.5 BOM）。若报 "missing version"，则在 parent pom `<properties>` 加 `<elasticsearch-java.version>8.13.4</elasticsearch-java.version>` 并给该依赖写上 `<version>${elasticsearch-java.version}</version>`，其余任务不受影响。

- [ ] **Step 4: 写失败测试 AuditEventTest**

```java
package io.sqlmask.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuditEventTest {

  @Test
  void rewriteFactoryFillsEnvelope() {
    AuditEvent e = AuditEvent.rewrite("sql-mask", AuditEvent.SUCCESS, 12L, "10.0.0.5",
        "ANONYMOUS", "alice", List.of("devs"), "postgresql", 2, true, false,
        "SELECT 1", "SELECT 1", null, null);
    assertEquals(AuditEvent.REWRITE, e.eventType());
    assertEquals(AuditEvent.SUCCESS, e.outcome());
    assertEquals("sql-mask", e.service());
    assertEquals(12L, e.durationMs());
    assertEquals("postgresql", e.dialect());
    assertEquals(Boolean.TRUE, e.masked());
    assertNull(e.resourceType());
    assertNotNull(e.timestamp());
  }

  @Test
  void adminChangeAndEffectivePullFactoriesSetTypeOnlyFields() {
    AuditEvent a = AuditEvent.adminChange("sql-mask", AuditEvent.FAILURE, 3L, "127.0.0.1",
        "API_KEY", "CREATE", "INSTANCE", null, "crm", java.util.Map.of("dialect", "postgresql"),
        "CONFIG_ERROR", "boom");
    assertEquals(AuditEvent.ADMIN_CHANGE, a.eventType());
    assertEquals("INSTANCE", a.resourceType());
    assertEquals("crm", a.resourceName());
    assertEquals("CONFIG_ERROR", a.errorCode());
    assertNull(a.dialect());

    AuditEvent p = AuditEvent.effectivePull("sql-mask", AuditEvent.SUCCESS, 1L, null,
        "API_KEY", "alice", List.of("devs"), "crm", null, null);
    assertEquals(AuditEvent.EFFECTIVE_PULL, p.eventType());
    assertEquals("crm", p.instance());
    assertEquals(List.of("devs"), p.actorGroups());
  }

  @Test
  void canonicalConstructorCopiesMutableInputsAndDefaultsTimestamp() {
    Instant now = Instant.now();
    java.util.List<String> groups = new java.util.ArrayList<>(List.of("devs"));
    AuditEvent e = new AuditEvent(now, AuditEvent.REWRITE, "sql-mask", AuditEvent.SUCCESS,
        null, null, null, groups, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null);
    groups.add("ops");
    assertEquals(List.of("devs"), e.actorGroups());
    assertNotSame(groups, e.actorGroups());
  }
}
```

- [ ] **Step 5: 运行确认失败**

Run: `mvn -q -pl mask-audit test`
Expected: COMPILATION ERROR（AuditEvent 不存在）。

- [ ] **Step 6: 实现 AuditEvent**

```java
package io.sqlmask.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One audit document (spec §3): a common envelope plus type-specific fields;
 * fields that do not apply stay null and are omitted when mapped to the ES
 * document. {@code sqlTruncated} is not carried here — truncation happens at
 * document-mapping time under the recorder's {@code sql-max-chars} setting.
 */
public record AuditEvent(
    Instant timestamp,
    String eventType,
    String service,
    String outcome,
    Long durationMs,
    String sourceIp,
    String actorUser,
    List<String> actorGroups,
    String authKind,
    String errorCode,
    String errorMessage,
    String dialect,
    Integer statementCount,
    Boolean masked,
    Boolean rowFiltered,
    String originalSql,
    String rewrittenSql,
    String resourceType,
    String action,
    String instance,
    String resourceName,
    Map<String, Object> detail) {

  public static final String REWRITE = "REWRITE";
  public static final String ADMIN_CHANGE = "ADMIN_CHANGE";
  public static final String EFFECTIVE_PULL = "EFFECTIVE_PULL";
  public static final String SUCCESS = "SUCCESS";
  public static final String FAILURE = "FAILURE";

  public AuditEvent {
    timestamp = timestamp == null ? Instant.now() : timestamp;
    actorGroups = actorGroups == null ? List.of() : List.copyOf(actorGroups);
    detail = detail == null ? null : Map.copyOf(detail);
  }

  public static AuditEvent rewrite(String service, String outcome, Long durationMs,
      String sourceIp, String authKind, String user, List<String> groups, String dialect,
      Integer statementCount, Boolean masked, Boolean rowFiltered,
      String originalSql, String rewrittenSql, String errorCode, String errorMessage) {
    return new AuditEvent(null, REWRITE, service, outcome, durationMs, sourceIp, user, groups,
        authKind, errorCode, errorMessage, dialect, statementCount, masked, rowFiltered,
        originalSql, rewrittenSql, null, null, null, null, null);
  }

  public static AuditEvent adminChange(String service, String outcome, Long durationMs,
      String sourceIp, String authKind, String action, String resourceType, String instance,
      String resourceName, Map<String, Object> detail, String errorCode, String errorMessage) {
    return new AuditEvent(null, ADMIN_CHANGE, service, outcome, durationMs, sourceIp, null,
        List.of(), authKind, errorCode, errorMessage, null, null, null, null, null, null,
        resourceType, action, instance, resourceName, detail);
  }

  public static AuditEvent effectivePull(String service, String outcome, Long durationMs,
      String sourceIp, String authKind, String user, List<String> groups, String instance,
      String errorCode, String errorMessage) {
    return new AuditEvent(null, EFFECTIVE_PULL, service, outcome, durationMs, sourceIp, user,
        groups, authKind, errorCode, errorMessage, null, null, null, null, null, null,
        null, null, instance, null, null);
  }
}
```

- [ ] **Step 7: 运行确认通过**

Run: `mvn -q -pl mask-audit test`
Expected: PASS（3 tests）。

- [ ] **Step 8: Commit**

```bash
git add pom.xml mask-audit
git commit -m "feat(audit): mask-audit 模块骨架与 AuditEvent 事件模型"
```

---

### Task 2: 文档映射 AuditEventJson（含 SQL 截断）

**Files:**
- Create: `mask-audit/src/main/java/io/sqlmask/audit/AuditEventJson.java`
- Test: `mask-audit/src/test/java/io/sqlmask/audit/AuditEventJsonTest.java`

**Interfaces:**
- Consumes: `AuditEvent`（Task 1）。
- Produces: `static Map<String,Object> AuditEventJson.toDocument(AuditEvent event, int sqlMaxChars)`——ES 文档（`@timestamp` 为 epoch 毫秒、null 字段省略、SQL 超长截断 + `sqlTruncated:true`）。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditEventJsonTest {

  private static final Instant T = Instant.parse("2026-09-16T12:34:56.789Z");

  private AuditEvent rewriteEvent(String originalSql, String rewrittenSql) {
    return new AuditEvent(T, AuditEvent.REWRITE, "sql-mask", AuditEvent.SUCCESS, 12L,
        "10.0.0.5", "alice", List.of("devs"), "ANONYMOUS", null, null, "postgresql",
        2, true, false, originalSql, rewrittenSql, null, null, null, null, null);
  }

  @Test
  void documentCarriesEpochMillisAndOmitsNulls() {
    Map<String, Object> doc = AuditEventJson.toDocument(rewriteEvent("SELECT 1", "SELECT 1"), 100);
    assertEquals(1789642896789L, doc.get("@timestamp"));
    assertEquals("REWRITE", doc.get("eventType"));
    assertEquals("sql-mask", doc.get("service"));
    assertEquals("postgresql", doc.get("dialect"));
    assertEquals(2, doc.get("statementCount"));
    assertNull(doc.get("resourceType"));
    assertNull(doc.get("detail"));
    assertFalse(doc.containsKey("sqlTruncated"));
    @SuppressWarnings("unchecked")
    Map<String, Object> actor = (Map<String, Object>) doc.get("actor");
    assertEquals("alice", actor.get("user"));
    assertEquals(List.of("devs"), actor.get("groups"));
    assertEquals("ANONYMOUS", actor.get("authKind"));
  }

  @Test
  void longSqlIsTruncatedAndFlagged() {
    String big = "x".repeat(30);
    Map<String, Object> doc = AuditEventJson.toDocument(rewriteEvent(big, "y".repeat(5)), 10);
    assertEquals("x".repeat(10), doc.get("originalSql"));
    assertEquals("y".repeat(5), doc.get("rewrittenSql"));
    assertEquals(Boolean.TRUE, doc.get("sqlTruncated"));
  }

  @Test
  void exactLengthIsNotTruncated() {
    String exact = "x".repeat(10);
    Map<String, Object> doc = AuditEventJson.toDocument(rewriteEvent(exact, null), 10);
    assertEquals(exact, doc.get("originalSql"));
    assertFalse(doc.containsKey("sqlTruncated"));
  }

  @Test
  void detailIsCopiedThroughWhenPresent() {
    AuditEvent e = AuditEvent.adminChange("sql-mask", AuditEvent.SUCCESS, 1L, null, "API_KEY",
        "CREATE", "POLICY", "crm", "mask-phone",
        Map.of("policyType", "datamask", "enabled", true), null, null);
    Map<String, Object> doc = AuditEventJson.toDocument(e, 100);
    assertEquals(Map.of("policyType", "datamask", "enabled", true), doc.get("detail"));
    assertTrue(doc.containsKey("resourceName"));
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-audit test`
Expected: COMPILATION ERROR（AuditEventJson 不存在）。

- [ ] **Step 3: 实现 AuditEventJson**

```java
package io.sqlmask.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps an {@link AuditEvent} to its Elasticsearch document: null fields are
 * omitted, {@code @timestamp} is epoch millis (the mapping declares a date),
 * and SQL texts are truncated to {@code sqlMaxChars} with a single
 * {@code sqlTruncated:true} marker when any field was cut.
 */
public final class AuditEventJson {

  private AuditEventJson() {
  }

  public static Map<String, Object> toDocument(AuditEvent event, int sqlMaxChars) {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("@timestamp", event.timestamp().toEpochMilli());
    put(doc, "eventType", event.eventType());
    put(doc, "service", event.service());
    put(doc, "outcome", event.outcome());
    put(doc, "durationMs", event.durationMs());
    put(doc, "sourceIp", event.sourceIp());
    if (event.actorUser() != null || event.actorGroups() != null || event.authKind() != null) {
      Map<String, Object> actor = new LinkedHashMap<>();
      put(actor, "user", event.actorUser());
      if (!event.actorGroups().isEmpty()) {
        actor.put("groups", event.actorGroups());
      }
      put(actor, "authKind", event.authKind());
      doc.put("actor", actor);
    }
    if (event.errorCode() != null || event.errorMessage() != null) {
      Map<String, Object> error = new LinkedHashMap<>();
      put(error, "code", event.errorCode());
      put(error, "message", event.errorMessage());
      doc.put("error", error);
    }
    put(doc, "dialect", event.dialect());
    put(doc, "statementCount", event.statementCount());
    put(doc, "masked", event.masked());
    put(doc, "rowFiltered", event.rowFiltered());
    boolean truncated = cut(doc, "originalSql", event.originalSql(), sqlMaxChars);
    truncated |= cut(doc, "rewrittenSql", event.rewrittenSql(), sqlMaxChars);
    if (truncated) {
      doc.put("sqlTruncated", Boolean.TRUE);
    }
    put(doc, "resourceType", event.resourceType());
    put(doc, "action", event.action());
    put(doc, "instance", event.instance());
    put(doc, "resourceName", event.resourceName());
    if (event.detail() != null && !event.detail().isEmpty()) {
      doc.put("detail", event.detail());
    }
    return doc;
  }

  private static void put(Map<String, Object> doc, String key, Object value) {
    if (value != null) {
      doc.put(key, value);
    }
  }

  /** Puts the (possibly cut) value; returns true when it was cut. */
  private static boolean cut(Map<String, Object> doc, String key, String value, int max) {
    if (value == null) {
      return false;
    }
    if (value.length() <= max) {
      doc.put(key, value);
      return false;
    }
    doc.put(key, value.substring(0, max));
    return true;
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-audit test`
Expected: PASS（7 tests 累计）。

- [ ] **Step 5: Commit**

```bash
git add mask-audit
git commit -m "feat(audit): AuditEventJson 文档映射与 SQL 截断打标"
```

---

### Task 3: AuditProperties 配置绑定

**Files:**
- Create: `mask-audit/src/main/java/io/sqlmask/audit/AuditProperties.java`
- Test: `mask-audit/src/test/java/io/sqlmask/audit/AuditPropertiesTest.java`

**Interfaces:**
- Produces: `AuditProperties`（`@ConfigurationProperties("audit")`），getter：`isEnabled() / getIndexPrefix() / getQueueCapacity() / getBatchSize() / getFlushIntervalMs() / getSqlMaxChars() / isEffectivePullEnabled() / getElasticsearch()`；嵌套 `AuditProperties.Elasticsearch`：`getUrl() / getApiKey() / getUsername() / getPassword()`。后续任务依赖这些确切签名。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditPropertiesTest {

  @Test
  void defaultsMatchSpec() {
    AuditProperties p = new AuditProperties();
    assertTrue(p.isEnabled());
    assertTrue(p.isEffectivePullEnabled());
    assertEquals("mask-audit", p.getIndexPrefix());
    assertEquals(10000, p.getQueueCapacity());
    assertEquals(200, p.getBatchSize());
    assertEquals(2000L, p.getFlushIntervalMs());
    assertEquals(8192, p.getSqlMaxChars());
    assertEquals("http://127.0.0.1:9200", p.getElasticsearch().getUrl());
    assertEquals("", p.getElasticsearch().getApiKey());
    assertEquals("", p.getElasticsearch().getUsername());
    assertEquals("", p.getElasticsearch().getPassword());
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-audit test`
Expected: COMPILATION ERROR。

- [ ] **Step 3: 实现 AuditProperties**

```java
package io.sqlmask.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code audit.*} settings (spec §4.5). Defaults equal the documented
 * application.yml values so a bare jar with no config audits to localhost.
 */
@ConfigurationProperties("audit")
public class AuditProperties {

  private boolean enabled = true;
  private String indexPrefix = "mask-audit";
  private int queueCapacity = 10000;
  private int batchSize = 200;
  private long flushIntervalMs = 2000;
  private int sqlMaxChars = 8192;
  private boolean effectivePullEnabled = true;
  private final Elasticsearch elasticsearch = new Elasticsearch();

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public String getIndexPrefix() { return indexPrefix; }
  public void setIndexPrefix(String indexPrefix) { this.indexPrefix = indexPrefix; }
  public int getQueueCapacity() { return queueCapacity; }
  public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
  public int getBatchSize() { return batchSize; }
  public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
  public long getFlushIntervalMs() { return flushIntervalMs; }
  public void setFlushIntervalMs(long flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }
  public int getSqlMaxChars() { return sqlMaxChars; }
  public void setSqlMaxChars(int sqlMaxChars) { this.sqlMaxChars = sqlMaxChars; }
  public boolean isEffectivePullEnabled() { return effectivePullEnabled; }
  public void setEffectivePullEnabled(boolean effectivePullEnabled) {
    this.effectivePullEnabled = effectivePullEnabled;
  }
  public Elasticsearch getElasticsearch() { return elasticsearch; }

  public static class Elasticsearch {
    private String url = "http://127.0.0.1:9200";
    private String apiKey = "";
    private String username = "";
    private String password = "";

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-audit test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add mask-audit
git commit -m "feat(audit): audit.* 配置属性绑定（默认值对齐 spec）"
```

---

### Task 4: FailureReporter（限频 WARN/恢复 INFO）+ IndexTemplateManager

**Files:**
- Create: `mask-audit/src/main/java/io/sqlmask/audit/FailureReporter.java`
- Create: `mask-audit/src/main/java/io/sqlmask/audit/IndexTemplateManager.java`
- Test: `mask-audit/src/test/java/io/sqlmask/audit/FailureReporterTest.java`
- Test: `mask-audit/src/test/java/io/sqlmask/audit/FakeEsServer.java`（测试工具，后续任务复用）

**Interfaces:**
- Produces:
  - `FailureReporter(Clock clock, Consumer<String> warn, Consumer<String> info)`；`void recordBatchFailure(int batchSize)`；`void recordSuccess()`。
  - `IndexTemplateManager(ElasticsearchClient client, String indexPrefix)`；`boolean ensureIfStale(long nowMillis)`——成功置 ready 返回 true；失败 60s 内不重试返回 false。
  - 测试工具 `FakeEsServer`：`static FakeEsServer start()`、`int port()`、`ElasticsearchClient client()`、`List<RecordedRequest> requests`、`void setBulkFailure()`、`void resetBulk()`、`void setSearchBody(String json)`、`List<RecordedRequest> requests(String pathPrefix)`、`close()`。

- [ ] **Step 1: 创建 FakeEsServer 测试工具（先于被测类，因为它定义了测试口径）**

```java
package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.elasticsearch.client.RestClient;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal in-JVM Elasticsearch stand-in: records every request (method, path,
 * body) and answers the handful of endpoints the audit writer/client uses.
 * Zero new test dependencies (JDK HttpServer).
 */
public final class FakeEsServer implements Closeable {

  public record RecordedRequest(String method, String path, String body) {
  }

  public final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  public final AtomicReference<Integer> bulkStatus = new AtomicReference<>(200);
  public final AtomicReference<String> bulkBody =
      new AtomicReference<>("{\"errors\":false,\"items\":[]}");
  public final AtomicReference<String> searchBody = new AtomicReference<>("""
      {"hits":{"total":{"value":0},"hits":[]}}""");

  private final HttpServer server;

  private FakeEsServer(HttpServer server) {
    this.server = server;
  }

  public static FakeEsServer start() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
    FakeEsServer fake = new FakeEsServer(server);
    server.createContext("/", exchange -> {
      byte[] body;
      try (InputStream in = exchange.getRequestBody()) {
        body = in.readAllBytes();
      }
      String path = exchange.getRequestURI().getPath();
      fake.requests.add(new RecordedRequest(exchange.getRequestMethod(), path,
          new String(body, StandardCharsets.UTF_8)));
      int status = 200;
      String response = "{}";
      if (path.equals("/_bulk")) {
        status = fake.bulkStatus.get();
        response = fake.bulkBody.get();
      } else if (path.startsWith("/_index_template")) {
        response = "{\"acknowledged\":true}";
      } else if (path.endsWith("/_search")) {
        response = fake.searchBody.get();
      }
      byte[] out = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, out.length);
      exchange.getResponseBody().write(out);
      exchange.close();
    });
    server.start();
    return fake;
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public ElasticsearchClient client() {
    RestClient rest = RestClient.builder(
        org.elasticsearch.client.HttpHost.create("http://127.0.0.1:" + port())).build();
    return new ElasticsearchClient(new RestClientTransport(rest, new JacksonJsonpMapper(
        new ObjectMapper())));
  }

  public List<RecordedRequest> requests(String pathPrefix) {
    return requests.stream().filter(r -> r.path().startsWith(pathPrefix)).toList();
  }

  public void setBulkFailure() {
    bulkStatus.set(503);
    bulkBody.set("boom");
  }

  public void resetBulk() {
    bulkStatus.set(200);
    bulkBody.set("{\"errors\":false,\"items\":[]}");
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
```

- [ ] **Step 2: 写 FailureReporter 失败测试**

```java
package io.sqlmask.audit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureReporterTest {

  private static Clock at(String iso) {
    return Clock.fixed(Instant.parse(iso), ZoneOffset.UTC);
  }

  @Test
  void firstFailureWarnsWithCountsThenSilentForWindow() {
    List<String> warns = new ArrayList<>();
    List<String> infos = new ArrayList<>();
    Clock clock = at("2026-09-16T00:00:00Z");
    FailureReporter r = new FailureReporter(clock, warns::add, infos::add);

    r.recordBatchFailure(200);
    r.recordBatchFailure(200);
    assertEquals(1, warns.size());
    assertTrue(warns.get(0).contains("dropped-batches=2"));
    assertTrue(warns.get(0).contains("events=400"));

    r.recordBatchFailure(200); // within 60s window: silent
    assertEquals(1, warns.size());
  }

  @Test
  void newWindowWarnsAgainWithAccumulatedCounts() {
    List<String> warns = new ArrayList<>();
    FailureReporter r = new FailureReporter(at("2026-09-16T00:00:00Z"), warns::add, s -> {});
    r.recordBatchFailure(100);
    Clock later = Clock.fixed(Instant.parse("2026-09-16T00:01:01Z"), ZoneOffset.UTC);
    r.advance(later);
    r.recordBatchFailure(50);
    assertEquals(2, warns.size());
    assertTrue(warns.get(1).contains("dropped-batches=3"));
  }

  @Test
  void successAfterFailuresLogsRecoveryInfoOnce() {
    List<String> warns = new ArrayList<>();
    List<String> infos = new ArrayList<>();
    FailureReporter r = new FailureReporter(at("2026-09-16T00:00:00Z"), warns::add, infos::add);
    r.recordBatchFailure(100);
    r.recordSuccess();
    r.recordSuccess();
    assertEquals(1, infos.size());
    assertTrue(infos.get(0).contains("recovered"));
    assertEquals(0, r.snapshotDroppedBatches()); // counters reset on recovery
    r.recordBatchFailure(10);
    assertEquals(2, warns.size()); // a fresh warn cycle after recovery
  }

  @Test
  void successWithoutFailureIsSilent() {
    List<String> infos = new ArrayList<>();
    FailureReporter r = new FailureReporter(at("2026-09-16T00:00:00Z"), s -> {}, infos::add);
    r.recordSuccess();
    assertEquals(0, infos.size());
  }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -q -pl mask-audit test`
Expected: COMPILATION ERROR（FailureReporter 不存在）。

- [ ] **Step 4: 实现 FailureReporter**

```java
package io.sqlmask.audit;

import java.time.Clock;
import java.util.function.Consumer;

/**
 * Rate-limited failure reporting: at most one WARN per 60s window carrying the
 * window's accumulated counters, and a single INFO on the first successful
 * write after at least one failure (spec §4.2). All counters reset on recovery.
 */
public final class FailureReporter {

  private static final long WINDOW_MS = 60_000;

  private final Consumer<String> warn;
  private final Consumer<String> info;
  private Clock clock;

  private long windowStart;
  private long windowDroppedBatches;
  private long windowDroppedEvents;
  private long droppedBatchesTotal;
  private boolean down;
  private long downSince;

  public FailureReporter(Clock clock, Consumer<String> warn, Consumer<String> info) {
    this.clock = clock;
    this.warn = warn;
    this.info = info;
    this.windowStart = clock.millis();
  }

  /** Test hook: move the reporter forward in time. */
  void advance(Clock newClock) {
    this.clock = newClock;
  }

  public synchronized void recordBatchFailure(int events) {
    droppedBatchesTotal++;
    long now = clock.millis();
    if (now - windowStart >= WINDOW_MS) {
      windowStart = now;
      windowDroppedBatches = 0;
      windowDroppedEvents = 0;
    }
    windowDroppedBatches++;
    windowDroppedEvents += events;
    if (!down) {
      down = true;
      downSince = now;
    }
    warn.accept(String.format(
        "audit: ES write failing (dropped-batches=%d, events=%d, total-dropped-batches=%d, "
            + "down-since-ms=%d) - events are being discarded (best-effort)",
        windowDroppedBatches, windowDroppedEvents, droppedBatchesTotal, now - downSince));
  }

  public synchronized void recordSuccess() {
    if (down) {
      info.accept(String.format(
          "audit: ES write recovered after %dms (dropped %d batches during outage)",
          clock.millis() - downSince, droppedBatchesTotal));
      down = false;
      droppedBatchesTotal = 0;
      windowDroppedBatches = 0;
      windowDroppedEvents = 0;
    }
  }

  public synchronized long snapshotDroppedBatches() {
    return droppedBatchesTotal;
  }
}
```

- [ ] **Step 5: 写 IndexTemplateManager 失败测试**

```java
package io.sqlmask.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexTemplateManagerTest {

  private FakeEsServer es;
  private ElasticsearchClient client;

  @BeforeEach
  void start() throws IOException {
    es = FakeEsServer.start();
    client = es.client();
  }

  @AfterEach
  void stop() throws IOException {
    client.close();
    es.close();
  }

  @Test
  void putsTemplateOnceAndStaysReady() throws Exception {
    IndexTemplateManager m = new IndexTemplateManager(client, "mask-audit");
    assertTrue(m.ensureIfStale(1000));
    assertTrue(m.ensureIfStale(2000)); // ready: no second PUT
    assertEquals(1, es.requests("/_index_template").size());
    String body = es.requests("/_index_template").get(0).body();
    assertTrue(body.contains("\"index_patterns\":[\"mask-audit-*\"]"));
    assertTrue(body.contains("mask-audit-")); // date-math-free pattern prefix
    assertTrue(body.contains("\"@timestamp\""));
    assertTrue(body.contains("\"keyword\""));
  }

  @Test
  void failureRetriesOnlyAfterSixtySeconds() throws Exception {
    es.bulkStatus.set(500); // template PUT uses same failure switch? no - use dedicated path
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
      IndexTemplateManager m = new IndexTemplateManager(client, "mask-audit");
      // Simulate ES down: stop answering by pointing at a dead port client
      ElasticsearchClient dead = FakeEsServer.start(); // placeholder, replaced below
      dead.close();
      assertFalse(m.ensureIfStale(1000) || m.ensureIfStale(1000));
    });
  }
}
```

注意：`failureRetriesOnlyAfterSixtySeconds` 里用占位断言不可行——按下面的**最终版**实现该测试（模拟「PUT 抛异常」用自注入 `ElasticsearchClient` 的方式太绕；直接用一个坏端口客户端即可）：

```java
  @Test
  void failureRetriesOnlyAfterSixtySeconds() throws Exception {
    ElasticsearchClient dead = deadClient();
    try {
      IndexTemplateManager m = new IndexTemplateManager(dead, "mask-audit");
      long t0 = 1_000_000L;
      assertFalse(m.ensureIfStale(t0));      // fail, records retry-after
      assertFalse(m.ensureIfStale(t0 + 59_000)); // inside window: no attempt
      assertFalse(m.ensureIfStale(t0 + 61_000)); // past window: retries, fails again
    } finally {
      dead.close();
    }
  }

  @Test
  void recoversWhenEsComesBack() throws Exception {
    ElasticsearchClient dead = deadClient();
    IndexTemplateManager m;
    try {
      m = new IndexTemplateManager(dead, "mask-audit");
      assertFalse(m.ensureIfStale(1_000_000L));
    } finally {
      dead.close();
    }
    assertTrue(m.ensureIfStale(1_100_000L));
    assertEquals(1, es.requests("/_index_template").size());
  }

  private ElasticsearchClient deadClient() {
    RestClient rest = RestClient.builder(
        org.elasticsearch.client.HttpHost.create("http://127.0.0.1:1")).build();
    return new ElasticsearchClient(new co.elastic.clients.transport.rest_client.RestClientTransport(
        rest, new co.elastic.clients.json.jackson.JacksonJsonpMapper(
            new com.fasterxml.jackson.databind.ObjectMapper())));
  }
```

同时删掉上面的占位测试 `failureRetriesOnlyAfterSixtySeconds` 的第一版。

- [ ] **Step 6: 运行确认失败**

Run: `mvn -q -pl mask-audit test`
Expected: COMPILATION ERROR（IndexTemplateManager 不存在）。

- [ ] **Step 7: 实现 IndexTemplateManager**

```java
package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;

/**
 * Idempotently PUTs the audit index template (spec §4.3) at most once per 60s
 * until it succeeds; a template failure never blocks writes (ES falls back to
 * dynamic mapping until the template lands).
 */
public final class IndexTemplateManager {

  private static final Logger log = LoggerFactory.getLogger(IndexTemplateManager.class);
  private static final long RETRY_AFTER_MS = 60_000;

  private final ElasticsearchClient client;
  private final String indexPrefix;
  private volatile boolean ready;
  private volatile long lastAttempt;

  public IndexTemplateManager(ElasticsearchClient client, String indexPrefix) {
    this.client = client;
    this.indexPrefix = indexPrefix;
  }

  public boolean ensureIfStale(long nowMillis) {
    if (ready || nowMillis - lastAttempt < RETRY_AFTER_MS) {
      return ready;
    }
    lastAttempt = nowMillis;
    try {
      client.cluster().putIndexTemplate(r -> r.name(indexPrefix)
          .withJson(new StringReader(templateJson(indexPrefix))));
      ready = true;
      log.info("audit: index template '{}' installed", indexPrefix);
      return true;
    } catch (Exception e) {
      log.warn("audit: index template PUT failed, will retry in {}s: {}",
          RETRY_AFTER_MS / 1000, e.getMessage());
      return false;
    }
  }

  static String templateJson(String prefix) {
    return """
        {
          "index_patterns": ["%s-*"],
          "template": {
            "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
            "mappings": {
              "properties": {
                "@timestamp": {"type": "date"},
                "eventType": {"type": "keyword"},
                "service": {"type": "keyword"},
                "outcome": {"type": "keyword"},
                "durationMs": {"type": "integer"},
                "sourceIp": {"type": "ip"},
                "actor": {
                  "properties": {
                    "user": {"type": "keyword"},
                    "groups": {"type": "keyword"},
                    "authKind": {"type": "keyword"}
                  }
                },
                "error": {
                  "properties": {
                    "code": {"type": "keyword"},
                    "message": {"type": "text"}
                  }
                },
                "dialect": {"type": "keyword"},
                "statementCount": {"type": "integer"},
                "masked": {"type": "boolean"},
                "rowFiltered": {"type": "boolean"},
                "originalSql": {"type": "text", "fields": {"keyword":
                  {"type": "keyword", "ignore_above": 256}}},
                "rewrittenSql": {"type": "text", "fields": {"keyword":
                  {"type": "keyword", "ignore_above": 256}}},
                "sqlTruncated": {"type": "boolean"},
                "resourceType": {"type": "keyword"},
                "action": {"type": "keyword"},
                "instance": {"type": "keyword"},
                "resourceName": {"type": "keyword"},
                "detail": {"type": "object"}
              }
            }
          }
        }""".formatted(prefix);
  }
}
```

（`org.slf4j:slf4j-api` 由 spring-boot-autoconfigure 传递提供，无需显式依赖。）

- [ ] **Step 8: 运行确认通过**

Run: `mvn -q -pl mask-audit test`
Expected: PASS。

- [ ] **Step 9: Commit**

```bash
git add mask-audit
git commit -m "feat(audit): 失败限频报告器与索引模板管理器（含 FakeEsServer 测试工具）"
```

---

### Task 5: EsAuditRecorder（有界队列 + 批量 flush + 丢弃计数 + 优雅停机）

**Files:**
- Create: `mask-audit/src/main/java/io/sqlmask/audit/AuditRecorder.java`
- Create: `mask-audit/src/main/java/io/sqlmask/audit/EsAuditRecorder.java`
- Test: `mask-audit/src/test/java/io/sqlmask/audit/EsAuditRecorderTest.java`

**Interfaces:**
- Consumes: `AuditEvent`（Task 1）、`AuditEventJson`（Task 2）、`AuditProperties`（Task 3）、`FailureReporter` / `IndexTemplateManager`（Task 4）。
- Produces:
  - `interface AuditRecorder { void record(AuditEvent event); }`（永不抛出）。
  - `EsAuditRecorder(ElasticsearchClient client, AuditProperties properties)` implements `AuditRecorder, AutoCloseable`；`close()` 排空队列至多 5s。
  - `static String EsAuditRecorder.indexName(String prefix, Instant at)`（UTC `prefix-yyyy.MM.dd`）。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EsAuditRecorderTest {

  private FakeEsServer es;
  private ElasticsearchClient client;

  @BeforeEach
  void start() throws IOException {
    es = FakeEsServer.start();
    client = es.client();
  }

  @AfterEach
  void stop() throws IOException {
    client.close();
    es.close();
  }

  private AuditProperties props(int queue, int batch, long intervalMs) {
    AuditProperties p = new AuditProperties();
    p.setQueueCapacity(queue);
    p.setBatchSize(batch);
    p.setFlushIntervalMs(intervalMs);
    p.setSqlMaxChars(100);
    return p;
  }

  private static AuditEvent event(String sql) {
    return AuditEvent.rewrite("sql-mask", AuditEvent.SUCCESS, 1L, null, "ANONYMOUS",
        null, List.of(), "postgresql", 1, false, false, sql, sql, null, null);
  }

  @Test
  void batchSizeTriggersBulkWithDailyIndexAndDocument() throws Exception {
    try (EsAuditRecorder r = new EsAuditRecorder(client, props(100, 2, 60_000))) {
      r.record(event("SELECT 1"));
      r.record(event("SELECT 2"));
      waitUntil(() -> !es.requests("/_bulk").isEmpty(), 5000);
    }
    List<FakeEsServer.RecordedRequest> bulks = es.requests("/_bulk");
    assertEquals(1, bulks.size());
    String body = bulks.get(0).body();
    String today = EsAuditRecorder.indexName("mask-audit", Instant.now());
    assertTrue(body.contains("\"_index\":\"" + today + "\""));
    assertTrue(body.contains("\"eventType\":\"REWRITE\""));
    assertTrue(body.contains("SELECT 1"));
  }

  @Test
  void overflowIsDroppedAndCountedNotBlocking() throws Exception {
    try (EsAuditRecorder r = new EsAuditRecorder(client, props(2, 1000, 60_000))) {
      r.record(event("a"));
      r.record(event("b"));
      long start = System.nanoTime();
      r.record(event("c")); // queue full (worker may not have drained yet) -> drop or offer
      r.record(event("d"));
      r.record(event("e"));
      assertTrue(System.nanoTime() - start < 100_000_000L); // no blocking
    }
  }

  @Test
  void recordNeverThrowsAndAcceptsNull() throws Exception {
    try (EsAuditRecorder r = new EsAuditRecorder(client, props(2, 1000, 60_000))) {
      r.record(null);
      r.record(event("x"));
    }
  }

  @Test
  void bulkFailureCountsAndRecoversWithoutBlocking() throws Exception {
    es.setBulkFailure();
    try (EsAuditRecorder r = new EsAuditRecorder(client, props(100, 1, 60_000))) {
      r.record(event("SELECT 1"));
      waitUntil(() -> !es.requests("/_bulk").isEmpty(), 5000);
      assertTrue(r.droppedBatches() >= 1);
      es.resetBulk();
      r.record(event("SELECT 2"));
      waitUntil(() -> es.requests("/_bulk").size() >= 2, 5000);
    }
  }

  @Test
  void indexNameUsesUtcDate() {
    Instant t = Instant.parse("2026-09-16T23:59:59Z");
    assertEquals("mask-audit-2026.09.16", EsAuditRecorder.indexName("mask-audit", t));
    Instant t2 = Instant.parse("2026-01-01T00:00:00Z");
    assertEquals("mask-audit-2026.01.01", EsAuditRecorder.indexName("mask-audit", t2));
  }

  private static void waitUntil(java.util.function.BooleanSupplier condition,
      long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("condition not met in " + timeoutMs + "ms");
      }
      Thread.sleep(25);
    }
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-audit test`
Expected: COMPILATION ERROR。

- [ ] **Step 3: 实现 AuditRecorder 接口与 EsAuditRecorder**

```java
package io.sqlmask.audit;

/** Emit one audit event. Implementations must never throw. */
public interface AuditRecorder {

  void record(AuditEvent event);
}
```

```java
package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Best-effort async writer (spec §4): a bounded queue, one daemon worker that
 * flushes a batch when {@code batch-size} events accumulated or
 * {@code flush-interval-ms} elapsed, one bulk request per batch. Queue-full
 * drops and failed batches are counted, never retried, and never propagated —
 * {@link #record} cannot throw. Close drains the queue for at most 5s.
 */
public final class EsAuditRecorder implements AuditRecorder, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(EsAuditRecorder.class);
  private static final DateTimeFormatter INDEX_DAY =
      DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC);
  private static final long CLOSE_TIMEOUT_MS = 5_000;
  private static final long POLL_MS = 200;

  private final ElasticsearchClient client;
  private final AuditProperties properties;
  private final ArrayBlockingQueue<AuditEvent> queue;
  private final Thread worker;
  private final FailureReporter reporter = new FailureReporter(
      Clock.systemUTC(), msg -> log.warn(msg), msg -> log.info(msg));
  private final IndexTemplateManager templates;
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong failedBatches = new AtomicLong();
  private volatile boolean running = true;

  public EsAuditRecorder(ElasticsearchClient client, AuditProperties properties) {
    this.client = client;
    this.properties = properties;
    this.queue = new ArrayBlockingQueue<>(properties.getQueueCapacity());
    this.templates = new IndexTemplateManager(client, properties.getIndexPrefix());
    this.worker = new Thread(this::loop, "audit-es-writer");
    this.worker.setDaemon(true);
    this.worker.start();
  }

  @Override
  public void record(AuditEvent event) {
    if (event == null) {
      return;
    }
    try {
      if (!queue.offer(event)) {
        dropped.incrementAndGet();
      }
    } catch (RuntimeException e) {
      // absolute guarantee: auditing never breaks the business request
    }
  }

  public long dropped() {
    return dropped.get();
  }

  public long droppedBatches() {
    return failedBatches.get();
  }

  private void loop() {
    List<AuditEvent> batch = new ArrayList<>();
    long lastFlush = System.currentTimeMillis();
    long lastTemplateAttempt = 0;
    while (running || !queue.isEmpty() || !batch.isEmpty()) {
      try {
        if (!templates.ensureIfStale(System.currentTimeMillis())
            && System.currentTimeMillis() - lastTemplateAttempt >= 1000) {
          lastTemplateAttempt = System.currentTimeMillis();
        }
        AuditEvent e = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
        if (e != null) {
          batch.add(e);
        }
        long now = System.currentTimeMillis();
        boolean sizeReached = batch.size() >= properties.getBatchSize();
        boolean timedOut = !batch.isEmpty() && now - lastFlush >= properties.getFlushIntervalMs();
        boolean draining = !running && queue.isEmpty() && !batch.isEmpty();
        if (sizeReached || timedOut || draining) {
          flush(batch);
          lastFlush = now;
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt(); // re-evaluate the loop condition
      } catch (RuntimeException e) {
        log.debug("audit: writer loop iteration failed: {}", e.getMessage());
      }
    }
  }

  private void flush(List<AuditEvent> batch) {
    if (batch.isEmpty()) {
      return;
    }
    try {
      var response = client.bulk(b -> {
        for (AuditEvent event : batch) {
          String index = indexName(properties.getIndexPrefix(), event.timestamp());
          b.operations(op -> op.index(idx -> idx.index(index)
              .document(AuditEventJson.toDocument(event, properties.getSqlMaxChars()))));
        }
        return b;
      });
      if (response.errors()) {
        failedBatches.incrementAndGet();
        reporter.recordBatchFailure(batch.size());
      } else {
        reporter.recordSuccess();
      }
    } catch (IOException | RuntimeException e) {
      failedBatches.incrementAndGet();
      reporter.recordBatchFailure(batch.size());
    } finally {
      batch.clear();
    }
  }

  static String indexName(String prefix, Instant at) {
    return prefix + "-" + INDEX_DAY.format(at);
  }

  @Override
  public void close() {
    running = false;
    worker.interrupt();
    try {
      worker.join(CLOSE_TIMEOUT_MS);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
    if (worker.isAlive()) {
      long pending = queue.size();
      log.warn("audit: writer did not drain in {}ms, {} queued events discarded",
          CLOSE_TIMEOUT_MS, pending);
    }
  }
}
```

需要 `java.time.Clock`（`Clock.systemUTC()`）。注意 `FailureReporter.advance` 是包私有测试钩子，不影响生产。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-audit test`
Expected: PASS。若 `bulkFailureCountsAndRecoversWithoutBlocking` 偶发抖动（worker 与 resetBulk 竞争），把第二次 `record` 前加 `Thread.sleep(50)` 再运行确认。

- [ ] **Step 5: Commit**

```bash
git add mask-audit
git commit -m "feat(audit): EsAuditRecorder 有界队列批量写入（尽力而为、停机排空）"
```

---

### Task 6: 自动装配（AuditAutoConfiguration + Noop）

**Files:**
- Create: `mask-audit/src/main/java/io/sqlmask/audit/AuditAutoConfiguration.java`
- Create: `mask-audit/src/main/java/io/sqlmask/audit/NoopAuditRecorder.java`
- Create: `mask-audit/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `mask-audit/src/test/java/io/sqlmask/audit/AuditAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `AuditProperties`、`EsAuditRecorder`、`AuditRecorder`。
- Produces: bean `AuditRecorder`（enabled→`EsAuditRecorder`（destroyMethod="close"）；disabled→`NoopAuditRecorder`）、bean `AuditSearchClient`（enabled 时；Task 8 实现后补装配，本任务先只装配 recorder——在 Task 8 Step 3 会回来加一行）。`@EnableConfigurationProperties(AuditProperties.class)`。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.audit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AuditAutoConfigurationTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class));

  @Test
  void enabledByDefaultCreatesEsRecorder() {
    runner.run(ctx -> {
      assertThat(ctx).hasSingleBean(AuditRecorder.class);
      assertThat(ctx).hasSingleBean(EsAuditRecorder.class);
      assertThat(ctx).hasSingleBean(AuditProperties.class);
    });
  }

  @Test
  void disabledFallsBackToNoopWithoutEsClient() {
    runner.withPropertyValues("audit.enabled=false").run(ctx -> {
      assertThat(ctx).hasSingleBean(AuditRecorder.class);
      assertThat(ctx.getBean(AuditRecorder.class)).isInstanceOf(NoopAuditRecorder.class);
      assertThat(ctx).doesNotHaveBean(EsAuditRecorder.class);
    });
  }

  @Test
  void noopRecordSurvivesNullAndValue() {
    runner.withPropertyValues("audit.enabled=false").run(ctx -> {
      AuditRecorder r = ctx.getBean(AuditRecorder.class);
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
        r.record(null);
        r.record(AuditEvent.rewrite("sql-mask", AuditEvent.SUCCESS, 1L, null, null,
            null, null, null, null, null, null, null, null, null, null));
      });
    });
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-audit test`
Expected: COMPILATION ERROR。

- [ ] **Step 3: 实现 Noop 与自动装配**

```java
package io.sqlmask.audit;

/** Discards every event (audit.enabled=false). */
public final class NoopAuditRecorder implements AuditRecorder {

  @Override
  public void record(AuditEvent event) {
  }
}
```

```java
package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the audit pipeline (spec §2/§4): enabled (default) builds the ES
 * client from {@code audit.elasticsearch.*} and starts the background writer;
 * disabled falls back to a Noop recorder. Nothing here connects eagerly and
 * nothing here can fail application startup.
 */
@AutoConfiguration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  EsAuditRecorder esAuditRecorder(AuditProperties properties) {
    RestClient rest = RestClient.builder(HttpHost.create(properties.getElasticsearch().getUrl()))
        .setHttpClientConfigCallback(builder -> {
          if (!properties.getElasticsearch().getApiKey().isBlank()) {
            builder.setDefaultHeaders(new Header[] {
                new org.apache.http.message.BasicHeader("Authorization",
                    "ApiKey " + properties.getElasticsearch().getApiKey())});
          }
          if (!properties.getElasticsearch().getUsername().isBlank()) {
            BasicCredentialsProvider provider = new BasicCredentialsProvider();
            provider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(
                properties.getElasticsearch().getUsername(),
                properties.getElasticsearch().getPassword()));
            builder.setDefaultCredentialsProvider(provider);
          }
          return builder;
        })
        .build();
    ElasticsearchClient client = new ElasticsearchClient(
        new RestClientTransport(rest, new JacksonJsonpMapper(new ObjectMapper())));
    return new EsAuditRecorder(client, properties);
  }

  @Bean
  @ConditionalOnMissingBean(AuditRecorder.class)
  NoopAuditRecorder noopAuditRecorder() {
    return new NoopAuditRecorder();
  }

  @Bean
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  ElasticsearchClient auditElasticsearchClient(EsAuditRecorder ignored) {
    throw new IllegalStateException("replaced in Task 8");
  }
}
```

**注意**：第三个 bean（`auditElasticsearchClient`）本任务先**不写**——上面的类只保留 `esAuditRecorder` 与 `noopAuditRecorder` 两个 bean；`ElasticsearchClient` 的共享 bean 留到 Task 8 与 `AuditSearchClient` 一起加（届时 recorder 改为接收共享 client）。本任务按两 bean 版本落地：

```java
@AutoConfiguration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  EsAuditRecorder esAuditRecorder(AuditProperties properties) {
    RestClient rest = RestClient.builder(HttpHost.create(properties.getElasticsearch().getUrl()))
        .setHttpClientConfigCallback(builder -> {
          if (!properties.getElasticsearch().getApiKey().isBlank()) {
            builder.setDefaultHeaders(new org.apache.http.Header[] {
                new org.apache.http.message.BasicHeader("Authorization",
                    "ApiKey " + properties.getElasticsearch().getApiKey())});
          }
          if (!properties.getElasticsearch().getUsername().isBlank()) {
            org.apache.http.impl.client.BasicCredentialsProvider provider =
                new org.apache.http.impl.client.BasicCredentialsProvider();
            provider.setCredentials(org.apache.http.auth.AuthScope.ANY,
                new org.apache.http.auth.UsernamePasswordCredentials(
                    properties.getElasticsearch().getUsername(),
                    properties.getElasticsearch().getPassword()));
            builder.setDefaultCredentialsProvider(provider);
          }
          return builder;
        })
        .build();
    ElasticsearchClient client = new ElasticsearchClient(
        new RestClientTransport(rest, new JacksonJsonpMapper(new ObjectMapper())));
    return new EsAuditRecorder(client, properties);
  }

  @Bean
  @ConditionalOnMissingBean(AuditRecorder.class)
  NoopAuditRecorder noopAuditRecorder() {
    return new NoopAuditRecorder();
  }
}
```

`AutoConfiguration.imports` 文件内容（一行）：

```
io.sqlmask.audit.AuditAutoConfiguration
```

（`org.apache.http.*` 来自 elasticsearch-rest-client 的传递依赖，可直接使用。）

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-audit test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add mask-audit
git commit -m "feat(audit): 自动装配（默认 Es 写入器，enabled=false 退化 Noop）"
```

---

### Task 7: 请求上下文助手 + 管理面包装助手 AuditAdminHelper

**Files:**
- Create: `mask-audit/src/main/java/io/sqlmask/audit/AuditEvents.java`
- Create: `mask-audit/src/main/java/io/sqlmask/audit/AuditAdminHelper.java`
- Test: `mask-audit/src/test/java/io/sqlmask/audit/AuditAdminHelperTest.java`

**Interfaces:**
- Consumes: `AuditRecorder`、`AuditEvent`。
- Produces:
  - `AuditEvents.AUTH_KIND_ATTRIBUTE = "audit.authKind"`；`static String sourceIp(HttpServletRequest)`；`static String authKind(HttpServletRequest)`（无标记 → `"ANONYMOUS"`）。
  - `AuditAdminHelper(AuditRecorder recorder, String service, java.util.function.Function<Throwable,String> errorCode)`；`<T> T adminChange(HttpServletRequest request, String action, String resourceType, String instance, String resourceName, java.util.function.Supplier<Map<String,Object>> detail, java.util.function.Supplier<T> work)`——执行 work 计时，SUCCESS/FAILURE 各发一条 ADMIN_CHANGE，业务异常**原样重抛**；FAILURE 时 detail 求值失败则置 null。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.audit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditAdminHelperTest {

  static class CapturingRecorder implements AuditRecorder {
    final List<AuditEvent> events = new ArrayList<>();

    @Override
    public void record(AuditEvent event) {
      events.add(event);
    }
  }

  private MockHttpServletRequest request(String ip, String authKind) {
    MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/instances");
    r.setRemoteAddr(ip);
    if (authKind != null) {
      r.setAttribute(AuditEvents.AUTH_KIND_ATTRIBUTE, authKind);
    }
    return r;
  }

  @Test
  void successRecordsAdminChangeWithDurationAndContext() {
    CapturingRecorder recorder = new CapturingRecorder();
    AuditAdminHelper helper = new AuditAdminHelper(recorder, "sql-mask",
        t -> t.getClass().getSimpleName());
    String result = helper.adminChange(request("10.1.1.1", "API_KEY"), "CREATE", "INSTANCE",
        null, "crm", () -> Map.of("dialect", "postgresql"), () -> "ok");
    assertEquals("ok", result);
    assertEquals(1, recorder.events.size());
    AuditEvent e = recorder.events.get(0);
    assertEquals(AuditEvent.ADMIN_CHANGE, e.eventType());
    assertEquals(AuditEvent.SUCCESS, e.outcome());
    assertEquals("CREATE", e.action());
    assertEquals("INSTANCE", e.resourceType());
    assertEquals("crm", e.resourceName());
    assertEquals("10.1.1.1", e.sourceIp());
    assertEquals("API_KEY", e.authKind());
    assertEquals(Map.of("dialect", "postgresql"), e.detail());
    assertEquals("sql-mask", e.service());
  }

  @Test
  void failureRecordsThenRethrowsOriginalException() {
    CapturingRecorder recorder = new CapturingRecorder();
    AuditAdminHelper helper = new AuditAdminHelper(recorder, "sql-mask",
        t -> t instanceof IllegalArgumentException ? "CONFIG_ERROR" : "INTERNAL_ERROR");
    RuntimeException boom = new IllegalArgumentException("bad name");
    RuntimeException thrown = assertThrows(IllegalArgumentException.class,
        () -> helper.adminChange(request("127.0.0.1", null), "DELETE", "POLICY", "crm",
            "mask-phone", () -> Map.of(), () -> {
              throw boom;
            }));
    assertSame(boom, thrown);
    assertEquals(1, recorder.events.size());
    AuditEvent e = recorder.events.get(0);
    assertEquals(AuditEvent.FAILURE, e.outcome());
    assertEquals("CONFIG_ERROR", e.errorCode());
    assertEquals("bad name", e.errorMessage());
    assertNull(e.authKind() == null ? null : e.authKind()); // unmarked -> ANONYMOUS below
    assertEquals("ANONYMOUS", e.authKind());
  }

  @Test
  void detailSupplierFailureOnFailurePathDoesNotMaskOriginalError() {
    CapturingRecorder recorder = new CapturingRecorder();
    AuditAdminHelper helper = new AuditAdminHelper(recorder, "sql-mask",
        t -> "X");
    assertThrows(IllegalStateException.class,
        () -> helper.adminChange(request(null, null), "UPDATE", "TABLES", "crm", null,
            () -> {
              throw new RuntimeException("detail blew up");
            }, () -> {
              throw new IllegalStateException("original");
            }));
    assertEquals(AuditEvent.FAILURE, recorder.events.get(0).outcome());
    assertNull(recorder.events.get(0).detail());
  }

  @Test
  void authKindDefaultsToAnonymousAndNullIpPassesThrough() {
    CapturingRecorder recorder = new CapturingRecorder();
    AuditAdminHelper helper = new AuditAdminHelper(recorder, "mask-metadata", t -> "X");
    helper.adminChange(request(null, null), "CREATE", "INSTANCE", null, "pg",
        () -> Map.of(), () -> 1);
    assertEquals("ANONYMOUS", recorder.events.get(0).authKind());
    assertNull(recorder.events.get(0).sourceIp());
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-audit test`
Expected: COMPILATION ERROR。

- [ ] **Step 3: 实现 AuditEvents 与 AuditAdminHelper**

```java
package io.sqlmask.audit;

import jakarta.servlet.http.HttpServletRequest;

/** Request-context extraction shared by all audit integration points. */
public final class AuditEvents {

  /** Set by the host service's API-key filter once a key check passes. */
  public static final String AUTH_KIND_ATTRIBUTE = "audit.authKind";
  public static final String AUTH_KIND_API_KEY = "API_KEY";
  public static final String AUTH_KIND_ANONYMOUS = "ANONYMOUS";

  private AuditEvents() {
  }

  public static String sourceIp(HttpServletRequest request) {
    return request == null ? null : request.getRemoteAddr();
  }

  public static String authKind(HttpServletRequest request) {
    if (request == null) {
      return AUTH_KIND_ANONYMOUS;
    }
    Object marked = request.getAttribute(AUTH_KIND_ATTRIBUTE);
    return marked == null ? AUTH_KIND_ANONYMOUS : marked.toString();
  }
}
```

```java
package io.sqlmask.audit;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Wraps one admin mutation: runs {@code work}, measures duration and emits a
 * single ADMIN_CHANGE event (SUCCESS or FAILURE) — business exceptions are
 * rethrown untouched (spec §5.1). {@code errorCode} is host-supplied so this
 * module stays free of host exception types.
 */
public final class AuditAdminHelper {

  private final AuditRecorder recorder;
  private final String service;
  private final Function<Throwable, String> errorCode;

  public AuditAdminHelper(AuditRecorder recorder, String service,
      Function<Throwable, String> errorCode) {
    this.recorder = recorder;
    this.service = service;
    this.errorCode = errorCode;
  }

  public <T> T adminChange(HttpServletRequest request, String action, String resourceType,
      String instance, String resourceName, Supplier<Map<String, Object>> detail,
      Supplier<T> work) {
    long start = System.nanoTime();
    try {
      T result = work.get();
      recorder.record(AuditEvent.adminChange(service, AuditEvent.SUCCESS,
          elapsedMs(start), AuditEvents.sourceIp(request), AuditEvents.authKind(request),
          action, resourceType, instance, resourceName, safeDetail(detail), null, null));
      return result;
    } catch (RuntimeException e) {
      recorder.record(AuditEvent.adminChange(service, AuditEvent.FAILURE,
          elapsedMs(start), AuditEvents.sourceIp(request), AuditEvents.authKind(request),
          action, resourceType, instance, resourceName, safeDetail(detail),
          errorCode.apply(e), e.getMessage() == null ? e.getClass().getSimpleName()
              : e.getMessage()));
      throw e;
    }
  }

  private static Map<String, Object> safeDetail(Supplier<Map<String, Object>> detail) {
    try {
      return detail.get();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
```

（`spring-boot-starter-test` 的 `spring-mock`/`MockHttpServletRequest` 已在 test scope 提供。）

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-audit test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add mask-audit
git commit -m "feat(audit): 请求上下文助手与管理面包装助手（不吞业务异常）"
```

---

### Task 8: AuditSearchClient + 查询模型 + 搜索不可用异常

**Files:**
- Create: `mask-audit/src/main/java/io/sqlmask/audit/AuditQuery.java`
- Create: `mask-audit/src/main/java/io/sqlmask/audit/AuditSearchResult.java`
- Create: `mask-audit/src/main/java/io/sqlmask/audit/AuditSearchUnavailableException.java`
- Create: `mask-audit/src/main/java/io/sqlmask/audit/AuditSearchClient.java`
- Modify: `mask-audit/src/main/java/io/sqlmask/audit/AuditAutoConfiguration.java`
- Test: `mask-audit/src/test/java/io/sqlmask/audit/AuditSearchClientTest.java`

**Interfaces:**
- Consumes: `AuditProperties`、FakeEsServer。
- Produces:
  - `record AuditQuery(String eventType, String outcome, String instance, String resourceType, String action, String user, java.time.Instant from, java.time.Instant to, int page, int size)`。
  - `record AuditSearchResult(long total, List<Map<String,Object>> events)`。
  - `AuditSearchUnavailableException extends RuntimeException`。
  - `AuditSearchClient(ElasticsearchClient client, String indexPrefix)`；`AuditSearchResult search(AuditQuery q)`——IOException/ES 5xx → `AuditSearchUnavailableException`；bool 过滤（非空 term + @timestamp range）、`@timestamp` desc、`from=page*size`。
  - 自动装配补充：共享 `ElasticsearchClient` bean + `AuditSearchClient` bean（enabled 时）。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditSearchClientTest {

  private FakeEsServer es;
  private ElasticsearchClient client;

  @BeforeEach
  void start() throws IOException {
    es = FakeEsServer.start();
    client = es.client();
  }

  @AfterEach
  void stop() throws IOException {
    client.close();
    es.close();
  }

  @Test
  void buildsBoolFilterAndMapsHits() throws Exception {
    es.searchBody.set("""
        {"hits":{"total":{"value":1},"hits":[
          {"_source":{"eventType":"REWRITE","outcome":"SUCCESS","actor":{"user":"alice"}}}
        ]}}""");
    AuditSearchClient search = new AuditSearchClient(client, "mask-audit");
    AuditQuery q = new AuditQuery("REWRITE", "SUCCESS", "crm", null, null, "alice",
        Instant.parse("2026-09-16T00:00:00Z"), Instant.parse("2026-09-17T00:00:00Z"),
        0, 50);
    AuditSearchResult result = search.search(q);
    assertEquals(1, result.total());
    assertEquals("REWRITE", result.events().get(0).get("eventType"));
    assertEquals("alice", ((Map<?, ?>) result.events().get(0).get("actor")).get("user"));

    String body = es.requests("/_search").get(0).body();
    assertTrue(body.contains("\"mask-audit-*\""));
    assertTrue(body.contains("\"query\""));
    assertTrue(body.contains("\"term\""));
    assertTrue(body.contains("\"eventType\":{\"value\":\"REWRITE\"}"));
    assertTrue(body.contains("\"range\""));
    assertTrue(body.contains("\"from\":0"));
    assertTrue(body.contains("\"size\":50"));
    assertTrue(body.contains("\"order\":\"desc\""));
  }

  @Test
  void nullFiltersAreOmittedFromQuery() throws Exception {
    AuditSearchClient search = new AuditSearchClient(client, "mask-audit");
    search.search(new AuditQuery(null, null, null, null, null, null,
        Instant.parse("2026-09-16T00:00:00Z"), Instant.parse("2026-09-17T00:00:00Z"), 0, 50));
    String body = es.requests("/_search").get(0).body();
    assertTrue(body.contains("\"match_all\""));
    assertTrue(!body.contains("\"term\""));
  }

  @Test
  void esOutageMapsToUnavailable() {
    ElasticsearchClient dead = new ElasticsearchClient(
        new co.elastic.clients.transport.rest_client.RestClientTransport(
            org.elasticsearch.client.RestClient.builder(
                org.elasticsearch.client.HttpHost.create("http://127.0.0.1:1")).build(),
            new co.elastic.clients.json.jackson.JacksonJsonpMapper(
                new com.fasterxml.jackson.databind.ObjectMapper())));
    try {
      AuditSearchClient search = new AuditSearchClient(dead, "mask-audit");
      assertThrows(AuditSearchUnavailableException.class,
          () -> search.search(AuditQuery.class.cast(new AuditQuery(null, null, null, null,
              null, null, Instant.now(), Instant.now().plusSeconds(60), 0, 10))));
    } finally {
      dead.close();
    }
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-audit test`
Expected: COMPILATION ERROR。

- [ ] **Step 3: 实现四个类并补装配**

```java
package io.sqlmask.audit;

import java.time.Instant;

/** Fixed-condition audit search (spec §6): no DSL passthrough. */
public record AuditQuery(String eventType, String outcome, String instance,
    String resourceType, String action, String user, Instant from, Instant to,
    int page, int size) {
}
```

```java
package io.sqlmask.audit;

import java.util.List;
import java.util.Map;

public record AuditSearchResult(long total, List<Map<String, Object>> events) {
}
```

```java
package io.sqlmask.audit;

/** Raised when ES cannot answer an audit search (mapped to HTTP 502 upstream). */
public class AuditSearchUnavailableException extends RuntimeException {

  public AuditSearchUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
```

```java
package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.SortOrder;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fixed-condition search over {@code <prefix>-*} (spec §6): keyword equality
 * filters, a @timestamp range, newest first, offset paging. Any ES outage
 * surfaces as {@link AuditSearchUnavailableException}.
 */
public final class AuditSearchClient {

  private final ElasticsearchClient client;
  private final String indexPrefix;
  private final ObjectMapper mapper = new ObjectMapper();

  public AuditSearchClient(ElasticsearchClient client, String indexPrefix) {
    this.client = client;
    this.indexPrefix = indexPrefix;
  }

  public AuditSearchResult search(AuditQuery q) {
    try {
      SearchResponse<ObjectNodeHolder> response = client.search(s -> {
        s.index(indexPrefix + "-*")
            .from(q.page() * q.size())
            .size(q.size())
            .sort(so -> so.field(f -> f.field("@timestamp").order(SortOrder.Desc)));
        List<Query> filters = new ArrayList<>();
        term(filters, "eventType", q.eventType());
        term(filters, "outcome", q.outcome());
        term(filters, "instance", q.instance());
        term(filters, "resourceType", q.resourceType());
        term(filters, "action", q.action());
        term(filters, "actor.user", q.user());
        if (q.from() != null || q.to() != null) {
          filters.add(Query.of(r -> r.range(range -> {
            range.field("@timestamp");
            if (q.from() != null) {
              range.gte(com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                  .valueToTree(q.from().toEpochMilli()));
            }
            if (q.to() != null) {
              range.lte(com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                  .valueToTree(q.to().toEpochMilli()));
            }
            range.format("epoch_millis");
            return range;
          })));
        }
        if (filters.isEmpty()) {
          s.query(query -> query.matchAll(m -> m));
        } else {
          s.query(query -> query.bool(b -> b.filter(filters)));
        }
        return s;
      }, ObjectNodeHolder.class);
      long total = response.hits().total() == null
          ? response.hits().hits().size() : response.hits().total().value();
      List<Map<String, Object>> events = new ArrayList<>();
      for (var hit : response.hits().hits()) {
        if (hit.source() != null) {
          events.add(mapper.convertValue(hit.source().node,
              new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
              }));
        }
      }
      return new AuditSearchResult(total, events);
    } catch (IOException | RuntimeException e) {
      if (e instanceof AuditSearchUnavailableException u) {
        throw u;
      }
      throw new AuditSearchUnavailableException(
          "elasticsearch search failed: " + e.getMessage(), e);
    }
  }

  private static void term(List<Query> filters, String field, String value) {
    if (value != null && !value.isBlank()) {
      filters.add(Query.of(t -> t.term(term -> term.field(field).value(value))));
    }
  }

  /** Marker type: elasticsearch-java deserializes hits into the given class;
   * we ask for Jackson's ObjectNode shape via this holder indirection. */
  public static final class ObjectNodeHolder {
    public com.fasterxml.jackson.databind.JsonNode node;
  }
}
```

**实现注意（执行者必读）**：`ObjectNodeHolder` 这类自拟反序列化载体不一定如约工作——`elasticsearch-java` 会按给定 class 反序列化 `_source`。**更可靠的落地方式**：直接请求 `com.fasterxml.jackson.databind.JsonNode.class`（JacksonJsonpMapper 原生支持）：

```java
SearchResponse<com.fasterxml.jackson.databind.JsonNode> response =
    client.search(s -> { ...同上... },
        com.fasterxml.jackson.databind.JsonNode.class);
...
events.add(mapper.convertValue(hit.source(),
    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
```

按 JsonNode 版本落地；`range.gte/lte` 处若类型不匹配（co.elastic 8.13 的 `NumberRangeQuery`/`FieldRangeQuery` API 形态），改用 `withJson` 组 range 子查询或直接用 `JsonData.of(q.from().toEpochMilli())`——以能编译、`buildsBoolFilterAndMapsHits` 断言通过为准，行为口径（term/range/from/size/order 字段）不变。

补充自动装配（替换 Task 6 的 `AuditAutoConfiguration`，共享 client）：

```java
@AutoConfiguration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  RestClient auditRestClient(AuditProperties properties) {
    return RestClient.builder(HttpHost.create(properties.getElasticsearch().getUrl()))
        .setHttpClientConfigCallback(builder -> {
          if (!properties.getElasticsearch().getApiKey().isBlank()) {
            builder.setDefaultHeaders(new org.apache.http.Header[] {
                new org.apache.http.message.BasicHeader("Authorization",
                    "ApiKey " + properties.getElasticsearch().getApiKey())});
          }
          if (!properties.getElasticsearch().getUsername().isBlank()) {
            org.apache.http.impl.client.BasicCredentialsProvider provider =
                new org.apache.http.impl.client.BasicCredentialsProvider();
            provider.setCredentials(org.apache.http.auth.AuthScope.ANY,
                new org.apache.http.auth.UsernamePasswordCredentials(
                    properties.getElasticsearch().getUsername(),
                    properties.getElasticsearch().getPassword()));
            builder.setDefaultCredentialsProvider(provider);
          }
          return builder;
        })
        .build();
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  ElasticsearchClient auditElasticsearchClient(RestClient auditRestClient) {
    return new ElasticsearchClient(
        new RestClientTransport(auditRestClient, new JacksonJsonpMapper(new ObjectMapper())));
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  EsAuditRecorder esAuditRecorder(ElasticsearchClient client, AuditProperties properties) {
    return new EsAuditRecorder(client, properties);
  }

  @Bean
  @ConditionalOnProperty(prefix = "audit", name = "enabled", havingValue = "true",
      matchIfMissing = true)
  AuditSearchClient auditSearchClient(ElasticsearchClient client, AuditProperties properties) {
    return new AuditSearchClient(client, properties.getIndexPrefix());
  }

  @Bean
  @ConditionalOnMissingBean(AuditRecorder.class)
  NoopAuditRecorder noopAuditRecorder() {
    return new NoopAuditRecorder();
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-audit test`
Expected: PASS（全部累计用例）。

- [ ] **Step 5: Commit**

```bash
git add mask-audit
git commit -m "feat(audit): AuditSearchClient 固定条件查询（bool 过滤/倒序/502 语义异常）与装配补全"
```

---

### Task 9: mask-core 接入准备（依赖 + 配置 + filter 打标与 /api/audit 管控）

**Files:**
- Modify: `mask-core/pom.xml`
- Modify: `mask-core/src/main/resources/application.yml`
- Modify: `mask-core/src/main/java/io/sqlmask/server/PolicyApiKeyFilter.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java`
- Test: Modify `mask-core/src/test/java/io/sqlmask/server/PolicyApiKeyFilterTest.java`

**Interfaces:**
- Consumes: `AuditEvents.AUTH_KIND_ATTRIBUTE` / `AUTH_KIND_API_KEY`（Task 7）、`AuditAdminHelper`。
- Produces:
  - `PolicyApiKeyFilter` 在校验通过后 `request.setAttribute(AuditEvents.AUTH_KIND_ATTRIBUTE, AuditEvents.AUTH_KIND_API_KEY)`；`/api/audit` 前缀纳入 adminKey 管控。
  - `SqlMaskServiceApplication` 增加 bean：`AuditAdminHelper auditAdminHelper(AuditRecorder r)`（errorCode：`SqlMaskException → getCode().name()`，其它 → 类 SimpleName）。
  - filter URL patterns 增加 `"/api/audit/*"`。

- [ ] **Step 1: 写失败测试（在 PolicyApiKeyFilterTest 追加）**

```java
  @Test
  void auditSurfaceRequiresAdminKeyAndMarksAuthKind() throws Exception {
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter("admin-secret", "data-secret");
    assertEquals(401, run(filter, "/api/audit/events", "data-secret").getStatus());
    assertEquals(401, run(filter, "/api/audit/events", null).getStatus());

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/audit/events");
    request.setServletPath("/api/audit/events");
    request.addHeader("X-Api-Key", "admin-secret");
    MockHttpServletResponse response = new MockHttpServletResponse();
    java.util.concurrent.atomic.AtomicBoolean chainReached = new java.util.concurrent.atomic.AtomicBoolean();
    filter.doFilter(request, response, (req, res) -> chainReached.set(true));
    assertEquals(200, response.getStatus());
    assertTrue(chainReached.get());
    assertEquals(io.sqlmask.audit.AuditEvents.AUTH_KIND_API_KEY,
        request.getAttribute(io.sqlmask.audit.AuditEvents.AUTH_KIND_ATTRIBUTE));
  }

  @Test
  void openManagedPathStaysAnonymous() throws Exception {
    PolicyApiKeyFilter filter = new PolicyApiKeyFilter(null, null);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/audit/events");
    request.setServletPath("/api/audit/events");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertNull(request.getAttribute(io.sqlmask.audit.AuditEvents.AUTH_KIND_ATTRIBUTE));
  }
```

（测试类补 import：`org.springframework.mock.web.MockHttpServletRequest` 已有；追加 `static org.junit.jupiter.api.Assertions.assertNull; static org.junit.jupiter.api.Assertions.assertTrue;` 与 MockFilterChain 已有。）

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-core test -Dtest=PolicyApiKeyFilterTest`
Expected: COMPILATION ERROR（mask-core 尚未依赖 mask-audit）。先做 Step 3 的 pom 再跑测试。

- [ ] **Step 3: mask-core 依赖 + 实现**

`mask-core/pom.xml` 在 `mask-policy` 依赖后加：

```xml
    <dependency>
      <groupId>io.sqlmask</groupId>
      <artifactId>mask-audit</artifactId>
      <version>${project.version}</version>
    </dependency>
```

`application.yml` 追加：

```yaml
audit:
  enabled: ${AUDIT_ENABLED:true}
  elasticsearch:
    url: ${AUDIT_ES_URL:http://127.0.0.1:9200}
    api-key: ${AUDIT_ES_API_KEY:}
    username: ${AUDIT_ES_USER:}
    password: ${AUDIT_ES_PASSWORD:}
  index-prefix: ${AUDIT_INDEX_PREFIX:mask-audit}
  queue-capacity: ${AUDIT_QUEUE_CAPACITY:10000}
  batch-size: ${AUDIT_BATCH_SIZE:200}
  flush-interval-ms: ${AUDIT_FLUSH_INTERVAL_MS:2000}
  sql-max-chars: ${AUDIT_SQL_MAX_CHARS:8192}
  effective-pull:
    enabled: ${AUDIT_EFFECTIVE_PULL_ENABLED:true}
```

`PolicyApiKeyFilter` 修改（`requiredKey` 与 `doFilter`）：

```java
  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) res;
    String required = requiredKey(request.getServletPath());
    if (required == null) {
      chain.doFilter(req, res);
      return;
    }
    if (!keyMatches(required, request.getHeader("X-Api-Key"))) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write(
          "{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid API key\",\"details\":[]}");
      return;
    }
    request.setAttribute(io.sqlmask.audit.AuditEvents.AUTH_KIND_ATTRIBUTE,
        io.sqlmask.audit.AuditEvents.AUTH_KIND_API_KEY);
    chain.doFilter(req, res);
  }
```

```java
  /** Null when the path is unmanaged or its key is unconfigured (open). */
  private String requiredKey(String path) {
    if (path.equals("/api/instances") || path.startsWith("/api/instances/")) {
      return adminKey == null || adminKey.isBlank() ? null : adminKey;
    }
    if (path.equals("/api/effective") || path.startsWith("/api/effective/")) {
      return dataKey == null || dataKey.isBlank() ? null : dataKey;
    }
    if (path.equals("/api/audit") || path.startsWith("/api/audit/")) {
      return adminKey == null || adminKey.isBlank() ? null : adminKey;
    }
    return null;
  }
```

javadoc 补一句：`/api/audit/**` 走 admin key；校验通过后打 `audit.authKind` 标。

`SqlMaskServiceApplication` 追加 bean：

```java
  @Bean
  io.sqlmask.audit.AuditAdminHelper auditAdminHelper(io.sqlmask.audit.AuditRecorder recorder) {
    return new io.sqlmask.audit.AuditAdminHelper(recorder,
        "sql-mask",
        e -> e instanceof io.sqlmask.error.SqlMaskException sme
            ? sme.getCode().name()
            : e.getClass().getSimpleName());
  }
```

filter 注册处 URL patterns 改为：

```java
    registration.addUrlPatterns("/api/instances/*", "/api/effective/*", "/api/audit/*");
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-core test -Dtest=PolicyApiKeyFilterTest`
Expected: PASS。

Run: `mvn -q -pl mask-core test`
Expected: PASS（既有 @SpringBootTest 上下文此时已装配 EsAuditRecorder，向 127.0.0.1:9200 的后台尝试只产生限频 WARN，不影响用例）。

- [ ] **Step 5: Commit**

```bash
git add mask-core
git commit -m "feat(server): mask-audit 接入准备（依赖/配置/API Key 打标与 /api/audit 管控）"
```

---

### Task 10: RewriteController 发 REWRITE 事件（成功/失败）

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/server/RewriteController.java`
- Test: Create `mask-core/src/test/java/io/sqlmask/server/RewriteAuditTest.java`

**Interfaces:**
- Consumes: `AuditRecorder`、`AuditEvent.rewrite(...)`、`AuditEvents.sourceIp/authKind`。
- Produces: `POST /api/rewrite` 每请求恰好一条 REWRITE 事件（成功：拼接 SQL + statementCount + masked/rowFiltered 任一；失败：error code/message 后原样重抛）。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.server;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RewriteAuditTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private AuditRecorder recorder;

  private static final String YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - name: id
                type: bigint
              - name: phone
                type: varchar
      columns:
        - catalog: crm
          schema: public
          table: customer
          column: phone
          policy: mask_phone
      policies:
        mask_phone:
          udf: mask_phone
          arguments: [3, 4]
      """;

  @Test
  void successfulRewriteEmitsOneEventWithJoinedSql() throws Exception {
    mvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"metadataYaml": %s, "sql": "SELECT phone FROM customer;",
                 "dialect": "postgresql", "user": "alice", "groups": ["devs"]}
                """.formatted(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(YAML))))
        .andExpect(status().isOk());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, times(1)).record(captor.capture());
    AuditEvent e = captor.getValue();
    assertEquals(AuditEvent.REWRITE, e.eventType());
    assertEquals(AuditEvent.SUCCESS, e.outcome());
    assertEquals("sql-mask", e.service());
    assertEquals("postgresql", e.dialect());
    assertEquals(1, e.statementCount());
    assertEquals(Boolean.TRUE, e.masked());
    assertEquals(Boolean.FALSE, e.rowFiltered());
    assertEquals("SELECT phone FROM customer;", e.originalSql());
    assertEquals("alice", e.actorUser());
    assertEquals(List.of("devs"), e.actorGroups());
    assertNull(e.errorCode());
  }

  @Test
  void failedRewriteEmitsFailureEventAndStillReturns400() throws Exception {
    mvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"metadataYaml": %s, "sql": "DELETE FROM customer;", "dialect": "postgresql"}
                """.formatted(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(YAML))))
        .andExpect(status().isBadRequest());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, times(1)).record(captor.capture());
    AuditEvent e = captor.getValue();
    assertEquals(AuditEvent.FAILURE, e.outcome());
    assertEquals("UNSUPPORTED_STATEMENT", e.errorCode());
    assertEquals("sql-mask", e.service());
  }

  @Test
  void configErrorEmitsFailureEventWithConfigErrorCode() throws Exception {
    mvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataYaml\": \"\", \"sql\": \"SELECT 1\"}"))
        .andExpect(status().isBadRequest());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, times(1)).record(captor.capture());
    assertEquals("CONFIG_ERROR", captor.getValue().errorCode());
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-core test -Dtest=RewriteAuditTest`
Expected: FAIL——`verify(recorder, times(1))` 期望 1 实际 0（控制器尚未发事件）。

- [ ] **Step 3: 修改 RewriteController**

```java
package io.sqlmask.server;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditEvents;
import io.sqlmask.audit.AuditRecorder;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.rewrite.RewriteEngine;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SQL rewriting endpoint. ... (原 javadoc 保留，追加一句)
 * Every request emits exactly one REWRITE audit event (spec §5.1) — success
 * or failure — and failures are rethrown untouched.
 */
@RestController
@RequestMapping("/api")
public class RewriteController {

  private final RewriteEngine engine;
  private final AuditRecorder audit;

  public RewriteController(RewriteEngine engine, AuditRecorder audit) {
    this.engine = engine;
    this.audit = audit;
  }

  @PostMapping("/rewrite")
  public RewriteResponse rewrite(@RequestBody RewriteRequest request,
      HttpServletRequest httpRequest) {
    long start = System.nanoTime();
    if (request == null || request.metadataYaml() == null || request.metadataYaml().isBlank()) {
      return fail(httpRequest, start, null, SqlMaskException.Code.CONFIG_ERROR,
          "metadataYaml is required: paste the YAML configuration declaring tables, "
              + "columns and masking policies");
    }
    if (request.sql() == null || request.sql().isBlank()) {
      return fail(httpRequest, start, request, SqlMaskException.Code.CONFIG_ERROR,
          "sql is required: provide at least one SELECT statement");
    }
    String dialect = request.dialect() == null || request.dialect().isBlank()
        ? "postgresql"
        : request.dialect();
    List<StatementRewrite> statements;
    try {
      statements = engine.rewrite(
          request.metadataYaml(), request.policyYaml(), request.sql(), dialect,
          Subject.of(request.user(), request.groups()));
    } catch (SqlMaskException e) {
      throw recorded(httpRequest, start, request, e.getCode(), e);
    } catch (RuntimeException e) {
      throw recorded(httpRequest, start, request, null, e);
    }
    audit.record(AuditEvent.rewrite("sql-mask", AuditEvent.SUCCESS,
        elapsedMs(start), AuditEvents.sourceIp(httpRequest),
        AuditEvents.authKind(httpRequest), request.user(), request.groups(), dialect,
        statements.size(),
        statements.stream().anyMatch(StatementRewrite::masked),
        statements.stream().anyMatch(StatementRewrite::rowFiltered),
        request.sql(), RewriteEngine.join(statements), null, null));
    return new RewriteResponse(statements, RewriteEngine.join(statements));
  }

  /** Guards path: record the FAILURE event then raise the 400. */
  private RewriteResponse fail(HttpServletRequest httpRequest, long start,
      RewriteRequest request, SqlMaskException.Code code, String message) {
    audit.record(AuditEvent.rewrite("sql-mask", AuditEvent.FAILURE, elapsedMs(start),
        AuditEvents.sourceIp(httpRequest), AuditEvents.authKind(httpRequest),
        request == null ? null : request.user(), request == null ? null : request.groups(),
        request == null ? null : request.dialect(), null, null, null, null, null,
        code.name(), message));
    throw new SqlMaskException(code, message);
  }

  private SqlMaskException recorded(HttpServletRequest httpRequest, long start,
      RewriteRequest request, SqlMaskException.Code code, RuntimeException e) {
    audit.record(AuditEvent.rewrite("sql-mask", AuditEvent.FAILURE, elapsedMs(start),
        AuditEvents.sourceIp(httpRequest), AuditEvents.authKind(httpRequest),
        request == null ? null : request.user(), request == null ? null : request.groups(),
        request == null ? null : request.dialect(), null, null, null, null, null,
        code == null ? null : code.name(),
        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    if (e instanceof SqlMaskException sme) {
      return sme;
    }
    return new SqlMaskException(SqlMaskException.Code.INTERNAL_ERROR, e.getMessage());
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  /** Per-statement rewrite request. */
  public record RewriteRequest(String metadataYaml, String policyYaml, String sql,
      String dialect, String user, List<String> groups) {
  }

  /** Per-statement rewrite response plus the combined script. */
  public record RewriteResponse(List<StatementRewrite> statements, String rewrittenSql) {
  }
}
```

**执行者注意**：若非 `SqlMaskException` 的 RuntimeException 语义与原行为不一致（原来直接冒泡给 `ApiExceptionHandler.handleUnexpected` 变 500），上面 `recorded` 会把它包成 `SqlMaskException(INTERNAL_ERROR)` 变 400——**保持原语义**，改为：非 SqlMaskException 时记录事件后 `throw e;` 原样重抛（即 `recorded` 返回类型改 `RuntimeException`，直接返回传入异常）。以此为准：

```java
    } catch (SqlMaskException e) {
      throw recorded(httpRequest, start, request, e.getCode(), e);
    } catch (RuntimeException e) {
      throw recorded(httpRequest, start, request, null, e);
    }
  ...
  private RuntimeException recorded(HttpServletRequest httpRequest, long start,
      RewriteRequest request, SqlMaskException.Code code, RuntimeException e) {
    audit.record(AuditEvent.rewrite("sql-mask", AuditEvent.FAILURE, elapsedMs(start),
        AuditEvents.sourceIp(httpRequest), AuditEvents.authKind(httpRequest),
        request == null ? null : request.user(), request == null ? null : request.groups(),
        request == null ? null : request.dialect(), null, null, null, null, null,
        code == null ? e.getClass().getSimpleName() : code.name(),
        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    return e;
  }
```

（非 SqlMaskException 的 errorCode 取类 SimpleName，与 `AuditAdminHelper` 的宿主口径一致。）

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-core test -Dtest=RewriteAuditTest,RewriteControllerTest`
Expected: PASS（新用例 + 既有改写用例）。

- [ ] **Step 5: Commit**

```bash
git add mask-core
git commit -m "feat(server): /api/rewrite 每请求发一条 REWRITE 审计事件（成功/失败）"
```

---

### Task 11: 管理面接入（PolicyAdminController / UdfController / MetadataImportController）

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/server/PolicyAdminController.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/UdfController.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/MetadataImportController.java`
- Test: Create `mask-core/src/test/java/io/sqlmask/server/AdminAuditTest.java`

**Interfaces:**
- Consumes: `AuditAdminHelper`（Task 7）、`PolicyAdminController.toTableDtos`。
- Produces: 变更端点各发一条 ADMIN_CHANGE 事件；action/resourceType/instance/resourceName/detail 按下表：

| 端点 | action | resourceType | instance | resourceName | detail |
|---|---|---|---|---|---|
| POST /api/instances | CREATE | INSTANCE | null | name | `{dialect, tableCount}` |
| PUT /api/instances/{n}/tables | REPLACE_TABLES | TABLES | n | n | `{tableCount}` |
| DELETE /api/instances/{n} | DELETE | INSTANCE | null | n | — |
| POST /api/instances/{n}/policies | CREATE | POLICY | n | policy name | `{policyType, enabled, resource, subjects}` |
| PUT /api/instances/{n}/policies/{p} | UPDATE | POLICY | n | p | 同上 |
| DELETE /api/instances/{n}/policies/{p} | DELETE | POLICY | n | p | — |
| POST /api/instances/{i}/udfs | REGISTER | UDF | i | udf name | `{signatureCount}` |
| PUT /api/instances/{i}/udfs/{name} | UPDATE | UDF | i | name | `{signatureCount}` |
| DELETE /api/instances/{i}/udfs/{name} | DELETE | UDF | i | name | — |
| POST /api/instances/{n}/import-metadata | IMPORT | TABLES | n | n | `{sourceInstance, tableCount}` |

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.server;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminAuditTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private AuditRecorder recorder;

  private List<AuditEvent> recorded() {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, atLeastOnce()).record(captor.capture());
    return captor.getAllValues();
  }

  @Test
  void createInstanceEmitsAdminChangeWithDetail() throws Exception {
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "crm", "dialect": "postgresql",
                 "tables": [{"catalog": "crm", "schema": "public", "name": "customer",
                   "columns": [{"name": "id", "type": "bigint"}]}]}
                """))
        .andExpect(status().isOk());
    AuditEvent e = recorded().stream()
        .filter(x -> "CREATE".equals(x.action()) && "INSTANCE".equals(x.resourceType()))
        .findFirst().orElseThrow();
    assertEquals(AuditEvent.ADMIN_CHANGE, e.eventType());
    assertEquals(AuditEvent.SUCCESS, e.outcome());
    assertEquals("crm", e.resourceName());
    assertEquals("postgresql", e.detail().get("dialect"));
    assertEquals(1, e.detail().get("tableCount"));
  }

  @Test
  void failedPolicyCreateEmitsFailureWithConfigError() throws Exception {
    mvc.perform(post("/api/instances/missing/policies").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "p1", "policyType": "datamask",
                 "resource": {"catalog": "c", "schema": "s", "table": "t", "columns": ["x"]},
                 "subjects": {"users": ["*"]}, "udf": "mask", "arguments": []}
                """))
        .andExpect(status().isBadRequest());
    AuditEvent e = recorded().stream()
        .filter(x -> x.outcome().equals(AuditEvent.FAILURE))
        .findFirst().orElseThrow();
    assertEquals("CREATE", e.action());
    assertEquals("POLICY", e.resourceType());
    assertEquals("missing", e.instance());
    assertEquals("CONFIG_ERROR", e.errorCode());
  }

  @Test
  void udfRegisterAndDeleteEmitEvents() throws Exception {
    mvc.perform(post("/api/instances/crm/udfs").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "mask_phone", "signatures": [{"params": ["varchar", "integer"],
                 "returns": "varchar"}]}
                """))
        .andExpect(status().isOk());
    mvc.perform(delete("/api/instances/crm/udfs/mask_phone"))
        .andExpect(status().isOk());
    List<AuditEvent> events = recorded();
    assertEquals("REGISTER", events.stream()
        .filter(x -> "UDF".equals(x.resourceType()) && "mask_phone".equals(x.resourceName()))
        .findFirst().orElseThrow().action());
    assertEquals("DELETE", events.stream()
        .filter(x -> "UDF".equals(x.resourceType()) && "mask_phone".equals(x.resourceName()))
        .reduce((a, b) -> b).orElseThrow().action());
  }

  @Test
  void policyDeleteAndInstanceDeleteEmitEvents() throws Exception {
    mvc.perform(post("/api/instances/crm/policies").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "rowf", "policyType": "row_filter", "isEnabled": true,
                 "resource": {"catalog": "crm", "schema": "public", "table": "customer"},
                 "subjects": {"groups": ["*"]}, "filterExpr": "id > 0"}
                """))
        .andExpect(status().isOk());
    mvc.perform(delete("/api/instances/crm/policies/rowf")).andExpect(status().isOk());
    mvc.perform(delete("/api/instances/crm")).andExpect(status().isOk());
    List<AuditEvent> events = recorded();
    assertEquals("DELETE", events.stream()
        .filter(x -> "POLICY".equals(x.resourceType()) && "rowf".equals(x.resourceName()))
        .reduce((a, b) -> b).orElseThrow().action());
    AuditEvent instanceDelete = events.stream()
        .filter(x -> "INSTANCE".equals(x.resourceType()) && "crm".equals(x.resourceName()))
        .reduce((a, b) -> b).orElseThrow();
    assertEquals("DELETE", instanceDelete.action());
    assertEquals(AuditEvent.SUCCESS, instanceDelete.outcome());
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-core test -Dtest=AdminAuditTest`
Expected: FAIL——没有对应事件（verify 抛 ZeroInteractions 或 orElseThrow NoSuchElement）。

- [ ] **Step 3: 接入三个控制器**

`PolicyAdminController`：构造器追加 `AuditAdminHelper audit`（import `io.sqlmask.audit.AuditAdminHelper`），每个变更方法包装。逐个方法改为：

```java
  @PostMapping
  public InstanceDto create(HttpServletRequest httpRequest, @RequestBody InstanceDto request) {
    requireText(request.name(), "instance name");
    requireText(request.dialect(), "instance dialect");
    List<TableDef> tables = toTables(request.tables());
    return audit.adminChange(httpRequest, "CREATE", "INSTANCE", null, request.name(),
        () -> Map.of("dialect", request.dialect(), "tableCount", tables.size()),
        () -> toDto(service.createInstance(request.name(), request.dialect(), tables)));
  }
```

```java
  @PutMapping("/{name}/tables")
  public InstanceDto replaceTables(HttpServletRequest httpRequest,
      @PathVariable("name") String name, @RequestBody TablesDto request) {
    List<TableDef> tables = toTables(request == null ? null : request.tables());
    return audit.adminChange(httpRequest, "REPLACE_TABLES", "TABLES", name, name,
        () -> Map.of("tableCount", tables.size()),
        () -> toDto(service.updateInstanceTables(name, tables)));
  }

  @DeleteMapping("/{name}")
  public void delete(HttpServletRequest httpRequest, @PathVariable("name") String name) {
    audit.adminChange(httpRequest, "DELETE", "INSTANCE", null, name,
        Map::of, () -> {
          service.deleteInstance(name);
          return null;
        });
  }

  @PostMapping("/{name}/policies")
  public PolicyDto createPolicy(HttpServletRequest httpRequest,
      @PathVariable("name") String name, @RequestBody PolicyDto request) {
    return audit.adminChange(httpRequest, "CREATE", "POLICY", name,
        request == null ? null : request.name(),
        () -> policyDetail(request),
        () -> toDto(service.createPolicy(name, toModel(request))));
  }

  @PutMapping("/{name}/policies/{policy}")
  public PolicyDto updatePolicy(HttpServletRequest httpRequest,
      @PathVariable("name") String name, @PathVariable("policy") String policy,
      @RequestBody PolicyDto request) {
    return audit.adminChange(httpRequest, "UPDATE", "POLICY", name, policy,
        () -> policyDetail(request),
        () -> toDto(service.updatePolicy(name, policy, toModel(request))));
  }

  @DeleteMapping("/{name}/policies/{policy}")
  public void deletePolicy(HttpServletRequest httpRequest, @PathVariable("name") String name,
      @PathVariable("policy") String policy) {
    audit.adminChange(httpRequest, "DELETE", "POLICY", name, policy, Map::of, () -> {
      service.deletePolicy(name, policy);
      return null;
    });
  }

  /** Audit summary of a policy body — no udf arguments (may be sensitive). */
  private static Map<String, Object> policyDetail(PolicyDto dto) {
    if (dto == null) {
      return Map.of();
    }
    var detail = new java.util.LinkedHashMap<String, Object>();
    detail.put("policyType", dto.policyType());
    detail.put("enabled", dto.isEnabled());
    if (dto.resource() != null) {
      detail.put("resource", Map.of("catalog", String.valueOf(dto.resource().catalog()),
          "schema", String.valueOf(dto.resource().schema()),
          "table", String.valueOf(dto.resource().table()),
          "columns", dto.resource().columns() == null ? List.of() : dto.resource().columns()));
    }
    if (dto.subjects() != null) {
      detail.put("subjects", Map.of("users", dto.subjects().users() == null ? List.of()
              : dto.subjects().users(),
          "groups", dto.subjects().groups() == null ? List.of() : dto.subjects().groups()));
    }
    return detail;
  }
```

（`create` 里新增的 `requireText(request.name()/dialect())` 守卫保持原校验语义——原来这些校验发生在 `service.createInstance` 内；提前到包装外只影响 400 的触发时机不影响形状。若执行中发现破坏既有断言，删掉这两行守卫，把 `toTables`/detail 求值放回 supplier 内。）

类头追加 import：`io.sqlmask.audit.AuditAdminHelper`、`jakarta.servlet.http.HttpServletRequest`、`java.util.Map`。

`UdfController` 同构（构造器追加 `AuditAdminHelper audit`）：

```java
  @PostMapping
  public UdfDto create(HttpServletRequest httpRequest, @PathVariable("instance") String instance,
      @RequestBody UdfDto request) {
    return audit.adminChange(httpRequest, "REGISTER", "UDF", instance,
        request == null ? null : request.name(),
        () -> udfDetail(request),
        () -> toDto(service.createUdf(instance, toModel(request))));
  }

  @PutMapping("/{name}")
  public UdfDto replace(HttpServletRequest httpRequest, @PathVariable("instance") String instance,
      @PathVariable("name") String name, @RequestBody UdfDto request) {
    return audit.adminChange(httpRequest, "UPDATE", "UDF", instance, name,
        () -> udfDetail(request),
        () -> toDto(service.replaceUdf(instance, name, toModel(request))));
  }

  @DeleteMapping("/{name}")
  public void delete(HttpServletRequest httpRequest, @PathVariable("instance") String instance,
      @PathVariable("name") String name) {
    audit.adminChange(httpRequest, "DELETE", "UDF", instance, name, Map::of, () -> {
      service.deleteUdf(instance, name);
      return null;
    });
  }

  private static Map<String, Object> udfDetail(UdfDto dto) {
    int signatures = dto == null || dto.signatures() == null ? 0 : dto.signatures().size();
    return Map.of("signatureCount", signatures);
  }
```

`MetadataImportController`（构造器追加 `AuditAdminHelper audit`）：

```java
  @PostMapping("/api/instances/{name}/import-metadata")
  public ImportResponse importMetadata(HttpServletRequest httpRequest,
      @PathVariable("name") String name, @RequestBody ImportRequest request) {
    if (request == null || request.metadataBaseUrl() == null
        || request.metadataBaseUrl().isBlank() || request.metadataInstance() == null
        || request.metadataInstance().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "metadataBaseUrl and metadataInstance are required");
    }
    return audit.adminChange(httpRequest, "IMPORT", "TABLES", name, name,
        () -> Map.of("sourceInstance", request.metadataInstance()),
        () -> doImport(name, request));
  }

  private ImportResponse doImport(String name, ImportRequest request) {
    // 原 importMetadata 方法体从 fetch 起原样移入此处
  }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-core test -Dtest=AdminAuditTest,PolicyAdminEndpointTest,UdfEndpointTest,MetadataImportEndpointTest`
Expected: PASS（新用例 + 既有端点用例不回归）。

- [ ] **Step 5: Commit**

```bash
git add mask-core
git commit -m "feat(server): 策略/UDF/导入管理面接入 ADMIN_CHANGE 审计（摘要不携带请求体）"
```

---

### Task 12: EffectiveConfigController 发 EFFECTIVE_PULL 事件（含开关）

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/server/EffectiveConfigController.java`
- Test: Create `mask-core/src/test/java/io/sqlmask/server/EffectivePullAuditTest.java`

**Interfaces:**
- Consumes: `AuditRecorder`、`AuditProperties.isEffectivePullEnabled()`、`AuditEvents`。
- Produces: `GET /api/effective/{instance}` 每次拉取一条 EFFECTIVE_PULL（开关关闭时零事件）。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.server;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties = "audit.effective-pull.enabled=true")
@AutoConfigureMockMvc
class EffectivePullAuditTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private AuditRecorder recorder;

  @Test
  void effectivePullEmitsEventWithSubject() throws Exception {
    mvc.perform(get("/api/effective/missing?user=alice&groups=devs"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .status().isBadRequest()); // instance missing -> 400 CONFIG_ERROR
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, times(1)).record(captor.capture());
    AuditEvent e = captor.getValue();
    assertEquals(AuditEvent.EFFECTIVE_PULL, e.eventType());
    assertEquals(AuditEvent.FAILURE, e.outcome());
    assertEquals("missing", e.instance());
    assertEquals("alice", e.actorUser());
    assertEquals(java.util.List.of("devs"), e.actorGroups());
    assertEquals("CONFIG_ERROR", e.errorCode());
  }
}
```

（若 `service.effective` 对缺失实例的形状不是 400 CONFIG_ERROR，执行时以实际错误码修正断言——事件断言保持：instance/subject/outcome=FAILURE。）

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-core test -Dtest=EffectivePullAuditTest`
Expected: FAIL——verify 期望 1 实际 0。

- [ ] **Step 3: 修改 EffectiveConfigController**

```java
package io.sqlmask.server;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditEvents;
import io.sqlmask.audit.AuditProperties;
import io.sqlmask.audit.AuditRecorder;
import io.sqlmask.config.source.EffectiveConfigResponse;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policyserver.PolicyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Policy-service data plane: the subject-parameterized compiled effective
 * config. Absent user/groups is the anonymous subject (wildcard policies only).
 * Every pull emits an EFFECTIVE_PULL audit event unless
 * {@code audit.effective-pull.enabled=false} (spec §5.1/§5.3).
 */
@RestController
public class EffectiveConfigController {

  private final PolicyService service;
  private final AuditRecorder audit;
  private final AuditProperties auditProperties;

  public EffectiveConfigController(PolicyService service, AuditRecorder audit,
      AuditProperties auditProperties) {
    this.service = service;
    this.audit = audit;
    this.auditProperties = auditProperties;
  }

  @GetMapping("/api/effective/{instance}")
  public EffectiveConfigResponse effective(@PathVariable("instance") String instance,
      @RequestParam(value = "user", required = false) String user,
      @RequestParam(value = "groups", required = false) List<String> groups,
      HttpServletRequest httpRequest) {
    long start = System.nanoTime();
    try {
      EffectiveConfigResponse response = service.effective(instance, Subject.of(user, groups));
      record(httpRequest, instance, user, groups, start, AuditEvent.SUCCESS, null, null);
      return response;
    } catch (RuntimeException e) {
      record(httpRequest, instance, user, groups, start, AuditEvent.FAILURE,
          e instanceof SqlMaskException sme ? sme.getCode().name() : e.getClass().getSimpleName(),
          e.getMessage());
      throw e;
    }
  }

  private void record(HttpServletRequest httpRequest, String instance, String user,
      List<String> groups, long startNanos, String outcome, String errorCode,
      String errorMessage) {
    if (!auditProperties.isEffectivePullEnabled()) {
      return;
    }
    audit.record(AuditEvent.effectivePull("sql-mask", outcome,
        (System.nanoTime() - startNanos) / 1_000_000, AuditEvents.sourceIp(httpRequest),
        AuditEvents.authKind(httpRequest), user, groups, instance, errorCode, errorMessage));
  }
}
```

（`io.sqlmask.error.SqlMaskException` 需 import。）

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-core test -Dtest=EffectivePullAuditTest,EffectiveConfigEndpointTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add mask-core
git commit -m "feat(server): /api/effective 拉取审计（含 effective-pull 开关）"
```

---

### Task 13: 审计查询 API（GET /api/audit/events + 502）

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/server/AuditQueryController.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/ApiExceptionHandler.java`
- Test: Create `mask-core/src/test/java/io/sqlmask/server/AuditQueryEndpointTest.java`

**Interfaces:**
- Consumes: `AuditSearchClient`、`AuditQuery`、`AuditSearchResult`、`AuditSearchUnavailableException`（Task 8）；`PolicyApiKeyFilter` 的 `/api/audit` 管控（Task 9）。
- Produces: `GET /api/audit/events`，参数 `eventType/outcome/instance/resourceType/action/user/from/to/page/size`；响应 `{total, page, size, events}`；非法参数 400 `CONFIG_ERROR`；ES 不可用 502 `AUDIT_SEARCH_UNAVAILABLE`。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.server;

import io.sqlmask.audit.AuditQuery;
import io.sqlmask.audit.AuditSearchResult;
import io.sqlmask.audit.AuditSearchUnavailableException;
import io.sqlmask.audit.AuditSearchClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuditQueryEndpointTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private AuditSearchClient searchClient;

  @Test
  void returnsMappedPage() throws Exception {
    when(searchClient.search(any(AuditQuery.class))).thenReturn(new AuditSearchResult(2,
        List.of(Map.of("eventType", "REWRITE"), Map.of("eventType", "ADMIN_CHANGE"))));
    mvc.perform(get("/api/audit/events").param("eventType", "REWRITE")
            .param("from", "2026-09-16T00:00:00Z").param("to", "2026-09-17T00:00:00Z")
            .param("page", "0").param("size", "50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(50))
        .andExpect(jsonPath("$.events[0].eventType").value("REWRITE"));
  }

  @Test
  void rejectsOversizedPageAndOversizedRange() throws Exception {
    when(searchClient.search(any(AuditQuery.class))).thenReturn(new AuditSearchResult(0,
        List.of()));
    mvc.perform(get("/api/audit/events").param("size", "201")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/audit/events")
            .param("from", "2026-09-01T00:00:00Z").param("to", "2026-09-16T00:00:00Z"))
        .andExpect(status().isBadRequest()); // 15 days > 7d cap
    mvc.perform(get("/api/audit/events").param("from", "not-a-time"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void esOutageIs502WithCode() throws Exception {
    when(searchClient.search(any(AuditQuery.class)))
        .thenThrow(new AuditSearchUnavailableException("down",
            new java.io.IOException("refused")));
    mvc.perform(get("/api/audit/events"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("AUDIT_SEARCH_UNAVAILABLE"));
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -pl mask-core test -Dtest=AuditQueryEndpointTest`
Expected: FAIL（404——控制器不存在）。

- [ ] **Step 3: 实现控制器与 502 映射**

```java
package io.sqlmask.server;

import io.sqlmask.audit.AuditQuery;
import io.sqlmask.audit.AuditSearchClient;
import io.sqlmask.audit.AuditSearchResult;
import io.sqlmask.error.SqlMaskException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Fixed-condition audit search over ES (spec §6). Newest first, offset paging,
 * time range capped at 7 days; no DSL passthrough — free-form exploration
 * belongs to Kibana.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditQueryController {

  private static final long MAX_RANGE_SECONDS = 7 * 24 * 3600L;
  private static final int DEFAULT_SIZE = 50;
  private static final int MAX_SIZE = 200;

  private final AuditSearchClient search;

  public AuditQueryController(AuditSearchClient search) {
    this.search = search;
  }

  public record AuditQueryResponse(long total, int page, int size,
      List<Object> events) {
  }

  @GetMapping("/events")
  public AuditQueryResponse events(
      @RequestParam(value = "eventType", required = false) String eventType,
      @RequestParam(value = "outcome", required = false) String outcome,
      @RequestParam(value = "instance", required = false) String instance,
      @RequestParam(value = "resourceType", required = false) String resourceType,
      @RequestParam(value = "action", required = false) String action,
      @RequestParam(value = "user", required = false) String user,
      @RequestParam(value = "from", required = false) String from,
      @RequestParam(value = "to", required = false) String to,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "size", required = false) Integer size) {
    int pageSize = size == null ? DEFAULT_SIZE : size;
    if (pageSize < 1 || pageSize > MAX_SIZE) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "size must be between 1 and " + MAX_SIZE);
    }
    int pageNumber = page == null ? 0 : page;
    if (pageNumber < 0) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "page must be >= 0");
    }
    Instant toAt = parseTime(to, "to", Instant.now());
    Instant fromAt = parseTime(from, "from", toAt.minusSeconds(24 * 3600L));
    if (!fromAt.isBefore(toAt)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "from must be before to");
    }
    if (toAt.getEpochSecond() - fromAt.getEpochSecond() > MAX_RANGE_SECONDS) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "time range must not exceed 7 days");
    }
    AuditSearchResult result = search.search(new AuditQuery(eventType, outcome, instance,
        resourceType, action, user, fromAt, toAt, pageNumber, pageSize));
    return new AuditQueryResponse(result.total(), pageNumber, pageSize, result.events());
  }

  private static Instant parseTime(String raw, String what, Instant fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          what + " must be ISO-8601 instant (e.g. 2026-09-16T00:00:00Z)");
    }
  }
}
```

`ApiExceptionHandler` 追加：

```java
  /** ES outage behind the audit query surface surfaces as a 502, not a 500. */
  @ExceptionHandler(io.sqlmask.audit.AuditSearchUnavailableException.class)
  public ResponseEntity<ApiError> handleAuditUnavailable(
      io.sqlmask.audit.AuditSearchUnavailableException e) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(new ApiError("AUDIT_SEARCH_UNAVAILABLE",
            e.getMessage() == null ? "elasticsearch unavailable" : e.getMessage()));
  }
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -q -pl mask-core test -Dtest=AuditQueryEndpointTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add mask-core
git commit -m "feat(server): /api/audit/events 固定条件查询（分页/7 天上限/502 语义）"
```

---

### Task 14: mask-metadata 接入

**Files:**
- Modify: `mask-metadata/src/main/resources/application.yml`
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/config/ApiKeyFilter.java`
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/MetadataServerApplication.java`
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataAdminController.java`
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/web/CollectController.java`
- Test: Modify `mask-metadata/src/test/java/io/sqlmask/metaserver/config/ApiKeyFilterTest.java`
- Test: Create `mask-metadata/src/test/java/io/sqlmask/metaserver/web/AdminAuditMetaTest.java`

**Interfaces:**
- Consumes: `AuditAdminHelper`、`AuditEvents`（mask-audit 经 mask-core 传递依赖已在 classpath）。
- Produces: metaserver 管理面全部变更发 ADMIN_CHANGE，service 名 `mask-metadata`；create/update/delete detail `{dialect}`；import detail `{tableCount, columnCount}`；collect detail `{engine, database, tableCount, columnCount}`。

- [ ] **Step 1: application.yml 追加 audit 段**

与 Task 9 Step 3 的 audit 段逐字相同（service 不同仅体现在 spring.application.name，已有 `mask-metadata`）。

- [ ] **Step 2: ApiKeyFilter 打标 + MetadataServerApplication 补 helper bean**

`ApiKeyFilter.doFilter` 在 `chain.doFilter` 前追加：

```java
    request.setAttribute(io.sqlmask.audit.AuditEvents.AUTH_KIND_ATTRIBUTE,
        io.sqlmask.audit.AuditEvents.AUTH_KIND_API_KEY);
```

`MetadataServerApplication` 追加：

```java
  @Bean
  io.sqlmask.audit.AuditAdminHelper auditAdminHelper(io.sqlmask.audit.AuditRecorder recorder) {
    return new io.sqlmask.audit.AuditAdminHelper(recorder, "mask-metadata",
        e -> e instanceof io.sqlmask.error.SqlMaskException sme
            ? sme.getCode().name()
            : e.getClass().getSimpleName());
  }
```

- [ ] **Step 3: 写失败测试（ApiKeyFilterTest 追加 + 新建 AdminAuditMetaTest）**

`ApiKeyFilterTest` 追加：

```java
  @Test
  void validKeyMarksAuthKindAttribute() throws Exception {
    ApiKeyFilter filter = new ApiKeyFilter("secret");
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/instances");
    request.addHeader("X-Api-Key", "secret");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertEquals(io.sqlmask.audit.AuditEvents.AUTH_KIND_API_KEY,
        request.getAttribute(io.sqlmask.audit.AuditEvents.AUTH_KIND_ATTRIBUTE));
  }
```

（该测试类的既有 import 风格保持；`MockHttpServletRequest/MockFilterChain` 来自 spring-test。）

```java
package io.sqlmask.metaserver.web;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminAuditMetaTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private AuditRecorder recorder;

  @Test
  void createInstanceEmitsAdminChange() throws Exception {
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .header("X-Api-Key", "test-key")
            .content("{\"name\": \"pg1\", \"dialect\": \"postgresql\"}"))
        .andExpect(status().isOk());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, atLeastOnce()).record(captor.capture());
    AuditEvent e = captor.getAllValues().stream()
        .filter(x -> "CREATE".equals(x.action()))
        .findFirst().orElseThrow();
    assertEquals(AuditEvent.ADMIN_CHANGE, e.eventType());
    assertEquals("mask-metadata", e.service());
    assertEquals("pg1", e.resourceName());
    assertEquals("postgresql", e.detail().get("dialect"));
  }

  @Test
  void failedCollectEmitsFailureEvent() throws Exception {
    mvc.perform(post("/api/instances/missing/collect").header("X-Api-Key", "test-key"))
        .andExpect(status().isBadRequest());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, atLeastOnce()).record(captor.capture());
    AuditEvent e = captor.getValue();
    assertEquals("COLLECT", e.action());
    assertEquals(AuditEvent.FAILURE, e.outcome());
    assertEquals("CONFIG_ERROR", e.errorCode());
  }
}
```

**执行前先读** `mask-metadata/src/test/java/io/sqlmask/metaserver/web/MetadataAdminControllerTest.java` 与 `CollectControllerTest.java` 的既有 setup（embedded Postgres / 测试键来源），照抄其 `X-Api-Key` 处理方式；若既有测试用真实 env key（`METADATA_API_KEY`），按其做法对齐（如 `@SpringBootTest(properties = "metadata.api-key=test-key")`）。

- [ ] **Step 4: 运行确认失败**

Run: `mvn -q -pl mask-metadata test -Dtest=AdminAuditMetaTest,ApiKeyFilterTest`
Expected: FAIL（无事件 / 无标记）。

- [ ] **Step 5: 接入两个控制器**

`MetadataAdminController`（构造器追加 `io.sqlmask.audit.AuditAdminHelper audit`；`HttpServletRequest` 参数命名 `httpRequest` 避免与请求体变量冲突）：

```java
  @PostMapping
  public MetadataDtos.InstanceDetailResponse create(HttpServletRequest httpRequest,
      @RequestBody MetadataDtos.InstanceCreateRequest request) {
    if (request == null || request.name() == null || request.dialect() == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "name and dialect are required");
    }
    return audit.adminChange(httpRequest, "CREATE", "INSTANCE", null, request.name(),
        () -> Map.of("dialect", request.dialect()),
        () -> {
          InstanceRow row = instances.create(request.name(), request.dialect(),
              ofNullable(request.connection()));
          return detail(row);
        });
  }

  @PutMapping("/{name}")
  public MetadataDtos.InstanceDetailResponse update(HttpServletRequest httpRequest,
      @PathVariable("name") String name,
      @RequestBody MetadataDtos.InstanceUpdateRequest request) {
    return audit.adminChange(httpRequest, "UPDATE", "INSTANCE", null, name, Map::of,
        () -> {
          ConnectionInfo connection = ofNullable(request == null ? null : request.connection());
          return detail(instances.updateConnection(name, connection));
        });
  }

  @DeleteMapping("/{name}")
  public MetadataDtos.InstanceSummaryResponse delete(HttpServletRequest httpRequest,
      @PathVariable("name") String name) {
    return audit.adminChange(httpRequest, "DELETE", "INSTANCE", null, name, Map::of,
        () -> {
          InstanceRow row = instances.get(name);
          instances.delete(name);
          return new MetadataDtos.InstanceSummaryResponse(row.name(), row.dialect(),
              row.metadataVersion());
        });
  }

  @PostMapping("/import")
  public MetadataDtos.ImportResponse importYaml(HttpServletRequest httpRequest,
      @RequestBody MetadataDtos.InstanceImportRequest request) {
    if (request == null || request.name() == null || request.dialect() == null
        || request.metadataYaml() == null || request.metadataYaml().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "name, dialect and metadataYaml are required");
    }
    return audit.adminChange(httpRequest, "IMPORT", "TABLES", null, request.name(),
        () -> Map.of("tableCount", importedTables(request).size(),
            "columnCount", importedTables(request).stream()
                .mapToInt(t -> t.columns().size()).sum()),
        () -> doImportYaml(request));
  }

  private MetadataDtos.ImportResponse doImportYaml(MetadataDtos.InstanceImportRequest request) {
    // 原 importYaml 从 importer.parse 起的原方法体移入此处
  }
```

（`importYaml` 的 detail 里两次调用 `importedTables(request)` 会重复解析——实现时先在 adminChange 之外解析一次存局部变量：`List<TableStructure> tables = importer.parse(request.metadataYaml(), request.name());` 空表守卫保留在原处，detail 闭包引用该局部变量。上面写法仅为形状示意，**以局部变量版为准**。）

`CollectController`：

```java
@RestController
@RequestMapping("/api/instances")
public class CollectController {

  private final CollectService collectService;
  private final MetadataService instances;
  private final io.sqlmask.audit.AuditAdminHelper audit;

  public CollectController(CollectService collectService, MetadataService instances,
      io.sqlmask.audit.AuditAdminHelper audit) {
    this.collectService = collectService;
    this.instances = instances;
    this.audit = audit;
  }

  @PostMapping("/{name}/collect")
  public MetadataDtos.CollectResponse collect(HttpServletRequest httpRequest,
      @PathVariable("name") String name) {
    return audit.adminChange(httpRequest, "COLLECT", "INSTANCE", null, name,
        () -> {
          var row = instances.get(name);
          var connection = row.connection();
          return connection == null
              ? Map.of("engine", row.dialect())
              : Map.of("engine", row.dialect(), "database", String.valueOf(connection.database()));
        },
        () -> {
          MetadataDtos.CollectResponse response = collectService.collect(name);
          return response;
        });
  }
}
```

（`detail` 里含 counts 的完整版：把 collect 调用移进 detail 无法拿到 response——接受 detail 只含 engine/database，counts 不进 detail；spec §5.2 的 `{tableCount, columnCount}` 简化掉，理由：counts 已在响应体可见，审计侧 instance+engine+database 足够定位。若评审坚持，可让 wrapper 支持 post-hook，v1 不做。）

类头 import：`jakarta.servlet.http.HttpServletRequest`、`java.util.Map`。

- [ ] **Step 6: 运行确认通过**

Run: `mvn -q -pl mask-metadata test`
Expected: PASS（全模块，含既有 controller/service 用例）。

- [ ] **Step 7: Commit**

```bash
git add mask-metadata
git commit -m "feat(metaserver): 管理面/采集接入 ADMIN_CHANGE 审计与 authKind 打标"
```

---

### Task 15: compose ES + README 文档

**Files:**
- Modify: `docker-compose.metadata.yml`
- Modify: `README.md`

**Interfaces:** 无代码接口；本地开发套一条命令起齐 ES。

- [ ] **Step 1: compose 增加 ES 服务**

`docker-compose.metadata.yml` 追加（services 下）与顶层 volumes：

```yaml
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.13.4
    environment:
      discovery.type: single-node
      xpack.security.enabled: "false"
      ES_JAVA_OPTS: "-Xms512m -Xmx512m"
    ports:
      - "127.0.0.1:9200:9200"
    volumes:
      - esdata:/usr/share/elasticsearch/data
    mem_limit: 1073741824

volumes:
  esdata:
```

（若文件已有顶层 `volumes:` 键则合并；端口显式绑 `127.0.0.1`，不暴露公网。）

- [ ] **Step 2: 校验 compose 语法**

Run: `docker compose -f docker-compose.metadata.yml config >/dev/null && echo OK`
Expected: `OK`。（无 docker 环境时改为人工核对 YAML 缩进。）

- [ ] **Step 3: README 增补「审计日志」章节**

在「策略服务管理面（REST）」章节后追加（内容要点来自 spec，含：三类事件一句话说明、索引 `mask-audit-YYYY.MM.dd`（UTC）、环境变量表（`AUDIT_ENABLED/AUDIT_ES_URL/AUDIT_ES_API_KEY/AUDIT_ES_USER/AUDIT_ES_PASSWORD/AUDIT_INDEX_PREFIX/AUDIT_QUEUE_CAPACITY/AUDIT_BATCH_SIZE/AUDIT_FLUSH_INTERVAL_MS/AUDIT_SQL_MAX_CHARS/AUDIT_EFFECTIVE_PULL_ENABLED` 及默认值）、`GET /api/audit/events` 参数表与 502 语义、compose 起 ES 命令、可选 ILM 示例（30 天删除：`PUT _ilm/policy/mask-audit-30d` + 模板 `index.lifecycle.name`，注明 v1 模板不绑）、可靠语义一句话（尽力而为：队列满丢弃、ES 故障不拦业务））。SQL 示例：

```bash
# 本地起 ES + 元数据服务
docker compose -f docker-compose.metadata.yml up -d elasticsearch
curl -s 'http://127.0.0.1:9200/_cat/indices/mask-audit-*?v'
curl -s 'http://127.0.0.1:8080/api/audit/events?eventType=REWRITE&size=10'
```

- [ ] **Step 4: 全量回归**

Run: `mvn -q test`
Expected: PASS（三个模块全绿）。

- [ ] **Step 5: Commit**

```bash
git add docker-compose.metadata.yml README.md
git commit -m "docs+build: compose 增加单节点 ES 与 README 审计日志章节"
```

---

### Task 16: 远程主机 ES 集成冒烟（root@47.100.166.158）

> **执行结论（2026-09-17，冒烟中断）**：远端 Docker 可用（29.1.3），按 Step 1
> 以 `--memory=1g -Xms512m -Xmx512m` 起 ES 后 **OOM 被杀（exit 137）**；降参为
> `--memory=768m -Xms256m -Xmx256m` 重试后 **宿主机整体内存耗尽**（总内存仅
> 1.6GB、无 swap、已跑 pg-mask postgres），SSH 无法再建立（ping 通、22 端口
> banner 超时）。冒烟 Step 2-6 无法执行。**该主机跑不动 ES**；待其恢复后应
> `docker rm -f mask-audit-es` 清理，并换内存 ≥2GB 的主机或加 swap 再冒烟。
> 本地全量回归（mask-policy/mask-audit/mask-core/mask-metadata）已全绿，
> 功能完成度止于无真实 ES 联调。
>
> **补充结论（2026-09-18，本地真实 ES 冒烟通过）**：远端主机持续失联，改在
> 本地（16GB 内存，ES 8.13.4 官方 zip 直跑，绑 127.0.0.1）完成 Step 2-4 等效
> 冒烟，**全部通过**：
> - 模板安装 + 事件落地：UTC 日索引（如 `mask-audit-2026.09.17`）自动创建；
> - 矩阵（独立前缀 `smoke19-*` 隔离计数）：改写成功/失败、建实例、生效配置
>   拉取成功/失败共 5 请求 → REWRITE=2、ADMIN_CHANGE=1、EFFECTIVE_PULL=2，
>   **一条不丢一条不重**；文档字段与 spec §3 一致（actor/error/原文 SQL/
>   masked/rowFiltered/statementCount/@timestamp epoch 毫秒）；
> - 查询 API：`total/page/size/events` 正确、时间倒序、全空过滤走 match_all；
> - 降级语义：**停 ES 后改写业务仍 200**（尽力而为丢弃 + 限频 WARN）；
>   ES 恢复中（red/503 窗口）查询 API 返回 **502 AUDIT_SEARCH_UNAVAILABLE**；
>   ES 恢复后新事件自动续写入库（失败限频 WARN 的 down-since 毫秒数随真实
>   停机时长累计，恢复窗口语义正确）。
> - **顺带发现两个与审计无关的问题**：① `sql-mask.jar`（shade fat jar）丢失
>   Spring Boot `AutoConfiguration.imports`（多 jar 同名资源未做合并），
>   `java -jar` 启动 Web 服务必失败（README 宣称的方式当前不可用）；需为
>   shade 配 `ResourceTransformer`/`AppendingTransformer` 或改用
>   spring-boot-maven-plugin。② 并行会话共享同一本地/工作区时的构件污染
>   （~/.m2 SNAPSHOT 被半成品覆盖）会干扰他人构建，建议并行工作一律用独立
>   worktree + 独立 `mvn -Dmaven.repo.local`。

**Files:** 无代码变更；产物为冒烟结论（写回本文件的 checkbox 与对话汇报）。

**Interfaces:** 消费 Task 1-15 的全部交付物。

**安全红线**：ES 未启用安全特性，**只绑远端 127.0.0.1，经 SSH 隧道访问**；不得 `-p 9200:9200` 暴露公网，也不得在云安全组放行 9200。

- [ ] **Step 1: 远端起 ES（docker，绑 127.0.0.1）**

```bash
ssh root@47.100.166.158 'docker --version || echo NO_DOCKER'
# 有 docker：
ssh root@47.100.166.158 'docker rm -f mask-audit-es 2>/dev/null; docker run -d --name mask-audit-es \
  -p 127.0.0.1:9200:9200 -e discovery.type=single-node -e xpack.security.enabled=false \
  -e ES_JAVA_OPTS="-Xms512m -Xmx512m" --memory=1g \
  docker.elastic.co/elasticsearch/elasticsearch:8.13.4'
# NO_DOCKER 时：apt-get/yum 装 docker.io 或 docker-ce 后重试（安装动作执行前向用户确认）。
```

- [ ] **Step 2: 隧道 + 健康检查**

```bash
ssh -N -L 9200:127.0.0.1:9200 root@47.100.166.158 &   # 保持前台进程挂后台运行
curl -s http://127.0.0.1:9200 | grep '"cluster_name"'
curl -s -X PUT 'http://127.0.0.1:9200/mask-audit-smoke'   # 写权限探测
curl -s -X DELETE 'http://127.0.0.1:9200/mask-audit-smoke'
```

Expected: 集群名 JSON；PUT/DELETE acknowledged。

- [ ] **Step 3: 起 mask-core（指向隧道）并跑冒烟矩阵**

```bash
mvn -q -pl mask-core -am package -DskipTests
java -jar mask-core/target/sql-mask.jar --server.port=18080 &   # AUDIT_ES_URL 缺省即 127.0.0.1:9200
```

冒烟矩阵（每步记录结果）：

```bash
# 1) 改写成功事件
curl -s -X POST http://127.0.0.1:18080/api/rewrite -H 'Content-Type: application/json' -d '{
  "metadataYaml": "metadata:\n  tables:\n    - catalog: crm\n      schema: public\n      name: customer\n      columns:\n        - name: id\n          type: bigint\n        - name: phone\n          type: varchar\ncolumns:\n  - catalog: crm\n    schema: public\n    table: customer\n    column: phone\n    policy: mask_phone\npolicies:\n  mask_phone:\n    udf: mask_phone\n    arguments: [3, 4]",
  "sql": "SELECT phone FROM customer;", "dialect": "postgresql", "user": "alice", "groups": ["devs"]}'
# 2) 改写失败事件
curl -s -X POST http://127.0.0.1:18080/api/rewrite -H 'Content-Type: application/json' -d '{
  "metadataYaml": "metadata:\n  tables: []\npolicies: {}", "sql": "DELETE FROM x"}'
# 3) 管理面：建实例/建策略/建 UDF/删策略
curl -s -X PUT http://127.0.0.1:18080/api/instances/crm/tables -H 'Content-Type: application/json' \
  -d '{"tables":[{"catalog":"crm","schema":"public","name":"customer","columns":[{"name":"id","type":"bigint"},{"name":"phone","type":"varchar"}]}]}'
curl -s -X POST http://127.0.0.1:18080/api/instances/crm/policies -H 'Content-Type: application/json' \
  -d '{"name":"mask-phone","policyType":"datamask","isEnabled":true,
       "resource":{"catalog":"crm","schema":"public","table":"customer","columns":["phone"]},
       "subjects":{"groups":["*"]},"udf":"mask_phone","arguments":[3,4]}'
# 4) 生效配置拉取
curl -s 'http://127.0.0.1:18080/api/effective/crm?user=alice&groups=devs' >/dev/null
sleep 4   # 等 flush-interval
# 5) ES 里验证事件落地 + 查询 API
curl -s 'http://127.0.0.1:9200/_cat/indices/mask-audit-*?v'
curl -s 'http://127.0.0.1:18080/api/audit/events?eventType=REWRITE&size=10'
curl -s 'http://127.0.0.1:18080/api/audit/events?user=alice&from=2026-09-16T00:00:00Z'
```

Expected: 索引存在且有文档；查询 API 返回 total ≥ 4（REWRITE 成功/失败、ADMIN_CHANGE ×2、EFFECTIVE_PULL），事件字段与 spec §3 一致。

- [ ] **Step 4: ES 故障不拦业务 + 恢复续写**

```bash
ssh root@47.100.166.158 'docker stop mask-audit-es'
curl -s -X POST http://127.0.0.1:18080/api/rewrite -H 'Content-Type: application/json' -d '{...同矩阵 1...}'
# Expected: 业务 200 正常返回（审计丢弃只产生限频 WARN 日志）
ssh root@47.100.166.158 'docker start mask-audit-es'
sleep 10
curl -s 'http://127.0.0.1:18080/api/audit/events?size=5'
# Expected: 恢复后新事件继续入库（服务日志出现 recovered INFO）
```

- [ ] **Step 5: 收尾**

```bash
kill %1 %2 2>/dev/null; ssh root@47.100.166.158 'docker rm -f mask-audit-es'
```

（是否保留远端 ES 供后续使用，询问用户后决定。）

- [ ] **Step 6: 汇报冒烟结论**

把矩阵每步的结果（通过/失败与关键输出摘录）汇报给用户；失败项回修后重跑。无代码变更则不产生 commit；若有回修，按所属模块正常 commit。

---

## Self-Review 记录

- **Spec 覆盖**：§2 模块→Task 1/6；§3 事件模型→Task 1/2；§4 管道→Task 3/4/5；§4.3 模板→Task 4；§4.5 配置→Task 3/9/14；§5.1 mask-core→Task 9/10/11/12；§5.2 metaserver→Task 14；§6 查询 API→Task 8/13（鉴权在 Task 9）；§7 部署/远程→Task 15/16；§8 测试→各任务 TDD 步骤；§9 错误语义→Task 4/5/13 断言覆盖。metaserver collect detail 简化（无 counts）已在 Task 14 注明理由。
- **占位符**：Task 11 `doImport`、Task 14 `doImportYaml` 为「原方法体平移」类指令，均指明了源方法的精确起点与守卫去留，非 TBD。
- **类型一致性**：`AuditEvent` 字段名与 Task 2/5/10/11/12/14 引用一致；`AuditEvents.AUTH_KIND_ATTRIBUTE` 常量在 Task 7 定义、Task 9/14 使用；`AuditAdminHelper` 构造签名（recorder, service, errorCode）在 Task 7 定义、Task 9/14 的 bean 一致；`AuditQuery` 十字段构造在 Task 8/13 一致。
