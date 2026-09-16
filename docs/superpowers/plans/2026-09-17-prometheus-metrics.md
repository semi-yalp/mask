# Prometheus 指标实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** mask-core 与 mask-metadata 暴露 `/actuator/prometheus`，并落地 spec §3.1–3.4 的业务指标（改写/管理面/生效配置/元数据采集）与 §5/§6 的配置和部署物。

**Architecture:** Spring Boot Actuator + Micrometer Prometheus registry（版本走 spring-boot-dependencies 3.3.5 BOM，pom 不写版本号）。业务指标埋点就地：每个服务建 2-3 个小的 `@Component` 指标门面类，Controller/Service 注入调用；不新建 Maven 模块。

**Tech Stack:** Java（Spring Boot 3.3.5）、Micrometer、JUnit 5、SimpleMeterRegistry（单测）+ @SpringBootTest/MockMvc（端到端）。

**Spec:** `docs/superpowers/specs/2026-09-17-prometheus-metrics-design.md`（计划从 spec 出发，执行者需同读两者）

**范围外（本计划不做）：** spec §3.5 审计管道六个指标——随 mask-audit 模块的实施计划落地，本计划不创建 `mask-audit`、不引用其任何类。

## Global Constraints

- 指标名用 Micrometer 点分小写；单测断言用点分名（如 `sqlmask.rewrite.requests`），Prometheus 呈现名（`sqlmask_rewrite_requests_total`）只在 `/actuator/prometheus` 冒烟里断言。
- label 取值纪律（spec §3.1）：`dialect` 小写化后必须命中 `postgresql`/`mysql`/`trino`，否则记 `invalid`；生效配置的 `instance` 未命中已知实例记 `(not_found)`。
- 失败路径计数后**原样重抛异常**，不改任何错误语义与 HTTP 状态码。
- GET/读端点不埋管理面指标（通用 `http.server.requests` 已覆盖）。
- 两个 pom 新增依赖一律不写 `<version>`（BOM 管理）。
- Java 代码风格：2 空格缩进，与现有代码一致。
- 全部 label 键用连字符转下划线前的 Micrometer 形式：`dialect`、`outcome`、`masked`、`row_filtered`、`code`、`resource_type`、`action`、`instance`、`engine`、`reason`（`reason` 仅审计模块用，本计划不涉及）。

---

### Task 1: Actuator 依赖与 Prometheus 端点（两服务）

**Files:**
- Modify: `mask-core/pom.xml`（dependencies 段）
- Modify: `mask-metadata/pom.xml`（dependencies 段）
- Modify: `mask-core/src/main/resources/application.yml`
- Modify: `mask-metadata/src/main/resources/application.yml`
- Test: `mask-core/src/test/java/io/sqlmask/server/MetricsEndpointSmokeTest.java`（新建）
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/web/MetricsEndpointSmokeTest.java`（新建）

**Interfaces:**
- Consumes: 无（首任务）。
- Produces: 两服务 `MeterRegistry`（PrometheusMeterRegistry）可注入；`/actuator/prometheus` 端点带 `application="sql-mask"` / `application="mask-metadata"` 公共 tag。后续所有任务依赖注入 `MeterRegistry`。

- [ ] **Step 1: 两服务 pom 加依赖**

在 `mask-core/pom.xml` 与 `mask-metadata/pom.xml` 的 `<dependencies>` 里各加（紧挨 `spring-boot-starter-web`/`spring-boot-starter-jdbc` 等既有 starter 的位置）：

```xml
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
```

- [ ] **Step 2: 两服务 application.yml 加配置**

`mask-core/src/main/resources/application.yml` 全文改为（`spring` 段保持原样，新增 `management` 段）：

```yaml
server:
  port: 8080

spring:
  application:
    name: sql-mask

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  observations:
    key:
      values:
        application: ${spring.application.name}
```

`mask-metadata/src/main/resources/application.yml` 同样**保留全部现有内容**（datasource、sql.init、metadata.api-key 都不动），只在文件末尾追加同一 `management:` 段（`spring.application.name` 已是 `mask-metadata`）。

- [ ] **Step 3: 写两服务的端点冒烟测试（先失败）**

`mask-core/src/test/java/io/sqlmask/server/MetricsEndpointSmokeTest.java`：

```java
package io.sqlmask.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MetricsEndpointSmokeTest {

  @Autowired
  private MockMvc mvc;

  @Test
  void exposesPrometheusScrapeEndpointWithCommonTag() throws Exception {
    mvc.perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("jvm_memory_used_bytes")))
        .andExpect(content().string(containsString("application=\"sql-mask\"")));
  }
}
```

`mask-metadata/src/test/java/io/sqlmask/metaserver/web/MetricsEndpointSmokeTest.java`（properties 对齐 `CollectControllerTest` 的既有写法）：

```java
package io.sqlmask.metaserver.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"metadata.api-key=test-key", "spring.sql.init.mode=never"})
@AutoConfigureMockMvc
class MetricsEndpointSmokeTest {

  @Autowired
  private MockMvc mvc;

  @Test
  void exposesPrometheusScrapeEndpointWithCommonTag() throws Exception {
    mvc.perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("jvm_memory_used_bytes")))
        .andExpect(content().string(containsString("application=\"mask-metadata\"")));
  }
}
```

- [ ] **Step 4: 跑测试验证通过（此任务是配置类任务：先跑确认红的原因是 404，加完依赖与配置后转绿）**

先跑（预期 FAIL：`/actuator/prometheus` 404）：

```bash
mvn -q -pl mask-core -am test -Dtest=MetricsEndpointSmokeTest
```

做完 Step 1-2 后再跑（预期 PASS）。mask-metadata 同理：

```bash
mvn -q -pl mask-metadata -am test -Dtest=MetricsEndpointSmokeTest
```

- [ ] **Step 5: Commit**

```bash
git add mask-core/pom.xml mask-metadata/pom.xml mask-core/src/main/resources/application.yml mask-metadata/src/main/resources/application.yml mask-core/src/test/java/io/sqlmask/server/MetricsEndpointSmokeTest.java mask-metadata/src/test/java/io/sqlmask/metaserver/web/MetricsEndpointSmokeTest.java
git commit -m "feat(metrics): 两服务接入 actuator + Prometheus registry，暴露 /actuator/prometheus"
```

---

### Task 2: 数据面改写四件套（mask-core）

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/server/RewriteMetrics.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/RewriteController.java`
- Test: `mask-core/src/test/java/io/sqlmask/server/RewriteMetricsTest.java`（新建，纯单测）
- Test: `mask-core/src/test/java/io/sqlmask/server/RewriteMetricsEndpointTest.java`（新建，@SpringBootTest）

**Interfaces:**
- Consumes: Task 1 的 `MeterRegistry` 注入；`RewriteEngine.StatementRewrite(ordinal, originalSql, rewrittenSql, masked, rowFiltered)`；`SqlMaskException.getCode()` 返回 `SqlMaskException.Code`。
- Produces: `RewriteMetrics`（`success(String, List<StatementRewrite>)`、`failure(String, String)`、`duration(String, long)`、包内静态 `normalize(String)`）；指标 `sqlmask.rewrite.requests{dialect,outcome,masked,row_filtered}`、`sqlmask.rewrite.failures{dialect,code}`、`sqlmask.rewrite.duration{dialect}`（SLO 桶）、`sqlmask.rewrite.statements{dialect}`。

- [ ] **Step 1: 写失败的单元测试**

`mask-core/src/test/java/io/sqlmask/server/RewriteMetricsTest.java`：

```java
package io.sqlmask.server;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RewriteMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final RewriteMetrics metrics = new RewriteMetrics(registry);

  @Test
  void successCountsRequestsStatementsAndFlags() {
    metrics.success("PostgreSQL", List.of(
        new StatementRewrite(0, "SELECT phone FROM c", "SELECT mask(phone) FROM c", true, false),
        new StatementRewrite(1, "SELECT 1", "SELECT 1", false, true)));

    assertThat(counter("sqlmask.rewrite.requests", "dialect", "postgresql",
        "outcome", "SUCCESS", "masked", "true", "row_filtered", "true")).isEqualTo(1.0);
    assertThat(counter("sqlmask.rewrite.statements", "dialect", "postgresql")).isEqualTo(2.0);
  }

  @Test
  void failureRecordsOutcomeAndClosedEnumCode() {
    metrics.failure("mysql", "PARSE_ERROR");

    assertThat(counter("sqlmask.rewrite.requests", "dialect", "mysql",
        "outcome", "FAILURE", "masked", "false", "row_filtered", "false")).isEqualTo(1.0);
    assertThat(counter("sqlmask.rewrite.failures", "dialect", "mysql", "code", "PARSE_ERROR"))
        .isEqualTo(1.0);
  }

  @Test
  void unknownDialectGoesToInvalidSentinel() {
    metrics.success("JUNK", List.of());
    metrics.failure(null, "CONFIG_ERROR");

    assertThat(counter("sqlmask.rewrite.requests", "dialect", "invalid",
        "outcome", "SUCCESS", "masked", "false", "row_filtered", "false")).isEqualTo(1.0);
    assertThat(counter("sqlmask.rewrite.failures", "dialect", "invalid", "code", "CONFIG_ERROR"))
        .isEqualTo(1.0);
    assertThat(registry.get("sqlmask.rewrite.requests").meters().size()).isEqualTo(2);
  }

  @Test
  void durationRecordsWithSloBuckets() {
    metrics.duration("trino", System.nanoTime() - 5_000_000);

    assertThat(registry.get("sqlmask.rewrite.duration").tag("dialect", "trino")
        .timer().count()).isEqualTo(1L);
    // 10s 是最大 SLO 桶，任何成功记录的耗时应落入其中
    assertThat(registry.get("sqlmask.rewrite.duration").tag("dialect", "trino")
        .timer().takeSnapshot().histogramCountForValue(10.0)).isEqualTo(1.0);
  }

  @Test
  void normalizeIsLowercaseAllowlist() {
    assertThat(RewriteMetrics.normalize("PostgreSQL")).isEqualTo("postgresql");
    assertThat(RewriteMetrics.normalize("trino")).isEqualTo("trino");
    assertThat(RewriteMetrics.normalize(" ")).isEqualTo("invalid");
    assertThat(RewriteMetrics.normalize("pg")).isEqualTo("invalid");
  }

  private double counter(String name, String... tags) {
    return registry.get(name).tags(tags).counter().count();
  }
}
```

注意：`metrics.success("JUNK", List.of())` 会生成一条 `masked=false,row_filtered=false` 的 SUCCESS 序列——空语句列表按"未脱敏"处理，这是有意的（无语句即无脱敏）。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -q -pl mask-core -am test -Dtest=RewriteMetricsTest
```

预期：编译失败，`RewriteMetrics` 不存在。

- [ ] **Step 3: 实现 RewriteMetrics**

`mask-core/src/main/java/io/sqlmask/server/RewriteMetrics.java`：

```java
package io.sqlmask.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Data-plane rewrite metrics (spec 2026-09-17-prometheus-metrics §3.1). Label
 * values from request input pass through {@link #normalize} first so the
 * series cardinality is bounded by code, not by callers.
 */
@Component
public class RewriteMetrics {

  private static final Set<String> DIALECTS = Set.of("postgresql", "mysql", "trino");

  private final MeterRegistry registry;

  public RewriteMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void success(String dialect, List<StatementRewrite> statements) {
    String d = normalize(dialect);
    registry.counter("sqlmask.rewrite.requests", "dialect", d, "outcome", "SUCCESS",
            "masked", String.valueOf(any(statements, StatementRewrite::masked)),
            "row_filtered", String.valueOf(any(statements, StatementRewrite::rowFiltered)))
        .increment();
    registry.counter("sqlmask.rewrite.statements", "dialect", d).increment(statements.size());
  }

  public void failure(String dialect, String code) {
    String d = normalize(dialect);
    registry.counter("sqlmask.rewrite.requests", "dialect", d, "outcome", "FAILURE",
        "masked", "false", "row_filtered", "false").increment();
    registry.counter("sqlmask.rewrite.failures", "dialect", d, "code", code).increment();
  }

  /** Called from a finally block with the nanoTime captured before the rewrite. */
  public void duration(String dialect, long startNanos) {
    Timer.builder("sqlmask.rewrite.duration")
        .tag("dialect", normalize(dialect))
        .serviceLevelObjectives(sloSeconds())
        .register(registry)
        .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
  }

  static String normalize(String raw) {
    if (raw == null) {
      return "invalid";
    }
    String d = raw.trim().toLowerCase(Locale.ROOT);
    return DIALECTS.contains(d) ? d : "invalid";
  }

  /** SLO bucket bounds in seconds, spec §5 (1ms–10s). Micrometer 的 double 重载按秒解释。 */
  static double[] sloSeconds() {
    return new double[] {0.001, 0.005, 0.010, 0.025, 0.050, 0.100, 0.250, 0.500,
        1.0, 5.0, 10.0};
  }

  private static boolean any(List<StatementRewrite> statements,
      java.util.function.Predicate<StatementRewrite> flag) {
    return statements.stream().anyMatch(flag);
  }
}
```

`sloSeconds()` 同时供 Task 3 `AdminMetrics` 与 Task 4 `EffectiveMetrics` 引用（`RewriteMetrics.sloSeconds()`，包内可见）。

- [ ] **Step 4: 跑单测确认通过**

```bash
mvn -q -pl mask-core -am test -Dtest=RewriteMetricsTest
```

预期：PASS（6 个用例）。

- [ ] **Step 5: 改 RewriteController 埋点**

`RewriteController` 注入 `RewriteMetrics` 并把 `rewrite` 方法整体包进计时/计数（守卫异常也计数；异常原样重抛）：

```java
  private final RewriteEngine engine;
  private final RewriteMetrics metrics;

  public RewriteController(RewriteEngine engine, RewriteMetrics metrics) {
    this.engine = engine;
    this.metrics = metrics;
  }

  @PostMapping("/rewrite")
  public RewriteResponse rewrite(@RequestBody RewriteRequest request) {
    String dialect = request == null || request.dialect() == null || request.dialect().isBlank()
        ? "postgresql"
        : request.dialect();
    long start = System.nanoTime();
    try {
      if (request == null || request.metadataYaml() == null || request.metadataYaml().isBlank()) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "metadataYaml is required: paste the YAML configuration declaring tables, "
                + "columns and masking policies");
      }
      if (request.sql() == null || request.sql().isBlank()) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "sql is required: provide at least one SELECT statement");
      }
      List<StatementRewrite> statements = engine.rewrite(
          request.metadataYaml(), request.policyYaml(), request.sql(), dialect,
          Subject.of(request.user(), request.groups()));
      metrics.success(dialect, statements);
      return new RewriteResponse(statements, RewriteEngine.join(statements));
    } catch (SqlMaskException e) {
      metrics.failure(dialect, e.getCode().name());
      throw e;
    } catch (RuntimeException e) {
      metrics.failure(dialect, "REWRITE_ERROR");
      throw e;
    } finally {
      metrics.duration(dialect, start);
    }
  }
```

import 需新增：`io.sqlmask.error.SqlMaskException`（已有）、`io.sqlmask.rewrite.RewriteEngine.StatementRewrite`（已有，`List<StatementRewrite>` 已 import）。

- [ ] **Step 6: 写端到端测试**

`mask-core/src/test/java/io/sqlmask/server/RewriteMetricsEndpointTest.java`：

```java
package io.sqlmask.server;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RewriteMetricsEndpointTest {

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

  @Autowired
  private MockMvc mvc;

  @Autowired
  private MeterRegistry registry;

  @Test
  void successfulRewriteIncrementsSuccessCounter() throws Exception {
    double before = counter("SUCCESS");
    mvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON)
            .content(body("postgresql")))
        .andExpect(status().isOk());
    assertThat(counter("SUCCESS")).isEqualTo(before + 1);
  }

  @Test
  void failedRewriteIncrementsFailureCounterWithCode() throws Exception {
    double before = failures("PARSE_ERROR");
    mvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON)
            .content(new ObjectMapper().writeValueAsString(Map.of(
                "metadataYaml", YAML, "sql", "SELEKT nonsense", "dialect", "postgresql"))))
        .andExpect(status().isBadRequest());
    assertThat(failures("PARSE_ERROR")).isEqualTo(before + 1);
  }

  @Test
  void unknownDialectCountsUnderInvalidSentinel() throws Exception {
    double before = registry.counter("sqlmask.rewrite.requests", "dialect", "invalid",
        "outcome", "FAILURE", "masked", "false", "row_filtered", "false").count();
    mvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON)
            .content(new ObjectMapper().writeValueAsString(Map.of(
                "metadataYaml", YAML, "sql", "SELECT 1", "dialect", "JUNK"))))
        .andExpect(status().is4xxClientError());
    assertThat(registry.counter("sqlmask.rewrite.requests", "dialect", "invalid",
        "outcome", "FAILURE", "masked", "false", "row_filtered", "false").count())
        .isEqualTo(before + 1);
  }

  private double counter(String outcome) {
    // registry.counter 对不存在的序列返回零值计数器——用于"动作前"读数不会抛异常
    return registry.counter("sqlmask.rewrite.requests", "dialect", "postgresql",
        "outcome", outcome, "masked", "true", "row_filtered", "false").count();
  }

  private double failures(String code) {
    return registry.counter("sqlmask.rewrite.failures",
        "dialect", "postgresql", "code", code).count();
  }

  private static String body(String dialect) throws Exception {
    return new ObjectMapper().writeValueAsString(Map.of(
        "metadataYaml", YAML, "sql", "SELECT phone FROM customer;", "dialect", dialect));
  }
}
```

说明：`SELEKT` 用例的预期错误码若实测不是 `PARSE_ERROR`（可能是 `CONFIG_ERROR`），以实际抛出的 `SqlMaskException.Code` 为准调整断言里的 code——断言的是"失败计数进对应 code 序列"，不是特定 code 值。

- [ ] **Step 7: 跑新测试与既有 RewriteControllerTest**

```bash
mvn -q -pl mask-core -am test -Dtest='RewriteMetricsTest,RewriteMetricsEndpointTest,RewriteControllerTest'
```

预期：全部 PASS（既有测试不受影响——构造器注入由 Spring 自动满足）。

- [ ] **Step 8: Commit**

```bash
git add mask-core/src/main/java/io/sqlmask/server/RewriteMetrics.java mask-core/src/main/java/io/sqlmask/server/RewriteController.java mask-core/src/test/java/io/sqlmask/server/RewriteMetricsTest.java mask-core/src/test/java/io/sqlmask/server/RewriteMetricsEndpointTest.java
git commit -m "feat(metrics): 数据面改写四件套（requests/failures/duration/statements，dialect 白名单纪律）"
```

---

### Task 3: 管理面指标（mask-core）

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/server/AdminMetrics.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/PolicyAdminController.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/UdfController.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/MetadataImportController.java`
- Test: `mask-core/src/test/java/io/sqlmask/server/AdminMetricsTest.java`（新建）
- Test: `mask-core/src/test/java/io/sqlmask/server/AdminMetricsEndpointTest.java`（新建）

**Interfaces:**
- Consumes: Task 1 的 `MeterRegistry`。
- Produces: `AdminMetrics.record(String resourceType, String action, Supplier<T>): T` 与 `record(String, String, Runnable): void`；指标 `sqlmask.admin.requests{resource_type,action,outcome}`、`sqlmask.admin.duration{resource_type,action}`。resource/action 枚举：INSTANCE/CREATE、INSTANCE/DELETE、INSTANCE/IMPORT、TABLES/REPLACE_TABLES、POLICY/CREATE、POLICY/UPDATE、POLICY/DELETE、UDF/REGISTER、UDF/DELETE、METADATA/CREATE、METADATA/UPDATE、METADATA/DELETE、METADATA/IMPORT（最后四项在 Task 5 的 metaserver 侧使用）。

- [ ] **Step 1: 写失败的单元测试**

`mask-core/src/test/java/io/sqlmask/server/AdminMetricsTest.java`：

```java
package io.sqlmask.server;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final AdminMetrics metrics = new AdminMetrics(registry);

  @Test
  void successRecordsCounterAndTimer() {
    String result = metrics.record("INSTANCE", "CREATE", () -> "ok");

    assertThat(result).isEqualTo("ok");
    assertThat(registry.get("sqlmask.admin.requests")
        .tag("resource_type", "INSTANCE").tag("action", "CREATE").tag("outcome", "SUCCESS")
        .counter().count()).isEqualTo(1.0);
    assertThat(registry.get("sqlmask.admin.duration")
        .tag("resource_type", "INSTANCE").tag("action", "CREATE").timer().count()).isEqualTo(1L);
  }

  @Test
  void failureCountsAndRethrowsUntouched() {
    RuntimeException boom = new IllegalStateException("boom");

    assertThatThrownBy(() -> metrics.record("POLICY", "DELETE", () -> {
          throw boom;
        }))
        .isSameAs(boom);

    assertThat(registry.get("sqlmask.admin.requests")
        .tag("resource_type", "POLICY").tag("action", "DELETE").tag("outcome", "FAILURE")
        .counter().count()).isEqualTo(1.0);
    assertThat(registry.get("sqlmask.admin.duration")
        .tag("resource_type", "POLICY").tag("action", "DELETE").timer().count()).isEqualTo(1L);
  }

  @Test
  void runnableOverloadRecordsVoidMutations() {
    metrics.record("UDF", "DELETE", () -> { });

    assertThat(registry.get("sqlmask.admin.requests")
        .tag("resource_type", "UDF").tag("action", "DELETE").tag("outcome", "SUCCESS")
        .counter().count()).isEqualTo(1.0);
  }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

```bash
mvn -q -pl mask-core -am test -Dtest=AdminMetricsTest
```

- [ ] **Step 3: 实现 AdminMetrics**

`mask-core/src/main/java/io/sqlmask/server/AdminMetrics.java`：

```java
package io.sqlmask.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Management-plane change metrics (spec §3.2). Wraps one mutation: counts the
 * outcome and records the duration, rethrowing business exceptions untouched.
 * Same wrapping position as the future audit {@code AuditAdminHelper}; when
 * mask-audit lands this class can be folded into it.
 */
@Component
public class AdminMetrics {

  private final MeterRegistry registry;

  public AdminMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public <T> T record(String resourceType, String action, Supplier<T> work) {
    long start = System.nanoTime();
    try {
      T result = work.get();
      counter(resourceType, action, "SUCCESS").increment();
      return result;
    } catch (RuntimeException e) {
      counter(resourceType, action, "FAILURE").increment();
      throw e;
    } finally {
      Timer.builder("sqlmask.admin.duration")
          .tag("resource_type", resourceType).tag("action", action)
          .serviceLevelObjectives(RewriteMetrics.sloSeconds())
          .register(registry)
          .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
  }

  public void record(String resourceType, String action, Runnable work) {
    record(resourceType, action, () -> {
      work.run();
      return null;
    });
  }

  private io.micrometer.core.instrument.Counter counter(String resourceType, String action,
      String outcome) {
    return registry.counter("sqlmask.admin.requests", "resource_type", resourceType,
        "action", action, "outcome", outcome);
  }
}
```

- [ ] **Step 4: 跑单测确认通过**

```bash
mvn -q -pl mask-core -am test -Dtest=AdminMetricsTest
```

- [ ] **Step 5: 埋点三个管理面控制器**

`PolicyAdminController`：注入 `AdminMetrics adminMetrics`（构造器加参），六个变更端点包一层（GET 端点不动）：

```java
  @PostMapping
  public InstanceDto create(@RequestBody InstanceDto request) {
    return adminMetrics.record("INSTANCE", "CREATE", () ->
        toDto(service.createInstance(request.name(), request.dialect(),
            toTables(request.tables()))));
  }

  @PutMapping("/{name}/tables")
  public InstanceDto replaceTables(@PathVariable("name") String name,
      @RequestBody TablesDto request) {
    return adminMetrics.record("TABLES", "REPLACE_TABLES", () ->
        toDto(service.updateInstanceTables(name, toTables(request.tables()))));
  }

  @DeleteMapping("/{name}")
  public void delete(@PathVariable("name") String name) {
    adminMetrics.record("INSTANCE", "DELETE", () -> service.deleteInstance(name));
  }

  @PostMapping("/{name}/policies")
  public PolicyDto createPolicy(@PathVariable("name") String name,
      @RequestBody PolicyDto request) {
    return adminMetrics.record("POLICY", "CREATE", () -> toDto(service.createPolicy(name,
        toModel(request))));
  }

  @PutMapping("/{name}/policies/{policy}")
  public PolicyDto updatePolicy(@PathVariable("name") String name,
      @PathVariable("policy") String policy, @RequestBody PolicyDto request) {
    return adminMetrics.record("POLICY", "UPDATE", () ->
        toDto(service.updatePolicy(name, policy, toModel(request))));
  }

  @DeleteMapping("/{name}/policies/{policy}")
  public void deletePolicy(@PathVariable("name") String name,
      @PathVariable("policy") String policy) {
    adminMetrics.record("POLICY", "DELETE", () -> service.deletePolicy(name, policy));
  }
```

`UdfController`：注入 `AdminMetrics adminMetrics`（构造器加参）。`create` 与 `replace` 的原方法体抽成私有方法 `doCreate(instance, request)` / `doReplace(instance, name, request)`（签名与原端点一致、内容=原方法体），端点方法体改为：

```java
  @PostMapping
  public UdfDto create(@PathVariable("instance") String instance, @RequestBody UdfDto request) {
    return adminMetrics.record("UDF", "REGISTER", () -> doCreate(instance, request));
  }

  @PutMapping("/{name}")
  public UdfDto replace(@PathVariable("instance") String instance,
      @PathVariable("name") String name, @RequestBody UdfDto request) {
    return adminMetrics.record("UDF", "REGISTER", () -> doReplace(instance, name, request));
  }

  @DeleteMapping("/{name}")
  public void delete(@PathVariable("instance") String instance, @PathVariable("name") String name) {
    adminMetrics.record("UDF", "DELETE", () -> service.deleteUdf(instance, name));
  }
```

（`doCreate`/`doReplace` 的内容就是 `create`/`replace` 现在的方法体，原样搬移、不改逻辑；若原方法体是单表达式，也可不抽方法直接写进 lambda。）

`MetadataImportController`：注入 `AdminMetrics`，`importMetadata` 包 `INSTANCE`/`IMPORT`：

```java
  @PostMapping("/api/instances/{name}/import-metadata")
  public ImportResponse importMetadata(@PathVariable("name") String name,
      @RequestBody ImportRequest request) {
    return adminMetrics.record("INSTANCE", "IMPORT", () -> doImport(name, request));
  }
```

其中 `doImport` 是原方法体抽出的私有方法（签名 `(String, ImportRequest): ImportResponse`，内容=原 importMetadata 方法体）。若原方法体很短，也可直接写成 lambda 内联，不抽方法。

- [ ] **Step 6: 写端到端测试**

`mask-core/src/test/java/io/sqlmask/server/AdminMetricsEndpointTest.java`：

```java
package io.sqlmask.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.PolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminMetricsEndpointTest {

  private static final String INSTANCE = "metrics_pg";

  @Autowired
  private MockMvc mvc;

  @Autowired
  private MeterRegistry registry;

  @Autowired
  private PolicyService service;

  @BeforeEach
  void removeLeftoverInstance() {
    try {
      service.deleteInstance(INSTANCE);
    } catch (SqlMaskException notExists) {
      // 首次运行实例不存在，忽略
    }
  }

  @Test
  void createInstanceCountsSuccess() throws Exception {
    double before = counter("INSTANCE", "CREATE", "SUCCESS");
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content(new ObjectMapper().writeValueAsString(Map.of(
                "name", INSTANCE, "dialect", "postgresql", "tables", List.of(Map.of(
                    "catalog", "crm", "schema", "public", "name", "customer",
                    "columns", List.of(Map.of("name", "phone", "type", "varchar"))))))))
        .andExpect(status().isOk());
    assertThat(counter("INSTANCE", "CREATE", "SUCCESS")).isEqualTo(before + 1);
  }

  @Test
  void failedMutationCountsFailure() throws Exception {
    double before = counter("POLICY", "CREATE", "FAILURE");
    mvc.perform(post("/api/instances/no_such_instance_metrics/policies")
            .contentType(MediaType.APPLICATION_JSON)
            .content(new ObjectMapper().writeValueAsString(Map.of(
                "name", "metrics_pol", "policyType", "datamask", "isEnabled", true,
                "resource", Map.of("catalog", "crm", "schema", "public", "table", "customer",
                    "columns", List.of("phone")),
                "udf", "mask_phone", "arguments", List.of()))))
        .andExpect(status().is4xxClientError());
    assertThat(counter("POLICY", "CREATE", "FAILURE")).isEqualTo(before + 1);
  }

  private double counter(String resource, String action, String outcome) {
    return registry.counter("sqlmask.admin.requests",
        "resource_type", resource, "action", action, "outcome", outcome).count();
  }
}
```

- [ ] **Step 7: 跑测试**

```bash
mvn -q -pl mask-core -am test -Dtest='AdminMetricsTest,AdminMetricsEndpointTest,PolicyAdminEndpointTest,UdfEndpointTest,MetadataImportEndpointTest'
```

预期：新测试 PASS，三个既有端点测试不受影响。

- [ ] **Step 8: Commit**

```bash
git add mask-core/src/main/java/io/sqlmask/server/AdminMetrics.java mask-core/src/main/java/io/sqlmask/server/PolicyAdminController.java mask-core/src/main/java/io/sqlmask/server/UdfController.java mask-core/src/main/java/io/sqlmask/server/MetadataImportController.java mask-core/src/test/java/io/sqlmask/server/AdminMetricsTest.java mask-core/src/test/java/io/sqlmask/server/AdminMetricsEndpointTest.java
git commit -m "feat(metrics): 管理面变更指标（resource_type/action/outcome，对齐审计枚举）"
```

---

### Task 4: 生效配置指标（mask-core）

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/server/EffectiveMetrics.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/EffectiveConfigController.java`
- Test: `mask-core/src/test/java/io/sqlmask/server/EffectiveMetricsTest.java`（新建）
- Test: `mask-core/src/test/java/io/sqlmask/server/EffectiveConfigMetricsTest.java`（新建，@SpringBootTest）

**Interfaces:**
- Consumes: Task 1 的 `MeterRegistry`；`EffectiveConfigResponse(instance, dialect, configVersion, policySummary(enabled, disabled), config)`。
- Produces: `EffectiveMetrics.success(String, EffectiveConfigResponse, long)`、`notFound(long)`、`failure(String, long)`；指标 `sqlmask.effective.pull{instance,dialect,outcome}`、`sqlmask.effective.compile{instance}`、gauge `sqlmask.effective.config_version{instance}`、gauge `sqlmask.effective.policies{instance}`（值 = `policySummary.enabled()`）。

- [ ] **Step 1: 写失败的单元测试**

`mask-core/src/test/java/io/sqlmask/server/EffectiveMetricsTest.java`：

```java
package io.sqlmask.server;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.sqlmask.config.source.EffectiveConfigResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final EffectiveMetrics metrics = new EffectiveMetrics(registry);

  private static EffectiveConfigResponse response(long version, int enabled) {
    return new EffectiveConfigResponse("pg_prod", "postgresql", version,
        new EffectiveConfigResponse.PolicySummary(enabled, 0), null);
  }

  @Test
  void successUpdatesPullCounterCompileTimerAndGauges() {
    long start = System.nanoTime();
    metrics.success("pg_prod", response(7, 3), start);
    metrics.success("pg_prod", response(9, 5), start);

    assertThat(registry.get("sqlmask.effective.pull")
        .tag("instance", "pg_prod").tag("dialect", "postgresql").tag("outcome", "SUCCESS")
        .counter().count()).isEqualTo(2.0);
    assertThat(registry.get("sqlmask.effective.config_version")
        .tag("instance", "pg_prod").gauge().value()).isEqualTo(9.0);
    assertThat(registry.get("sqlmask.effective.policies")
        .tag("instance", "pg_prod").gauge().value()).isEqualTo(5.0);
    assertThat(registry.get("sqlmask.effective.compile")
        .tag("instance", "pg_prod").timer().count()).isEqualTo(2L);
  }

  @Test
  void notFoundGoesToSharedSentinelSeries() {
    metrics.notFound(System.nanoTime());
    metrics.notFound(System.nanoTime());

    assertThat(registry.get("sqlmask.effective.pull")
        .tag("instance", "(not_found)").tag("dialect", "(not_found)").tag("outcome", "FAILURE")
        .counter().count()).isEqualTo(2.0);
    // 未知实例不得产生按请求输入命名的 series
    assertThat(registry.getMeters()).noneMatch(m ->
        m.getId().getName().equals("sqlmask.effective.pull")
            && m.getId().getTag("instance").equals("garbage"));
  }

  @Test
  void otherFailuresKeepResolvedInstanceNameWithUnknownDialect() {
    metrics.failure("pg_prod", System.nanoTime());
    metrics.failure(null, System.nanoTime());

    assertThat(registry.get("sqlmask.effective.pull")
        .tag("instance", "pg_prod").tag("dialect", "(unknown)").tag("outcome", "FAILURE")
        .counter().count()).isEqualTo(1.0);
    assertThat(registry.get("sqlmask.effective.pull")
        .tag("instance", "(not_found)").tag("dialect", "(unknown)").tag("outcome", "FAILURE")
        .counter().count()).isEqualTo(1.0);
  }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

```bash
mvn -q -pl mask-core -am test -Dtest=EffectiveMetricsTest
```

- [ ] **Step 3: 实现 EffectiveMetrics**

`mask-core/src/main/java/io/sqlmask/server/EffectiveMetrics.java`：

```java
package io.sqlmask.server;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.sqlmask.config.source.EffectiveConfigResponse;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Policy-service data-plane metrics (spec §3.3). The {@code instance} label is
 * the only semi-open label in the catalog: it is accepted only after the
 * instance resolved (admin-bounded); unresolved lookups collapse into the
 * shared {@code (not_found)} series.
 */
@Component
public class EffectiveMetrics {

  static final String NOT_FOUND = "(not_found)";
  private static final String UNKNOWN_DIALECT = "(unknown)";

  private final MeterRegistry registry;
  private final ConcurrentHashMap<String, AtomicLong> versions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicLong> policies = new ConcurrentHashMap<>();

  public EffectiveMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void success(String instance, EffectiveConfigResponse response, long startNanos) {
    registry.counter("sqlmask.effective.pull", "instance", instance,
        "dialect", response.dialect(), "outcome", "SUCCESS").increment();
    gauge(versions, "sqlmask.effective.config_version", instance).set(response.configVersion());
    gauge(policies, "sqlmask.effective.policies", instance)
        .set(response.policySummary().enabled());
    compile(instance, startNanos);
  }

  public void notFound(long startNanos) {
    registry.counter("sqlmask.effective.pull", "instance", NOT_FOUND,
        "dialect", NOT_FOUND, "outcome", "FAILURE").increment();
    compile(NOT_FOUND, startNanos);
  }

  /** Only for failures after the instance resolved — the name is admin-bounded. */
  public void failure(String instance, long startNanos) {
    String name = instance == null || instance.isBlank() ? NOT_FOUND : instance;
    registry.counter("sqlmask.effective.pull", "instance", name,
        "dialect", UNKNOWN_DIALECT, "outcome", "FAILURE").increment();
    compile(name, startNanos);
  }

  private void compile(String instance, long startNanos) {
    Timer.builder("sqlmask.effective.compile")
        .tag("instance", instance)
        .serviceLevelObjectives(RewriteMetrics.sloSeconds())
        .register(registry)
        .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
  }

  private AtomicLong gauge(ConcurrentHashMap<String, AtomicLong> map, String name,
      String instance) {
    return map.computeIfAbsent(instance, i -> {
      AtomicLong ref = new AtomicLong();
      Gauge.builder(name, ref, AtomicLong::get).tag("instance", i).register(registry);
      return ref;
    });
  }
}
```

已知取舍：实例被删除后其 gauge 序列保留到进程重启（Micrometer gauge 弱引用语义下保守持有），量级 = 历史实例数，可接受；不做清理钩子。

- [ ] **Step 4: 跑单测确认通过**

```bash
mvn -q -pl mask-core -am test -Dtest=EffectiveMetricsTest
```

- [ ] **Step 5: 改 EffectiveConfigController 埋点**

```java
  private final PolicyService service;
  private final EffectiveMetrics metrics;

  public EffectiveConfigController(PolicyService service, EffectiveMetrics metrics) {
    this.service = service;
    this.metrics = metrics;
  }

  @GetMapping("/api/effective/{instance}")
  public EffectiveConfigResponse effective(
      @PathVariable("instance") String instance,
      @RequestParam(value = "user", required = false) String user,
      @RequestParam(value = "groups", required = false) List<String> groups) {
    long start = System.nanoTime();
    try {
      EffectiveConfigResponse response = service.effective(instance, Subject.of(user, groups));
      metrics.success(instance, response, start);
      return response;
    } catch (SqlMaskException e) {
      if (e.getCode() == SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND) {
        metrics.notFound(start);
      } else {
        metrics.failure(instance, start);
      }
      throw e;
    } catch (RuntimeException e) {
      metrics.failure(instance, start);
      throw e;
    }
  }
```

import 新增：`io.sqlmask.error.SqlMaskException`。

- [ ] **Step 6: 写端到端测试**

`mask-core/src/test/java/io/sqlmask/server/EffectiveConfigMetricsTest.java`：

```java
package io.sqlmask.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EffectiveConfigMetricsTest {

  private static final String INSTANCE = "metrics_eff_pg";

  @Autowired
  private MockMvc mvc;

  @Autowired
  private MeterRegistry registry;

  @Autowired
  private PolicyService service;

  @BeforeEach
  void setUp() {
    try {
      service.createInstance(INSTANCE, "postgresql", List.of(
          new TableDef("crm", "public", "customer",
              List.of(new ColumnDef("phone", "varchar")))));
    } catch (SqlMaskException alreadyExists) {
      // 上下文复用
    }
  }

  @Test
  void pullUpdatesGauges() throws Exception {
    mvc.perform(get("/api/effective/" + INSTANCE))
        .andExpect(status().isOk());

    assertThat(registry.get("sqlmask.effective.pull")
        .tag("instance", INSTANCE).tag("dialect", "postgresql").tag("outcome", "SUCCESS")
        .counter().count()).isGreaterThanOrEqualTo(1.0);
    assertThat(registry.get("sqlmask.effective.config_version")
        .tag("instance", INSTANCE).gauge().value()).isGreaterThanOrEqualTo(1.0);
  }

  @Test
  void unknownInstanceCollapsesIntoNotFoundSentinel() throws Exception {
    double before = registry.counter("sqlmask.effective.pull",
        "instance", "(not_found)", "dialect", "(not_found)", "outcome", "FAILURE").count();
    mvc.perform(get("/api/effective/no_such_instance_metrics"))
        .andExpect(status().isBadRequest());

    assertThat(registry.get("sqlmask.effective.pull")
        .tag("instance", "(not_found)").tag("dialect", "(not_found)").tag("outcome", "FAILURE")
        .counter().count()).isEqualTo(before + 1);
    assertThat(registry.getMeters()).noneMatch(m ->
        m.getId().getName().equals("sqlmask.effective.pull")
            && m.getId().getTag("instance").equals("no_such_instance_metrics"));
  }
}
```

- [ ] **Step 7: 跑测试**

```bash
mvn -q -pl mask-core -am test -Dtest='EffectiveMetricsTest,EffectiveConfigMetricsTest,EffectiveConfigEndpointTest'
```

- [ ] **Step 8: Commit**

```bash
git add mask-core/src/main/java/io/sqlmask/server/EffectiveMetrics.java mask-core/src/main/java/io/sqlmask/server/EffectiveConfigController.java mask-core/src/test/java/io/sqlmask/server/EffectiveMetricsTest.java mask-core/src/test/java/io/sqlmask/server/EffectiveConfigMetricsTest.java
git commit -m "feat(metrics): 生效配置指标（pull/compile/config_version/policies，未知实例坍缩到 (not_found) 哨兵）"
```

---

### Task 5: metaserver 采集与管理面指标（mask-metadata）

**Files:**
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/metrics/AdminMetrics.java`
- Create: `mask-metadata/src/main/java/io/sqlmask/metaserver/metrics/CollectMetrics.java`
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/service/CollectService.java`
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataAdminController.java`
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/metrics/CollectMetricsTest.java`（新建）
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/service/CollectServiceTest.java`（修改：4 处构造器加参 + 新增指标断言）

**Interfaces:**
- Consumes: Task 1 的 `MeterRegistry`；`IntrospectionResult.warnings(): List<String>`；`InstanceRow.dialect(): String`。
- Produces: `CollectMetrics.record(String engine, Supplier<T>): T`、`warnings(String engine, int count)`；指标 `sqlmask.metadata.collect{engine,outcome}`、`sqlmask.metadata.collect.duration{engine}`、`sqlmask.metadata.warnings{engine}`；mask-metadata 侧 `AdminMetrics`（与 Task 3 同形，`resource_type` 用 `METADATA`）。metaserver 的引擎采集（审计 action=COLLECT）只进 collect 指标，不进 admin 指标（spec §3.2 注）。

说明：mask-core 与 mask-metadata 不共享模块，`AdminMetrics` 在两个服务各放一份（约 50 行，spec §1.2 明确不为此新建模块）；两份类放各自的包 `io.sqlmask.server` 与 `io.sqlmask.metaserver.metrics`。

- [ ] **Step 1: 写失败的 CollectMetrics 单元测试**

`mask-metadata/src/test/java/io/sqlmask/metaserver/metrics/CollectMetricsTest.java`：

```java
package io.sqlmask.metaserver.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final CollectMetrics metrics = new CollectMetrics(registry);

  @Test
  void successRecordsCounterTimerAndWarnings() {
    int result = metrics.record("postgresql", () -> 2);

    assertThat(result).isEqualTo(2);
    assertThat(registry.get("sqlmask.metadata.collect")
        .tag("engine", "postgresql").tag("outcome", "SUCCESS").counter().count()).isEqualTo(1.0);
    assertThat(registry.get("sqlmask.metadata.collect.duration")
        .tag("engine", "postgresql").timer().count()).isEqualTo(1L);

    metrics.warnings("postgresql", 2);
    metrics.warnings("postgresql", 0);
    assertThat(registry.get("sqlmask.metadata.warnings")
        .tag("engine", "postgresql").counter().count()).isEqualTo(2.0);
  }

  @Test
  void failureCountsAndRethrowsUntouched() {
    RuntimeException boom = new IllegalStateException("db down");

    assertThatThrownBy(() -> metrics.record("mysql", () -> {
          throw boom;
        }))
        .isSameAs(boom);

    assertThat(registry.get("sqlmask.metadata.collect")
        .tag("engine", "mysql").tag("outcome", "FAILURE").counter().count()).isEqualTo(1.0);
    assertThat(registry.get("sqlmask.metadata.collect.duration")
        .tag("engine", "mysql").timer().count()).isEqualTo(1L);
  }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

```bash
mvn -q -pl mask-metadata -am test -Dtest=CollectMetricsTest
```

- [ ] **Step 3: 实现 CollectMetrics 与 metaserver 侧 AdminMetrics**

`mask-metadata/src/main/java/io/sqlmask/metaserver/metrics/CollectMetrics.java`：

```java
package io.sqlmask.metaserver.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Metadata collection metrics (spec §3.4). The engine label comes from the
 * stored instance row (admin-bounded), never from free request input.
 */
@Component
public class CollectMetrics {

  private final MeterRegistry registry;

  public CollectMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public <T> T record(String engine, Supplier<T> work) {
    long start = System.nanoTime();
    try {
      T result = work.get();
      registry.counter("sqlmask.metadata.collect", "engine", engine, "outcome", "SUCCESS")
          .increment();
      return result;
    } catch (RuntimeException e) {
      registry.counter("sqlmask.metadata.collect", "engine", engine, "outcome", "FAILURE")
          .increment();
      throw e;
    } finally {
      Timer.builder("sqlmask.metadata.collect.duration")
          .tag("engine", engine)
          .serviceLevelObjectives(0.010, 0.050, 0.100, 0.250, 0.500, 1.0, 2.5, 5.0, 10.0, 30.0,
              60.0)
          .register(registry)
          .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
  }

  public void warnings(String engine, int count) {
    if (count > 0) {
      registry.counter("sqlmask.metadata.warnings", "engine", engine).increment(count);
    }
  }
}
```

`mask-metadata/src/main/java/io/sqlmask/metaserver/metrics/AdminMetrics.java`（与 mask-core 版职责相同，但独立成类、SLO 桶内联——两服务不共享模块，spec §1.2 明确不为此新建模块）：

```java
package io.sqlmask.metaserver.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Management-plane change metrics (spec §3.2). Wraps one mutation: counts the
 * outcome and records the duration, rethrowing business exceptions untouched.
 */
@Component
public class AdminMetrics {

  private final MeterRegistry registry;

  public AdminMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public <T> T record(String resourceType, String action, Supplier<T> work) {
    long start = System.nanoTime();
    try {
      T result = work.get();
      counter(resourceType, action, "SUCCESS").increment();
      return result;
    } catch (RuntimeException e) {
      counter(resourceType, action, "FAILURE").increment();
      throw e;
    } finally {
      Timer.builder("sqlmask.admin.duration")
          .tag("resource_type", resourceType).tag("action", action)
          .serviceLevelObjectives(0.001, 0.005, 0.010, 0.025, 0.050, 0.100, 0.250, 0.500,
              1.0, 5.0, 10.0)
          .register(registry)
          .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
  }

  public void record(String resourceType, String action, Runnable work) {
    record(resourceType, action, () -> {
      work.run();
      return null;
    });
  }

  private Counter counter(String resourceType, String action, String outcome) {
    return registry.counter("sqlmask.admin.requests", "resource_type", resourceType,
        "action", action, "outcome", outcome);
  }
}
```

- [ ] **Step 4: 跑单测确认通过**

```bash
mvn -q -pl mask-metadata -am test -Dtest=CollectMetricsTest
```

- [ ] **Step 5: 改 CollectService 埋点**

注入 `CollectMetrics`（构造器加第 5 参），`collect` 方法改为——引擎从未知实例解析出来之后才开始计量（未知实例 → `instances.get` 抛 `METADATA_INSTANCE_NOT_FOUND`，不记 collect 指标，由通用 HTTP 指标覆盖）：

```java
  private final CollectMetrics collectMetrics;

  public CollectService(MetadataService instances, StructureService structures,
      CredentialResolver credentials, IntrospectorFactory introspectors,
      CollectMetrics collectMetrics) {
    this.instances = instances;
    this.structures = structures;
    this.credentials = credentials;
    this.introspectors = introspectors;
    this.collectMetrics = collectMetrics;
  }

  public MetadataDtos.CollectResponse collect(String name) {
    InstanceRow row = instances.get(name);
    ConnectionInfo connection = row.connection();
    if (connection == null) {
      // 有 engine、无连接配置也算一次失败采集（CONFIG_ERROR）
      return collectMetrics.record(row.dialect(), () -> {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "instance '" + name + "' has no connection settings; PUT the connection first");
      });
    }
    return collectMetrics.record(row.dialect(), () -> {
      ConnectionSpec spec = new ConnectionSpec(row.dialect(), connection.host(), connection.port(),
          connection.database(), connection.dbUser(), credentials.resolve(connection.passwordRef()),
          connection.schemas(), connection.includeViews(), false, connection.sslmode(),
          connection.connectTimeoutSeconds());
      IntrospectionResult result = introspectors.byEngine(row.dialect()).introspect(spec);
      List<TableStructure> tables = toStructures(result);
      long version = structures.replace(name, tables);
      collectMetrics.warnings(row.dialect(), result.warnings().size());
      return new MetadataDtos.CollectResponse(tables.size(),
          tables.stream().mapToInt(t -> t.columns().size()).sum(), result.warnings(), version);
    });
  }
```

import 新增：`io.sqlmask.metaserver.metrics.CollectMetrics`。

- [ ] **Step 6: 更新 CollectServiceTest（构造器 4 处 + 指标断言）**

文件头加字段（import `io.micrometer.core.instrument.simple.SimpleMeterRegistry`、`io.sqlmask.metaserver.metrics.CollectMetrics`）：

```java
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
```

四处 `new CollectService(instances, structures, ...)` 各追加第 5 参 `new CollectMetrics(registry)`（第 31、56、74、91 行附近；lambda 参数顺序保持不变）。

`collectReplacesStructureAndReportsCounts` 末尾追加：

```java
    org.assertj.core.api.Assertions.assertThat(
        registry.get("sqlmask.metadata.collect")
            .tag("engine", "postgresql").tag("outcome", "SUCCESS").counter().count())
        .isEqualTo(1.0);
    org.assertj.core.api.Assertions.assertThat(
        registry.get("sqlmask.metadata.warnings")
            .tag("engine", "postgresql").counter().count()).isEqualTo(1.0);
```

（warnings=1 来自测试夹具 `result` 的一条降级告警。）`collectFailureLeavesStoredStructureUntouched` 用例若通过抛异常路径失败，追加断言 FAILURE 计数 ≥1（该用例第 56 行的 `failing` 实例同样传 `new CollectMetrics(registry)`；断言用 `isGreaterThanOrEqualTo(1.0)`，因用例内先成功采集过一次）。文件既有断言风格是 JUnit `assertEquals`——新断言统一用 AssertJ 静态导入亦可，与上面代码块保持一致即可。

- [ ] **Step 7: 改 MetadataAdminController 埋点**

注入 metaserver 侧 `AdminMetrics adminMetrics`（构造器加参，Spring 自动装配）。四个变更端点各自抽私有方法再包（GET 端点不动），原方法体原样搬进私有方法、不改逻辑：

| 端点 | resource_type / action | 抽出的私有方法 |
|---|---|---|
| `create`（POST `/api/instances`） | `METADATA` / `CREATE` | `doCreate`（参数与返回值同原端点） |
| `update`（PUT `/api/instances/{name}`） | `METADATA` / `UPDATE` | `doUpdate` |
| `delete`（DELETE `/api/instances/{name}`） | `METADATA` / `DELETE` | `doDelete` |
| `importYaml`（POST `/api/instances/import`） | `METADATA` / `IMPORT` | `doImport` |

包裹形态与 Task 3 的 `UdfController` 相同：`return adminMetrics.record("METADATA", "CREATE", () -> doCreate(...));`（void 端点用 Runnable 重载）。

- [ ] **Step 8: 跑 mask-metadata 全部测试**

```bash
mvn -q -pl mask-metadata -am test
```

预期：全绿（含既有 `CollectControllerTest`、`MetadataAdminControllerTest`、`MetadataServerApplicationTest`——构造器变更由 Spring 装配自动满足）。

- [ ] **Step 9: Commit**

```bash
git add mask-metadata/src/main/java/io/sqlmask/metaserver/metrics/AdminMetrics.java mask-metadata/src/main/java/io/sqlmask/metaserver/metrics/CollectMetrics.java mask-metadata/src/main/java/io/sqlmask/metaserver/service/CollectService.java mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataAdminController.java mask-metadata/src/test/java/io/sqlmask/metaserver/metrics/CollectMetricsTest.java mask-metadata/src/test/java/io/sqlmask/metaserver/service/CollectServiceTest.java
git commit -m "feat(metrics): metaserver 采集指标（engine/outcome/warnings）与管理面指标（METADATA）"
```

---

### Task 6: 部署物与 README

**Files:**
- Create: `docker/prometheus.yml`
- Create: `docker-compose.metrics.yml`
- Modify: `README.md`（文末追加一节）

**Interfaces:**
- Consumes: Task 1 的 `/actuator/prometheus` 端点（8080/8082）。
- Produces: 可直接 `docker compose -f docker-compose.metrics.yml up -d` 启动的抓取配置。

- [ ] **Step 1: 写抓取配置**

`docker/prometheus.yml`：

```yaml
scrape_interval: 15s

scrape_configs:
  # 默认形态：服务跑在宿主机、Prometheus 跑容器
  - job_name: sql-mask
    static_configs:
      - targets: ["host.docker.internal:8080"]
  - job_name: mask-metadata
    static_configs:
      - targets: ["host.docker.internal:8082"]
  # 若服务也在 compose 网络内，把 targets 换成服务名:
  #   sql-mask:8080 / metadata:8082
```

- [ ] **Step 2: 写 compose 示例**

`docker-compose.metrics.yml`：

```yaml
services:
  prometheus:
    image: prom/prometheus:v2.53.0
    volumes:
      - ./docker/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    ports:
      - "9090:9090"
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

- [ ] **Step 3: README 追加指标一节**

在 `README.md` 文末追加：

```markdown
## 指标（Prometheus）

两个服务在同端口暴露 Prometheus 抓取端点：mask-core `http://localhost:8080/actuator/prometheus`、
metaserver `http://localhost:8082/actuator/prometheus`。指标端点无鉴权，仅供内网使用，
不要暴露公网。指标目录与 label 纪律见
`docs/superpowers/specs/2026-09-17-prometheus-metrics-design.md`。

本地快速验证（Prometheus 抓宿主机的 8080/8082）：

    docker compose -f docker-compose.metrics.yml up -d
    curl -s localhost:8080/actuator/prometheus | grep sqlmask_

部署侧可用 `management.server.port` 把指标端口与业务端口隔离（可选，默认同端口）。
```

（README 其余内容一字不动。）

- [ ] **Step 4: 验证**

```bash
mvn -q test
```

预期：全仓库测试通过。人工验证（可选，本机有 Docker 时）：

```bash
java -jar mask-core/target/sql-mask.jar &
docker compose -f docker-compose.metrics.yml up -d
# 打开 http://localhost:9090，查询 sqlmask_rewrite_requests_total 有数据
```

- [ ] **Step 5: Commit**

```bash
git add docker/prometheus.yml docker-compose.metrics.yml README.md
git commit -m "feat(metrics): Prometheus 抓取配置与 compose 示例，README 指标说明"
```

---

## 收尾核对（执行完后）

- [ ] `mvn -q test` 全绿；
- [ ] `curl -s localhost:8080/actuator/prometheus | grep sqlmask_` 出现：`sqlmask_rewrite_requests_total`、`sqlmask_rewrite_failures_total`、`sqlmask_rewrite_duration_seconds_bucket`、`sqlmask_rewrite_statements_total`、`sqlmask_admin_requests_total`、`sqlmask_admin_duration_seconds_count`、`sqlmask_effective_pull_total`、`sqlmask_effective_compile_seconds_count`、`sqlmask_effective_config_version`、`sqlmask_effective_policies`；
- [ ] `curl -s localhost:8082/actuator/prometheus | grep sqlmask_` 出现：`sqlmask_metadata_collect_total`、`sqlmask_metadata_collect_duration_seconds_count`、`sqlmask_metadata_warnings_total`、`sqlmask_admin_requests_total`；
- [ ] spec §8 的说明成立：本计划未触碰 `mask-audit`（§3.5 审计管道指标留给该模块的实施计划）。
