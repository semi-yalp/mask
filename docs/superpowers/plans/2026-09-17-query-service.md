# 统一查询服务（mask-query 批 1）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 mask-query 数据面服务：调用方提交「原始 SQL + 实例 + 主体」，服务内部经 mask-core 按实例改写后以 JDBC 执行，只返回脱敏后结果集（批 1：PostgreSQL / MySQL / Trino / StarRocks）。

**Architecture:** 数据面/控制面分离。mask-query（8083）无状态：从 mask-metadata 拉实例连接信息，调 mask-core 新端点 `POST /api/rewrite/instances/{name}` 改写（mask-core 组装 元数据快照 + 按主体生效配置），然后自行建 JDBC 连接执行并施加护栏（并发信号量 → 只读 → 超时 → maxRows+1 截断 → 流式读取 → 断连取消）。审计走 mask-audit 共享模块新增的 `QUERY` 事件。

**Tech Stack:** Java 17、Spring Boot 3（Web/MockMvc/JdbcTemplate）、JDBC 驱动 org.postgresql / com.mysql:mysql-connector-j / io.trino:trino-jdbc:446（三者均已在仓库依赖中）、jackson、mask-audit、junit5 + assertj + mockito、embedded-postgres（IT）。

**Spec:** `docs/superpowers/specs/2026-09-17-query-service-design.md`（本计划逐节实现该 spec，字段与错误码以 spec 为准）

## Global Constraints

- 全仓构建：`mvn test`；单模块：`mvn -pl <module> -am test`。每任务收尾必须全绿再提交。
- 提交信息用仓库既有惯例：`feat(scope): ...` / `test: ...` / `docs: ...`，中文描述。
- **密码红线**：数据库密码与 API Key 原文不进日志、不进错误消息、不进审计事件。
- 错误契约：`{"code":"...","message":"..."}`；HTTP 状态：业务错误 400，鉴权失败 401。
- 新增 Maven 模块加入父 pom `<modules>`；mask-query 不依赖 mask-core / mask-policy / mask-metadata（只依赖 mask-audit 与第三方库），跨服务一律 HTTP。
- `instance` 的 `engine` 与 `dialect` 分离：dialect 保持 `{postgresql, mysql, trino}`；engine 可显式为 `starrocks`（此时 dialect 必须为 `mysql`），为空时由 dialect 推导。
- 执行分支前置：本计划在 `feature/audit-log-es` 分支之上编写（mask-audit 模块在该分支）；执行前从当时的主开发分支拉出特性分支。Task 12 依赖 `AuditRecorder` 接口（审计计划 Task 5），见该任务内前置说明。
- 兼容性红线：`POST /api/rewrite`（内联 YAML）与 mask-metadata 既有实例 API 的既有字段行为逐字节不变；本计划只做增量。

## File Structure

```
mask-core/src/main/java/io/sqlmask/
  rewrite/StatementKind.java                  [Task 1] 新枚举
  rewrite/RewriteEngine.java                  [Task 1] StatementRewrite + kind
  config/source/InstanceQueryAssembler.java   [Task 2] 快照×生效配置合并
  server/InstanceRewriteConfig.java           [Task 2] 上游服务配置 + 客户端 bean
  server/InstanceRewriteController.java       [Task 3] 按实例改写端点
  server/InstanceRewriteApiKeyFilter.java     [Task 3] 可选 key
  server/SqlMaskServiceApplication.java       [Task 3] 注册过滤器

mask-metadata/src/main/java/io/sqlmask/metaserver/
  model/InstanceRow.java                      [Task 4] + engine
  service/MetadataService.java                [Task 4] engine 规范化
  web/MetadataDtos.java                       [Task 4] DTO + engine
  web/MetadataAdminController.java            [Task 4] 透传 engine
  store/JdbcMetaStore.java                    [Task 4] engine 列
  store/InMemoryMetaStore.java (test)         [Task 4]
mask-metadata/src/main/resources/metadata-schema.sql [Task 4]

mask-query/                                   [Task 5..13] 新模块
  pom.xml
  src/main/java/io/sqlmask/query/
    QueryServerApplication.java               [Task 5]
    config/QueryProperties.java               [Task 5]
    config/UpstreamProperties.java            [Task 5]
    error/QueryException.java                 [Task 6]
    web/QueryApiKeyFilter.java                [Task 6]
    web/ApiExceptionHandler.java              [Task 6]
    metadata/MetadataServiceClient.java       [Task 7] 实例视图客户端
    rewrite/RewriteServiceClient.java         [Task 8] 改写客户端
    executors/QueryEngine.java                [Task 9] 引擎目录 + URL
    service/ValueJson.java                    [Task 10] 结果值规范化
    service/ErrorClassifier.java              [Task 10] SQLException→错误码
    service/CancelRegistry.java               [Task 11] 断连取消注册表
    service/QueryService.java                 [Task 10/11] 执行管线
    web/QueryController.java                  [Task 11] POST /api/v1/query
    audit/QueryAudit.java                     [Task 12] 审计装配 + 发射
  src/main/resources/application.yml          [Task 5]
  src/test/java/io/sqlmask/query/...          各任务测试
  src/test/java/io/sqlmask/query/QueryEndToEndIT.java [Task 13]

mask-audit/src/main/java/io/sqlmask/audit/AuditEvent.java [Task 12] + QUERY
docs/query-acceptance/golden-queries.md       [Task 14] golden 验收清单
docker-compose.query.yml                      [Task 14] mask-query + 三引擎
README.md                                     [Task 14]
docs/superpowers/specs/2026-09-17-query-service-design.md [Task 14] 错误码补录
```

---

### Task 1: mask-core — StatementKind 枚举与 StatementRewrite.kind

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/rewrite/StatementKind.java`
- Modify: `mask-core/src/main/java/io/sqlmask/rewrite/RewriteEngine.java`
- Test: `mask-core/src/test/java/io/sqlmask/server/RewriteControllerTest.java`（追加断言）

**Interfaces:**
- Produces: `enum StatementKind { SELECT, INSERT_SELECT, CTAS }`；
  `StatementRewrite(int ordinal, String originalSql, String rewrittenSql, boolean masked,
  boolean rowFiltered, StatementKind kind)`（JSON 增加 `"kind":"SELECT"|...`）。
  旧 4 参/5 参构造器保留，kind 缺省 `SELECT`。

- [ ] **Step 1: 写失败测试**

在 `RewriteControllerTest` 追加（沿用类内已有的 `YAML` 常量与 MockMvc 写法）：

```java
@Test
void rewriteResponseCarriesStatementKinds() throws Exception {
  mockMvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON).content("""
      {"metadataYaml": %s, "sql": "SELECT phone FROM customer; INSERT INTO arch (phone) SELECT phone FROM customer; CREATE TABLE AS t AS SELECT phone FROM customer;", "dialect": "postgresql"}
      """.formatted(OBJECT_MAPPER.writeValueAsString(YAML)).replace("\\n", "\\\\n")))
      .andExpect(status().isOk());
  // 上面的 formatted 拼接易错，直接用 Java 文本块拼 JSON 更稳，见下方完整版本
}
```

实际落地用下面这个**完整版本**替换上面草稿（`RewriteControllerTest` 中新增）：

```java
@Test
void rewriteResponseCarriesStatementKinds() throws Exception {
  String body = """
      {"metadataYaml":"%s","sql":"SELECT phone FROM customer;\\nINSERT INTO arch (phone) SELECT phone FROM customer;\\nCREATE TABLE t AS SELECT phone FROM customer;","dialect":"postgresql"}
      """.formatted(YAML.replace("\"", "\\\\").replace("\n", "\\n"));
  mockMvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON).content(body))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.statements[0].kind").value("SELECT"))
      .andExpect(jsonPath("$.statements[1].kind").value("INSERT_SELECT"))
      .andExpect(jsonPath("$.statements[2].kind").value("CTAS"));
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-core -am test -Dtest=RewriteControllerTest`
Expected: FAIL —— `$.statements[0].kind` 路径不存在。

- [ ] **Step 3: 实现**

`StatementKind.java`：

```java
package io.sqlmask.rewrite;

/** Coarse statement class of one rewritten statement; the query data plane
 * rejects everything but {@link #SELECT}. */
public enum StatementKind { SELECT, INSERT_SELECT, CTAS }
```

`RewriteEngine.java` 修改三处：

```java
// 1) StatementRewrite 记录加 kind（主构造器 6 参；旧构造器委托并缺省 SELECT）
public record StatementRewrite(int ordinal, String originalSql, String rewrittenSql,
    boolean masked, boolean rowFiltered, StatementKind kind) {

  public StatementRewrite(int ordinal, String originalSql, String rewrittenSql, boolean masked) {
    this(ordinal, originalSql, rewrittenSql, masked, false);
  }

  public StatementRewrite(int ordinal, String originalSql, String rewrittenSql, boolean masked,
      boolean rowFiltered) {
    this(ordinal, originalSql, rewrittenSql, masked, rowFiltered, StatementKind.SELECT);
  }

  @com.fasterxml.jackson.annotation.JsonProperty("unchanged")
  public boolean unchanged() {
    return originalSql.equals(rewrittenSql);
  }
}
```

```java
// 2) rewriteOne 写语句分支：计算 writeKind 并贯穿四个 return
if (parsed.getKind() == org.apache.calcite.sql.SqlKind.INSERT
    || parsed.getKind() == org.apache.calcite.sql.SqlKind.CREATE_TABLE) {
  StatementKind writeKind = parsed.getKind() == org.apache.calcite.sql.SqlKind.INSERT
      ? StatementKind.INSERT_SELECT : StatementKind.CTAS;
  if (dialect.isPassThroughWrite(parsed)) {
    return new StatementRewrite(ordinal, statementText, statementText, false, false, writeKind);
  }
  // ……（方法体不变，仅把该分支内所有 new StatementRewrite(...) 补上末参 writeKind：
  //  composeWriteStatement 两处 → new StatementRewrite(ordinal, statementText, ..., writeKind)
  //  原样直通一处 → new StatementRewrite(ordinal, statementText, statementText, false, false, writeKind)）
}
```

```java
// 3) 读语句分支末尾的 return 不变（走 5 参构造器，kind=SELECT）
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-core -am test`
Expected: 全部 PASS（含既有测试——旧构造器兼容，响应仅新增字段）。

- [ ] **Step 5: Commit**

```bash
git add mask-core/src/main/java/io/sqlmask/rewrite/StatementKind.java \
  mask-core/src/main/java/io/sqlmask/rewrite/RewriteEngine.java \
  mask-core/src/test/java/io/sqlmask/server/RewriteControllerTest.java
git commit -m "feat(rewrite): 语句结果携带 kind（SELECT/INSERT_SELECT/CTAS），为查询数据面只读判定供契约"
```

---

### Task 2: mask-core — 实例改写装配（快照 × 生效配置合并 + 上游服务配置）

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/config/source/InstanceQueryAssembler.java`
- Create: `mask-core/src/main/java/io/sqlmask/server/InstanceRewriteConfig.java`
- Modify: `mask-core/src/main/resources/application.yml`
- Test: `mask-core/src/test/java/io/sqlmask/config/source/InstanceQueryAssemblerTest.java`（新建）

**Interfaces:**
- Consumes: `MetadataClient.MetadataSnapshot(instance, dialect, metadataVersion, tables[catalog,schema,name,columns[name,type]])`；`ConfigSource.ResolvedConfig(LoadedConfig config, String dialect, long configVersion)`；`DialectProfiles.byName(name).typeResolver()`；`TableMetadata(catalog, schema, name, columns, rowFilter)`；`MaskingConfig(tables, columnPolicies, policies)`；`LoadedConfig.findTable(catalog, schema, table)`。
- Produces: `class InstanceQueryAssembler { LoadedConfig assemble(MetadataSnapshot snapshot, ResolvedConfig effective) }`；
  `InstanceRewriteConfig` 提供 bean `MetadataClient metadataClient()` 与
  `PolicySourceProvider policySourceProvider()`（`PolicySourceProvider.forInstance(String name) → PolicyServiceConfigSource`，按实例缓存）；
  配置键 `sqlmask.metadata-service.base-url|api-key`、`sqlmask.policy-service.base-url|api-key`。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.config.source;

import io.sqlmask.metadataclient.MetadataClient;
import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstanceQueryAssemblerTest {

  private final InstanceQueryAssembler assembler = new InstanceQueryAssembler();

  private static ConfigSource.ResolvedConfig effective(String dialect, String rowFilter) {
    // 用 YAML 走一遍既有装载器构造生效配置（等价于 policy service 下发后的形态）
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              %s
              columns:
                - { name: id, type: bigint }
                - { name: phone, type: varchar }
        columns:
          - { catalog: crm, schema: public, table: customer, column: phone, policy: m }
        policies:
          m: { udf: mask_phone, arguments: [3, 4] }
        """.formatted(rowFilter == null ? "" : "rowFilter: \"" + rowFilter + "\"");
    LoadedConfig loaded = new io.sqlmask.config.YamlConfigLoader().loadContent(
        yaml, "metadata.yaml", dialect);
    return new ConfigSource.ResolvedConfig(loaded, dialect);
  }

  private static MetadataClient.MetadataSnapshot snapshot(String dialect) {
    return new MetadataClient.MetadataSnapshot("pg_prod", dialect, 7,
        List.of(new MetadataClient.TableSnapshot("crm", "public", "customer",
            List.of(new MetadataClient.ColumnSnapshot("id", "bigint"),
                new MetadataClient.ColumnSnapshot("phone", "varchar"),
                // 快照里多出一张新表（生效配置尚未导入）
                new MetadataClient.TableSnapshot("crm", "public", "fresh",
                    List.of(new MetadataClient.ColumnSnapshot("id", "bigint")))),
            List.of()));
  }

  @Test
  void mergesFreshTablesWithEffectiveRowFilterAndPolicies() {
    LoadedConfig merged = assembler.assemble(snapshot("postgresql"),
        effective("postgresql", "status = 'active'"));
    assertThat(merged.tables()).extracting(t -> t.name()).containsExactly("customer", "fresh");
    assertThat(merged.findTable("crm", "public", "customer").orElseThrow().rowFilter())
        .isEqualTo("status = 'active'");
    assertThat(merged.findTable("crm", "public", "fresh").orElseThrow().rowFilter()).isNull();
    // 策略与列绑定原样保留
    assertThat(merged.config().config().policies()).containsKey("m");
    assertThat(merged.config().config().columnPolicies()).hasSize(1);
  }

  @Test
  void rejectsDialectMismatch() {
    assertThatThrownBy(() -> assembler.assemble(snapshot("mysql"), effective("postgresql", null)))
        .isInstanceOf(SqlMaskException.class)
        .hasMessageContaining("dialect");
  }
}
```

注意：若 `LoadedConfig`/`MaskingConfig` 的访问器路径与上面不一致（如
`merged.config().config()`），以 `EffectiveConfigAssembler` 用法为准
（`new MaskingConfig(tables, bindings, policies)`、`loaded.config().policies()`）。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-core -am test -Dtest=InstanceQueryAssemblerTest`
Expected: 编译失败 `InstanceQueryAssembler` 不存在。

- [ ] **Step 3: 实现**

`InstanceQueryAssembler.java`：

```java
package io.sqlmask.config.source;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.MaskingConfig;
import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.dialect.DialectProfiles;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.metadataclient.MetadataClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the rewrite-ready configuration for one query run: tables come fresh
 * from the metadata service snapshot, while row filters (which the policy
 * service bakes into its table payload) and the subject's column bindings and
 * policies come from the compiled effective config. A dialect disagreement
 * between the two services fails closed.
 */
@Component
public final class InstanceQueryAssembler {

  public LoadedConfig assemble(MetadataClient.MetadataSnapshot snapshot,
      ConfigSource.ResolvedConfig effective) {
    if (!snapshot.dialect().equals(effective.dialect())) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "instance '" + snapshot.instance() + "' reports dialect '" + snapshot.dialect()
              + "' but the effective config was compiled for '" + effective.dialect() + "'");
    }
    var typeResolver = DialectProfiles.byName(snapshot.dialect()).typeResolver();
    List<TableMetadata> tables = new ArrayList<>();
    int t = -1;
    for (MetadataClient.TableSnapshot ts : snapshot.tables()) {
      t++;
      String path = "instance '" + snapshot.instance() + "': tables[" + t + "] ("
          + ts.catalog() + "." + ts.schema() + "." + ts.name() + ")";
      List<TableMetadata.Column> columns = new ArrayList<>();
      try {
        for (MetadataClient.ColumnSnapshot cs : ts.columns()) {
          columns.add(typeResolver.parseColumn(cs.name(), cs.type()));
        }
      } catch (RuntimeException e) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, path + ": " + e.getMessage(), e);
      }
      String rowFilter = effective.config()
          .findTable(ts.catalog(), ts.schema(), ts.name())
          .map(TableMetadata::rowFilter).orElse(null);
      tables.add(new TableMetadata(ts.catalog(), ts.schema(), ts.name(), columns, rowFilter));
    }
    MaskingConfig effectiveConfig = effective.config().config();
    return new LoadedConfig(
        new MaskingConfig(tables, effectiveConfig.columnPolicies(), effectiveConfig.policies()));
  }
}
```

若 `TableMetadata.Column`/`typeResolver.parseColumn` 的实际签名与
`EffectiveConfigAssembler` 用法不同，以该文件为准对齐。

`InstanceRewriteConfig.java`：

```java
package io.sqlmask.server;

import io.sqlmask.config.source.PolicyServiceConfigSource;
import io.sqlmask.metadataclient.MetadataClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;

/** Service-mode wiring for the instance-scoped rewrite endpoint. */
@Configuration
@EnableConfigurationProperties(InstanceRewriteConfig.Upstreams.class)
public class InstanceRewriteConfig {

  @ConfigurationProperties(prefix = "sqlmask")
  public record Upstreams(Service metadataService, Service policyService) {
    public record Service(String baseUrl, String apiKey) {}
  }

  @Bean
  public MetadataClient instanceMetadataClient(Upstreams props) {
    require(props.metadataService(), "sqlmask.metadata-service.base-url");
    return new MetadataClient(props.metadataService().baseUrl(), props.metadataService().apiKey());
  }

  @Bean
  public PolicySourceProvider policySourceProvider(Upstreams props) {
    require(props.policyService(), "sqlmask.policy-service.base-url");
    return new PolicySourceProvider(props.policyService().baseUrl(), props.policyService().apiKey());
  }

  private static void require(Upstreams.Service service, String key) {
    if (service == null || service.baseUrl() == null || service.baseUrl().isBlank()) {
      throw new IllegalStateException(
          key + " must be configured for the instance-scoped rewrite endpoint");
    }
  }

  /** One cached PolicyServiceConfigSource per instance name (per-subject LRU lives inside). */
  public static final class PolicySourceProvider {
    private final String baseUrl;
    private final String apiKey;
    private final ConcurrentHashMap<String, PolicyServiceConfigSource> sources = new ConcurrentHashMap<>();

    public PolicySourceProvider(String baseUrl, String apiKey) {
      this.baseUrl = baseUrl;
      this.apiKey = apiKey;
    }

    public PolicyServiceConfigSource forInstance(String name) {
      return sources.computeIfAbsent(name, n -> new PolicyServiceConfigSource(baseUrl, apiKey, n));
    }
  }
}
```

说明：bean 缺配置时用 `IllegalStateException` 让上下文启动失败（配置错误应
在启动期暴露）；运行期端点判空见 Task 3（bean 不存在时端点 404 即可，不再重复校验）。

`application.yml` 追加：

```yaml
sqlmask:
  metadata-service:
    base-url: ${SQLMASK_METADATA_BASE_URL:}
    api-key: ${SQLMASK_METADATA_API_KEY:}
  policy-service:
    base-url: ${SQLMASK_POLICY_BASE_URL:}
    api-key: ${SQLMASK_POLICY_API_KEY:}
```

同时给 `InstanceRewriteConfig` 的两个 `@Bean` 加 `@ConditionalOnProperty`?
—— 不加：`require()` 在空串时抛异常会让**未配置服务模式**的默认部署直接启动失败，
违背兼容红线。改为：bean 返回惰性包装，校验推迟到调用时。落地：`MetadataClient`
与 `PolicySourceProvider` 改为在 `baseUrl` 为空白时也可构造（`MetadataClient`
构造本身允许任意串），把 `require(...)` 从 bean 工厂移到
`InstanceRewriteController` 调用路径（Task 3 第 3 步包含最终形态——本任务先按
上面代码落地 `require` 于工厂，Task 3 落控制器时把它移除并加端点判空，
本任务 Step 4 的全量测试证明默认上下文仍可启动）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-core -am test`
Expected: PASS，且既有 `ConfigControllerTest` 等默认上下文测试不受影响
（若因 bean 工厂 `require` 启动失败，按上面说明移到调用路径）。

- [ ] **Step 5: Commit**

```bash
git add mask-core/src/main/java/io/sqlmask/config/source/InstanceQueryAssembler.java \
  mask-core/src/main/java/io/sqlmask/server/InstanceRewriteConfig.java \
  mask-core/src/main/resources/application.yml \
  mask-core/src/test/java/io/sqlmask/config/source/InstanceQueryAssemblerTest.java
git commit -m "feat(core): 实例改写装配器（快照×生效配置合并）与服务模式上游配置"
```

---

### Task 3: mask-core — POST /api/rewrite/instances/{name} + 可选 API Key

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/server/InstanceRewriteController.java`
- Create: `mask-core/src/main/java/io/sqlmask/server/InstanceRewriteApiKeyFilter.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java`（注册过滤器）
- Test: `mask-core/src/test/java/io/sqlmask/server/InstanceRewriteEndpointTest.java`（新建）

**Interfaces:**
- Consumes: Task 2 的 `MetadataClient`、`PolicySourceProvider`、`InstanceQueryAssembler`；
  `RewriteEngine.rewrite(LoadedConfig, String sqlText, String dialectName)`；
  `RewriteController.RewriteResponse`；`Subject.of(user, groups)`。
- Produces: `POST /api/rewrite/instances/{name}`，请求
  `{ "sql": "...", "user": "...", "groups": [...] }`，响应同 `/api/rewrite`
  （statements 带 `kind`）。错误：sql 空白 → `CONFIG_ERROR`；上游服务未配置 →
  `CONFIG_ERROR`；其余透传 MetadataClient/PolicyServiceConfigSource 的
  `SqlMaskException.Code`（`METADATA_INSTANCE_NOT_FOUND`、
  `METADATA_SERVICE_UNAVAILABLE`、`POLICY_SERVICE_UNAVAILABLE` 等）。
  环境变量 `SQLMASK_REWRITE_API_KEY` 非空时该路径要求 `X-Api-Key`。

- [ ] **Step 1: 写失败测试**

用 JDK 内置 `com.sun.net.httpserver.HttpServer` 桩掉 metadata/policy 两个上游
（无新依赖）。注意 `PolicyServiceConfigSource` 调
`GET {base}/api/effective/{instance}?user=...&groups=...`，先读该类确认路径与
响应字段（`EffectiveConfigResponse` 的 JSON 形状），桩按其真实契约返回；下面
`EFFECTIVE_JSON` 占位以实际字段为准填写（本步骤动手前先读
`config/source/EffectiveConfigResponse.java`）。

```java
package io.sqlmask.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "sqlmask.metadata-service.base-url=http://127.0.0.1:${stub.port}/meta",
    "sqlmask.metadata-service.api-key=meta-key",
    "sqlmask.policy-service.base-url=http://127.0.0.1:${stub.port}/policy",
    "sqlmask.policy-service.api-key=policy-key"
})
@AutoConfigureMockMvc
class InstanceRewriteEndpointTest {

  static HttpServer stub;
  static volatile String effectiveJson;

  @BeforeAll
  static void startStub(@Autowired ObjectMapper json) throws Exception {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/meta/api/instances/pg_prod", exchange -> {
      byte[] body = """
          {"name":"pg_prod","dialect":"postgresql","engine":null,"metadataVersion":7,
           "connection":null,
           "tables":[{"catalog":"crm","schema":"public","name":"customer",
             "columns":[{"name":"id","type":"bigint"},{"name":"phone","type":"varchar"}]}]}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
    });
    stub.createContext("/policy/api/effective/pg_prod", exchange -> {
      byte[] body = effectiveJson.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
    });
    stub.start();
    System.setProperty("stub.port", String.valueOf(stub.getAddress().getPort()));
  }

  @AfterAll
  static void stopStub() { stub.stop(0); System.clearProperty("stub.port"); }

  @Autowired MockMvc mockMvc;

  @Test
  void rewritesAgainstInstance() throws Exception {
    effectiveJson = """
        {"instance":"pg_prod","dialect":"postgresql","configVersion":3,
         "policySummary":{"enabled":1,"disabled":0},
         "config":{
           "metadata":{"tables":[{"catalog":"crm","schema":"public","name":"customer",
             "rowFilter":null,
             "columns":[{"name":"id","type":"bigint"},{"name":"phone","type":"varchar"}]}]},
           "columns":[{"catalog":"crm","schema":"public","table":"customer",
             "column":"phone","policy":"m"}],
           "policies":{"m":{"udf":"mask_phone","arguments":[3,4]}}}}
        """;
    mockMvc.perform(post("/api/rewrite/instances/pg_prod")
            .contentType("application/json")
            .content("{\"sql\":\"SELECT phone FROM customer\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statements[0].kind").value("SELECT"))
        .andExpect(jsonPath("$.statements[0].masked").value(true))
        .andExpect(jsonPath("$.rewrittenSql").value(
            org.hamcrest.Matchers.containsString("mask_phone")));
  }

  @Test
  void missingInstancePropagates404Code() throws Exception {
    effectiveJson = "{}";
    mockMvc.perform(post("/api/rewrite/instances/missing")
            .contentType("application/json").content("{\"sql\":\"SELECT 1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("METADATA_INSTANCE_NOT_FOUND"));
  }
}
```

`${stub.port}` 占位符在 `@SpringBootTest(properties)` 里不可用动态值——落地时改用
`@DynamicPropertySource`：

```java
static String stubBase() { return "http://127.0.0.1:" + stub.getAddress().getPort(); }
@DynamicPropertySource
static void upstreams(org.springframework.test.context.DynamicPropertyRegistry r) {
  r.register("sqlmask.metadata-service.base-url", () -> stubBase() + "/meta");
  r.register("sqlmask.metadata-service.api-key", () -> "meta-key");
  r.register("sqlmask.policy-service.base-url", () -> stubBase() + "/policy");
  r.register("sqlmask.policy-service.api-key", () -> "policy-key");
}
```

（`@BeforeAll` 只负责启动 stub；两个测试方法在 `@BeforeAll` 之后执行，
`@DynamicPropertySource` 在上下文创建前调用——因此 stub 必须在静态初始化或
`@DynamicPropertySource` 里启动：把 `startStub()` 挪到 `static { }` 块。）

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-core -am test -Dtest=InstanceRewriteEndpointTest`
Expected: FAIL —— 404（端点不存在）。

- [ ] **Step 3: 实现**

`InstanceRewriteController.java`：

```java
package io.sqlmask.server;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.source.ConfigSource;
import io.sqlmask.config.source.InstanceQueryAssembler;
import io.sqlmask.config.source.PolicyServiceConfigSource;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadataclient.MetadataClient;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.rewrite.RewriteEngine;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Instance-scoped rewrite for the query data plane: metadata tables come from
 * the metadata service snapshot, policies from the subject's compiled
 * effective config; the response adds per-statement {@code kind} so callers
 * can enforce read-only data planes.
 */
@RestController
@RequestMapping("/api/rewrite/instances")
public class InstanceRewriteController {

  private final RewriteEngine engine;
  private final MetadataClient metadataClient;
  private final InstanceRewriteConfig.PolicySourceProvider policySources;
  private final InstanceQueryAssembler assembler;

  public InstanceRewriteController(RewriteEngine engine, MetadataClient metadataClient,
      InstanceRewriteConfig.PolicySourceProvider policySources, InstanceQueryAssembler assembler) {
    this.engine = engine;
    this.metadataClient = metadataClient;
    this.policySources = policySources;
    this.assembler = assembler;
  }

  @PostMapping("/{name}")
  public RewriteController.RewriteResponse rewrite(@PathVariable("name") String name,
      @RequestBody InstanceRewriteRequest request) {
    if (request == null || request.sql() == null || request.sql().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "sql is required");
    }
    MetadataClient.MetadataSnapshot snapshot = metadataClient.fetch(name);
    PolicyServiceConfigSource source = policySources.forInstance(name);
    ConfigSource.ResolvedConfig effective = source.load(Subject.of(request.user(), request.groups()));
    LoadedConfig loaded = assembler.assemble(snapshot, effective);
    List<StatementRewrite> statements = engine.rewrite(loaded, request.sql(), snapshot.dialect());
    return new RewriteController.RewriteResponse(statements, RewriteEngine.join(statements));
  }

  public record InstanceRewriteRequest(String sql, String user, java.util.List<String> groups) {}
}
```

（`RewriteController.RewriteResponse` 若为私有可见性则改为 public——检查后如已是
public record 则不动。）

`InstanceRewriteApiKeyFilter.java`（**可选** key：未配置放行，与 fail-closed 相反）：

```java
package io.sqlmask.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** Optional API-key gate over /api/rewrite/instances/*. Unconfigured = open
 * (backward compatible); configured = enforced with the metadata server's
 * fail-closed JSON error shape. */
public class InstanceRewriteApiKeyFilter implements Filter {

  private final String expectedKey;

  public InstanceRewriteApiKeyFilter(String expectedKey) {
    this.expectedKey = expectedKey;
  }

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    if (expectedKey == null || expectedKey.isBlank()) {
      chain.doFilter(req, res);
      return;
    }
    HttpServletRequest request = (HttpServletRequest) req;
    HttpServletResponse response = (HttpServletResponse) res;
    if (!expectedKey.equals(request.getHeader("X-Api-Key"))) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write(
          "{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid API key\",\"details\":[]}");
      return;
    }
    chain.doFilter(req, res);
  }
}
```

`SqlMaskServiceApplication` 追加 bean：

```java
@Bean
org.springframework.boot.web.servlet.FilterRegistrationBean<InstanceRewriteApiKeyFilter>
instanceRewriteApiKeyFilter() {
  var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
      new InstanceRewriteApiKeyFilter(System.getenv("SQLMASK_REWRITE_API_KEY")));
  registration.addUrlPatterns("/api/rewrite/instances/*");
  registration.setOrder(2);
  return registration;
}
```

（Task 2 留下的收尾：确认 `InstanceRewriteConfig` 工厂里的 `require` 已移除，
改为控制器判空——`metadataClient`/`policySources` 为 null 时（未配置服务模式）
抛 `CONFIG_ERROR "instance-scoped rewrite requires sqlmask.metadata-service.base-url
and sqlmask.policy-service.base-url"`。用 `ObjectProvider<MetadataClient>` 或让
bean 在空白时返回 null 并以 `@Autowired(required = false)` 注入，二选一落地，
测试补一条「未配置 → CONFIG_ERROR」。）

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-core -am test`
Expected: PASS（含「未配置服务模式 → CONFIG_ERROR」与默认上下文不受影响）。

- [ ] **Step 5: Commit**

```bash
git add mask-core/src/main/java/io/sqlmask/server/InstanceRewriteController.java \
  mask-core/src/main/java/io/sqlmask/server/InstanceRewriteApiKeyFilter.java \
  mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java \
  mask-core/src/test/java/io/sqlmask/server/InstanceRewriteEndpointTest.java \
  mask-core/src/main/java/io/sqlmask/server/InstanceRewriteConfig.java
git commit -m "feat(core): 按实例改写端点（元数据快照+按主体生效配置）与可选 API Key 门"
```

---

### Task 4: mask-metadata — 实例 engine 字段（支持 starrocks）

**Files:**
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/model/InstanceRow.java`
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/service/MetadataService.java`
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataDtos.java`
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataAdminController.java`
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/store/JdbcMetaStore.java`
- Modify: `mask-metadata/src/main/resources/metadata-schema.sql`
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/service/MetadataServiceTest.java`（追加）、
  `store/JdbcMetaStoreTest.java`（追加）、`store/InMemoryMetaStore.java`（同步）

**Interfaces:**
- Produces: `InstanceRow(name, dialect, engine, connection, metadataVersion)`
  （`engine` 可空；`effectiveEngine()` = engine 非空取 engine，否则由 dialect
  推导 postgresql→postgresql / trino→trino / mysql→mysql）。
  规范化规则：engine 空白 → null；非空必须为 `starrocks` 且 dialect 必须为
  `mysql`，否则 `CONFIG_ERROR`。DTO `InstanceCreateRequest` 加可选 `engine`，
  `InstanceSummaryResponse`/`InstanceDetailResponse` 加 `engine`（取
  effectiveEngine）。存储列 `meta_instance.engine VARCHAR(64)`。

- [ ] **Step 1: 写失败测试**

`MetadataServiceTest` 追加：

```java
@Test
void engineBlankDerivesNullAndStarrocksRequiresMysqlDialect() {
  InstanceRow pg = service.create("pg1", "postgresql", "  ", null);
  assertThat(pg.engine()).isNull();
  assertThat(pg.effectiveEngine()).isEqualTo("postgresql");

  InstanceRow sr = service.create("sr1", "mysql", "STARROCKS", null);
  assertThat(sr.engine()).isEqualTo("starrocks");
  assertThat(sr.effectiveEngine()).isEqualTo("starrocks");

  assertThatThrownBy(() -> service.create("bad1", "mysql", "hive", null))
      .isInstanceOf(SqlMaskException.class)
      .hasMessageContaining("unsupported engine");
  assertThatThrownBy(() -> service.create("bad2", "postgresql", "starrocks", null))
      .isInstanceOf(SqlMaskException.class)
      .hasMessageContaining("requires dialect 'mysql'");
}
```

（构造签名以类内现有 `service.create(...)` 调用为准插入 engine 实参。）

`JdbcMetaStoreTest` 追加 round-trip：

```java
@Test
void engineRoundTripsThroughStore() {
  ConnectionInfo conn = new ConnectionInfo("h", 9030, "db", "u", "REF", "disable", 5, List.of(), false);
  store.createInstance(new InstanceRow("sr-eng", "mysql", "starrocks", conn, 1));
  assertEquals("starrocks", store.findInstance("sr-eng").orElseThrow().engine());
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-metadata -am test -Dtest='MetadataServiceTest,JdbcMetaStoreTest'`
Expected: 编译失败（构造器无 engine 参）。

- [ ] **Step 3: 实现**

`InstanceRow`：

```java
/** Stored instance: identity + dialect + optional engine override (currently
 * only 'starrocks'; otherwise derived from the dialect) + optional connection
 * group + version anchor. */
public record InstanceRow(String name, String dialect, String engine, ConnectionInfo connection,
    long metadataVersion) {

  public InstanceRow withVersion(long newVersion) {
    return new InstanceRow(name, dialect, engine, connection, newVersion);
  }

  /** engine 非空取 engine，否则由 dialect 推导（与 QueryEngine 目录一致）。 */
  public String effectiveEngine() {
    if (engine != null && !engine.isBlank()) {
      return engine;
    }
    return switch (dialect) {
      case "postgresql" -> "postgresql";
      case "trino" -> "trino";
      default -> "mysql";
    };
  }
}
```

`MetadataService`：`create(String name, String dialect, String engine, ConnectionInfo connection)`
加规范化：

```java
private static String normalizeEngine(String engine, String normalizedDialect) {
  if (engine == null || engine.isBlank()) {
    return null;
  }
  String normalized = engine.trim().toLowerCase(java.util.Locale.ROOT);
  if (!normalized.equals("starrocks")) {
    throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "unsupported engine '" + engine + "' (engines are dialect-derived; "
            + "the only explicit engine is starrocks)");
  }
  if (!normalizedDialect.equals("mysql")) {
    throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "engine 'starrocks' requires dialect 'mysql'");
  }
  return normalized;
}
```

所有 `new InstanceRow(...)` 构造点补 engine 实参（`MetadataService`、
`JdbcMetaStore.INSTANCE_ROW`、测试 `InMemoryMetaStore` 若有构造）。更新实例的
engine 与 dialect 同为不可变（`updateConnection` 不碰）。

`JdbcMetaStore`：INSERT 列清单加 `engine`；SELECT 加 `engine`；
`INSTANCE_ROW` 映射 `rs.getString("engine")`。

`metadata-schema.sql`：`dialect VARCHAR(64) NOT NULL,` 下加
`engine VARCHAR(64),`，并加注释：
`-- 存量部署需手动: ALTER TABLE meta_instance ADD COLUMN engine VARCHAR(64);`
（`CREATE TABLE IF NOT EXISTS` 不会为既有表补列，与部署文档一致。）

`MetadataDtos`：`InstanceCreateRequest(+String engine)`、
`InstanceSummaryResponse(+String engine)`、`InstanceDetailResponse(+String engine)`。
`MetadataAdminController`：create 传 `request.engine()`；summary/detail 用
`row.effectiveEngine()`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-metadata -am test`
Expected: PASS（含 `MetadataAdminControllerTest`/`DataPlaneContractTest` 若因
DTO 字段增加需补期望值，一并修正——JSON 响应新增字段，既有断言按新字段补全）。

- [ ] **Step 5: Commit**

```bash
git add mask-metadata
git commit -m "feat(metadata): 实例 engine 字段（starrocks 显式声明，dialect 派生兜底）"
```

---

### Task 5: mask-query — 模块骨架（pom + 应用 + 配置）

**Files:**
- Create: `mask-query/pom.xml`、`mask-query/src/main/java/io/sqlmask/query/QueryServerApplication.java`、
  `mask-query/src/main/java/io/sqlmask/query/config/QueryProperties.java`、
  `mask-query/src/main/java/io/sqlmask/query/config/UpstreamProperties.java`、
  `mask-query/src/main/resources/application.yml`
- Modify: `pom.xml`（根，`<modules>` 加 `mask-query`）
- Test: `mask-query/src/test/java/io/sqlmask/query/QueryServerApplicationTest.java`

**Interfaces:**
- Produces:
  `QueryProperties(Integer timeoutSeconds, Integer maxRows, Integer maxRowsHard, Integer fetchSize, Integer maxConcurrentPerInstance)`
  with accessors `timeoutSeconds()=30, maxRows()=1000, maxRowsHard()=10000, fetchSize()=500, maxConcurrentPerInstance()=10`（null/非正归一）；
  `UpstreamProperties(String metadataBaseUrl, String metadataApiKey, String rewriteBaseUrl, String rewriteApiKey)`，前缀 `upstream`。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.query;

import io.sqlmask.query.config.QueryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class QueryServerApplicationTest {

  @Autowired QueryProperties props;

  @Test
  void contextLoadsWithDefaults() {
    assertThat(props.timeoutSeconds()).isEqualTo(30);
    assertThat(props.maxRows()).isEqualTo(1000);
    assertThat(props.maxRowsHard()).isEqualTo(10000);
    assertThat(props.fetchSize()).isEqualTo(500);
    assertThat(props.maxConcurrentPerInstance()).isEqualTo(10);
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-query -am test`
Expected: 模块不存在（先建骨架再跑；此步在 Step 3 落地后以「先跑测试类」顺序成立——
实现时先写 pom+最小应用类，再写测试，跑红改绿以 Step 4 为准）。

- [ ] **Step 3: 实现**

`pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.sqlmask</groupId>
    <artifactId>sql-mask-parent</artifactId>
    <version>${revision}</version>  <!-- 与兄弟模块一致；打开 mask-core/pom.xml 照抄实际版本表达 -->
  </parent>
  <artifactId>mask-query</artifactId>

  <dependencies>
    <dependency>
      <groupId>io.sqlmask</groupId>
      <artifactId>mask-audit</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
    </dependency>
    <dependency>
      <groupId>io.trino</groupId>
      <artifactId>trino-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <!-- 照抄 mask-metadata/pom.xml 的 spring-boot-maven-plugin repackage 配置 -->
    </plugins>
  </build>
</project>
```

版本一律来自父 pom dependencyManagement（兄弟模块都不写版本号）；mask-audit
依赖坐标照 mask-core 对 mask-audit 的写法（若父 pom 已管理则不写版本）。

`QueryServerApplication`：

```java
package io.sqlmask.query;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class QueryServerApplication {
  public static void main(String[] args) {
    SpringApplication.run(QueryServerApplication.class, args);
  }
}
```

`QueryProperties`：

```java
package io.sqlmask.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Query guardrails; non-positive or missing values fall back to defaults. */
@ConfigurationProperties(prefix = "query")
public record QueryProperties(Integer timeoutSeconds, Integer maxRows, Integer maxRowsHard,
    Integer fetchSize, Integer maxConcurrentPerInstance) {

  public QueryProperties {
    if (timeoutSeconds == null || timeoutSeconds <= 0) timeoutSeconds = 30;
    if (maxRows == null || maxRows <= 0) maxRows = 1000;
    if (maxRowsHard == null || maxRowsHard <= 0) maxRowsHard = 10000;
    if (fetchSize == null || fetchSize <= 0) fetchSize = 500;
    if (maxConcurrentPerInstance == null || maxConcurrentPerInstance <= 0) {
      maxConcurrentPerInstance = 10;
    }
  }
}
```

`UpstreamProperties`：

```java
package io.sqlmask.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Upstream service locations; blank base URLs fail the query path closed. */
@ConfigurationProperties(prefix = "upstream")
public record UpstreamProperties(String metadataBaseUrl, String metadataApiKey,
    String rewriteBaseUrl, String rewriteApiKey) {}
```

`application.yml`：

```yaml
server:
  port: 8083

spring:
  application:
    name: mask-query

upstream:
  metadata-base-url: ${SQLMASK_METADATA_BASE_URL:}
  metadata-api-key: ${SQLMASK_METADATA_API_KEY:}
  rewrite-base-url: ${SQLMASK_REWRITE_BASE_URL:}
  rewrite-api-key: ${SQLMASK_REWRITE_API_KEY:}
```

根 `pom.xml` `<modules>` 追加 `<module>mask-query</module>`（插在 mask-audit
之后）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-query -am test`
Expected: PASS（context 启动 + 默认值绑定）。

- [ ] **Step 5: Commit**

```bash
git add pom.xml mask-query
git commit -m "feat(query): mask-query 模块骨架（8083，护栏与上游配置）"
```

---

### Task 6: mask-query — 错误模型 + 鉴权 + 异常处理

**Files:**
- Create: `mask-query/src/main/java/io/sqlmask/query/error/QueryException.java`
- Create: `mask-query/src/main/java/io/sqlmask/query/web/QueryApiKeyFilter.java`
- Create: `mask-query/src/main/java/io/sqlmask/query/web/ApiExceptionHandler.java`
- Test: `mask-query/src/test/java/io/sqlmask/query/web/QueryApiKeyFilterTest.java`、
  `web/ApiExceptionHandlerTest.java`

**Interfaces:**
- Produces: `QueryException(String code, String message[, Throwable cause[, boolean rewritePhase]])`
  （code 为字符串：自有码常量直接定义在 `QueryException` 上，或上游透传码原样）：
  `CONFIG_ERROR, INSTANCE_NOT_FOUND, INSTANCE_NOT_EXECUTABLE, MULTI_STATEMENT,
  WRITE_STATEMENT, QUERY_BUSY, QUERY_TIMEOUT, QUERY_ERROR, CREDENTIAL_UNAVAILABLE,
  REWRITE_SERVICE_UNAVAILABLE, UNSUPPORTED_ENGINE, UNAUTHORIZED`。
  `QueryApiKeyFilter`（`/api/*`，fail-closed：`SQLMASK_QUERY_API_KEY` 未配置全 401）。
  `ApiExceptionHandler`：`QueryException → 400 {code,message,details:[]}`。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.query.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class QueryApiKeyFilterTest {

  private final QueryApiKeyFilter open = new QueryApiKeyFilter(null);
  private final QueryApiKeyFilter guarded = new QueryApiKeyFilter("k1");

  @Test
  void unconfiguredKeyFailsClosed() throws Exception {
    MockHttpServletResponse res = new MockHttpServletResponse();
    open.doFilter(new MockHttpServletRequest("POST", "/api/v1/query"), res, new MockFilterChain());
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void configuredKeyAcceptsOnlyMatch() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/query");
    req.addHeader("X-Api-Key", "k1");
    MockFilterChain chain = new MockFilterChain();
    guarded.doFilter(req, new MockHttpServletResponse(), chain);
    assertThat(chain.getRequest()).isNotNull();
  }
}
```

```java
package io.sqlmask.query.web;

import io.sqlmask.query.error.QueryException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

  @Test
  void mapsQueryExceptionToPayload() {
    var handler = new ApiExceptionHandler();
    var response = handler.queryException(new QueryException("QUERY_BUSY", "busy"));
    assertThat(response.getStatusCode().value()).isEqualTo(400);
    assertThat(response.getBody()).containsEntry("code", "QUERY_BUSY")
        .containsEntry("message", "busy").containsKey("details");
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-query -am test -Dtest='QueryApiKeyFilterTest,ApiExceptionHandlerTest'`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`QueryException`：

```java
package io.sqlmask.query.error;

/** Fatal data-plane error with a machine-readable code: either one of this
 * service's own codes or a passthrough code from an upstream response. */
public class QueryException extends RuntimeException {

  /** Codes owned by mask-query; upstream codes pass through verbatim. */
  public static final String CONFIG_ERROR = "CONFIG_ERROR";
  public static final String INSTANCE_NOT_FOUND = "INSTANCE_NOT_FOUND";
  public static final String INSTANCE_NOT_EXECUTABLE = "INSTANCE_NOT_EXECUTABLE";
  public static final String MULTI_STATEMENT = "MULTI_STATEMENT";
  public static final String WRITE_STATEMENT = "WRITE_STATEMENT";
  public static final String QUERY_BUSY = "QUERY_BUSY";
  public static final String QUERY_TIMEOUT = "QUERY_TIMEOUT";
  public static final String QUERY_ERROR = "QUERY_ERROR";
  public static final String CREDENTIAL_UNAVAILABLE = "CREDENTIAL_UNAVAILABLE";
  public static final String REWRITE_SERVICE_UNAVAILABLE = "REWRITE_SERVICE_UNAVAILABLE";
  public static final String UNSUPPORTED_ENGINE = "UNSUPPORTED_ENGINE";

  private final String code;
  /** True when the failure happened at or before the rewrite call — the
   * rewrite (REWRITE) audit event belongs to mask-core, so mask-query stays
   * silent. */
  private final boolean rewritePhase;

  public QueryException(String code, String message) {
    this(code, message, null);
  }

  public QueryException(String code, String message, Throwable cause) {
    this(code, message, cause, false);
  }

  public QueryException(String code, String message, Throwable cause, boolean rewritePhase) {
    super(message, cause);
    this.code = code;
    this.rewritePhase = rewritePhase;
  }

  public String code() { return code; }

  public boolean rewritePhase() { return rewritePhase; }
}
```

`QueryApiKeyFilter`：逐行照抄 mask-metadata 的 `ApiKeyFilter`（相同 JSON 形状），
仅类名不同。`ApiExceptionHandler`：

```java
package io.sqlmask.query.web;

import io.sqlmask.query.error.QueryException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(QueryException.class)
  public ResponseEntity<Map<String, Object>> queryException(QueryException e) {
    return ResponseEntity.badRequest().body(Map.of(
        "code", e.code(),
        "message", e.getMessage() == null ? "" : e.getMessage(),
        "details", List.of()));
  }
}
```

`QueryApiKeyFilter` bean 注册在 `QueryServerApplication`：

```java
@Bean
org.springframework.boot.web.servlet.FilterRegistrationBean<QueryApiKeyFilter> queryApiKeyFilter() {
  var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
      new QueryApiKeyFilter(System.getenv("SQLMASK_QUERY_API_KEY")));
  registration.addUrlPatterns("/api/*");
  registration.setOrder(1);
  return registration;
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-query -am test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add mask-query
git commit -m "feat(query): 错误模型（自有码+上游透传码）、fail-closed 鉴权与异常映射"
```

---

### Task 7: mask-query — 实例目录客户端（MetadataServiceClient）

**Files:**
- Create: `mask-query/src/main/java/io/sqlmask/query/metadata/MetadataServiceClient.java`
- Test: `mask-query/src/test/java/io/sqlmask/query/metadata/MetadataServiceClientTest.java`

**Interfaces:**
- Consumes: `UpstreamProperties.metadataBaseUrl/metadataApiKey`；mask-metadata 管理面
  `GET /api/instances/{name}`（响应 `InstanceDetailResponse` JSON：name, dialect,
  engine, metadataVersion, connection{host,port,database,dbUser,passwordRef,sslmode,...}）。
- Produces: `InstanceView fetch(String instance)`；
  `record InstanceView(String name, String engine, String dialect, long metadataVersion,
  ConnectionView connection)`；`record ConnectionView(String host, int port, String
  database, String dbUser, String passwordRef, String sslmode, int connectTimeoutSeconds)`。
  错误映射：404 → `INSTANCE_NOT_FOUND`；401 → `CONFIG_ERROR`（"metadata service
  rejected the configured API key"）；超时/非 200/解析失败 →
  `METADATA_SERVICE_UNAVAILABLE`（透传码）。engine 为 null 时按 dialect 推导
  （postgresql→postgresql / trino→trino / mysql→mysql），与 mask-metadata 的
  `effectiveEngine()` 同规则。

- [ ] **Step 1: 写失败测试**

用 JDK `HttpServer` 桩（同 Task 3 模式）：

```java
package io.sqlmask.query.metadata;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataServiceClientTest {

  static HttpServer stub;
  static MetadataServiceClient client;
  static volatile int status = 200;
  static volatile String body = "{}";

  @BeforeAll
  static void start() throws Exception {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/api/instances/pg_prod", exchange -> {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var out = exchange.getResponseBody()) { out.write(bytes); }
    });
    stub.start();
    client = new MetadataServiceClient(
        "http://127.0.0.1:" + stub.getAddress().getPort(), "k");
  }

  @AfterAll
  static void stop() { stub.stop(0); }

  @Test
  void parsesDetailAndDerivesEngine() {
    status = 200;
    body = """
        {"name":"pg_prod","dialect":"postgresql","engine":null,"metadataVersion":3,
         "connection":{"host":"h","port":5432,"database":"crm","dbUser":"u",
           "passwordRef":"REF","sslmode":"disable","connectTimeoutSeconds":5}}
        """;
    InstanceView view = client.fetch("pg_prod");
    assertThat(view.engine()).isEqualTo("postgresql");
    assertThat(view.connection().port()).isEqualTo(5432);
  }

  @Test
  void maps404And401() {
    status = 404; body = "{}";
    assertThatThrownBy(() -> client.fetch("pg_prod"))
        .hasFieldOrPropertyWithValue("code", "INSTANCE_NOT_FOUND");
    status = 401; body = "{}";
    assertThatThrownBy(() -> client.fetch("pg_prod"))
        .hasFieldOrPropertyWithValue("code", "CONFIG_ERROR")
        .hasMessageContaining("API key");
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-query -am test -Dtest=MetadataServiceClientTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```java
package io.sqlmask.query.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.query.error.QueryException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Pulls one instance view from the metadata service admin plane. Fail-closed:
 * anything but a 200 detail payload becomes an exception; the password never
 * leaves this call chain (it is read later, only for building the JDBC
 * connection). */
public final class MetadataServiceClient {

  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http;
  private final URI base;
  private final String apiKey;

  public MetadataServiceClient(String baseUrl, String apiKey) {
    this.base = URI.create(baseUrl);
    this.apiKey = apiKey;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  public InstanceView fetch(String instance) {
    HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/instances/" + instance))
        .header("Accept", "application/json")
        .header("X-Api-Key", apiKey == null ? "" : apiKey)
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new QueryException("METADATA_SERVICE_UNAVAILABLE",
          "metadata service unreachable at '" + request.uri() + "'", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new QueryException("METADATA_SERVICE_UNAVAILABLE",
          "interrupted while calling the metadata service", e);
    }
    if (response.statusCode() == 404) {
      throw new QueryException(QueryException.INSTANCE_NOT_FOUND,
          "instance '" + instance + "' does not exist on the metadata service");
    }
    if (response.statusCode() == 401) {
      throw new QueryException(QueryException.CONFIG_ERROR,
          "metadata service rejected the configured API key (HTTP 401)");
    }
    if (response.statusCode() != 200) {
      throw new QueryException("METADATA_SERVICE_UNAVAILABLE",
          "metadata service returned HTTP " + response.statusCode()
              + " for instance '" + instance + "'");
    }
    try {
      JsonNode node = JSON.readTree(response.body());
      JsonNode connection = node.get("connection");
      return new InstanceView(
          node.path("name").asText(instance),
          effectiveEngine(node.path("engine").isMissingNode() ? null : textOrNull(node.get("engine")),
              node.path("dialect").asText(null)),
          node.path("dialect").asText(null),
          node.path("metadataVersion").asLong(0),
          connection == null || connection.isNull() ? null : new ConnectionView(
              connection.path("host").asText(),
              connection.path("port").asInt(),
              connection.path("database").asText(),
              connection.path("dbUser").asText(),
              connection.path("passwordRef").asText(),
              connection.path("sslmode").asText("disable"),
              connection.path("connectTimeoutSeconds").asInt(10)));
    } catch (IOException e) {
      throw new QueryException("METADATA_SERVICE_UNAVAILABLE",
          "metadata service returned an unreadable instance payload", e);
    }
  }

  private static String textOrNull(JsonNode node) {
    return node == null || node.isNull() ? null : node.asText();
  }

  static String effectiveEngine(String engine, String dialect) {
    if (engine != null && !engine.isBlank()) {
      return engine;
    }
    return switch (dialect == null ? "" : dialect) {
      case "postgresql" -> "postgresql";
      case "trino" -> "trino";
      default -> "mysql";
    };
  }

  public record InstanceView(String name, String engine, String dialect, long metadataVersion,
      ConnectionView connection) {}

  public record ConnectionView(String host, int port, String database, String dbUser,
      String passwordRef, String sslmode, int connectTimeoutSeconds) {}
}
```

bean 装配：`QueryServerApplication` 加

```java
@Bean
MetadataServiceClient metadataServiceClient(UpstreamProperties props) {
  return new MetadataServiceClient(props.metadataBaseUrl(), props.metadataApiKey());
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-query -am test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add mask-query
git commit -m "feat(query): 实例目录客户端（engine 推导、fail-closed 错误映射）"
```

---

### Task 8: mask-query — 改写服务客户端（RewriteServiceClient）

**Files:**
- Create: `mask-query/src/main/java/io/sqlmask/query/rewrite/RewriteServiceClient.java`
- Test: `mask-query/src/test/java/io/sqlmask/query/rewrite/RewriteServiceClientTest.java`

**Interfaces:**
- Consumes: `UpstreamProperties.rewriteBaseUrl/rewriteApiKey`；mask-core
  `POST /api/rewrite/instances/{name}`（响应 `statements[].kind/masked/rowFiltered/rewrittenSql`）。
- Produces: `RewrittenQuery rewrite(String instance, String sql, String user,
  List<String> groups)`；`record RewrittenQuery(List<StatementView> statements)`；
  `record StatementView(int ordinal, String originalSql, String rewrittenSql, boolean
  masked, boolean rowFiltered, String kind)`。
  错误映射：2xx 解析；4xx → `QueryException(响应体 code 原样, message,
  cause=null, rewritePhase=true)`；5xx/不可达/解析失败 →
  `REWRITE_SERVICE_UNAVAILABLE`（`rewritePhase=true`）。

- [ ] **Step 1: 写失败测试**

（桩同 Task 7 模式，`POST /api/rewrite/instances/x`：）

```java
@Test
void parsesStatementsAndPassthroughErrors() {
  status = 200;
  body = """
      {"statements":[{"ordinal":1,"originalSql":"SELECT 1","rewrittenSql":"SELECT 1",
        "masked":false,"rowFiltered":false,"kind":"SELECT"}],"rewrittenSql":"SELECT 1;"}
      """;
  RewrittenQuery result = client.rewrite("x", "SELECT 1", null, List.of());
  assertThat(result.statements()).hasSize(1);
  assertThat(result.statements().get(0).kind()).isEqualTo("SELECT");

  status = 400;
  body = "{\"code\":\"POLICY_SERVICE_UNAVAILABLE\",\"message\":\"down\"}";
  assertThatThrownBy(() -> client.rewrite("x", "SELECT 1", null, List.of()))
      .isInstanceOf(QueryException.class)
      .hasFieldOrPropertyWithValue("code", "POLICY_SERVICE_UNAVAILABLE")
      .matches(e -> ((QueryException) e).rewritePhase());

  status = 500; body = "boom";
  assertThatThrownBy(() -> client.rewrite("x", "SELECT 1", null, List.of()))
      .hasFieldOrPropertyWithValue("code", "REWRITE_SERVICE_UNAVAILABLE");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-query -am test -Dtest=RewriteServiceClientTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```java
package io.sqlmask.query.rewrite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.query.error.QueryException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** Calls mask-core's instance-scoped rewrite endpoint. Every failure before a
 * usable statement list is marked {@code rewritePhase} so the caller's audit
 * boundary can stay silent (mask-core already emitted REWRITE). */
public final class RewriteServiceClient {

  private static final ObjectMapper JSON = new ObjectMapper();
  private final HttpClient http;
  private final URI base;
  private final String apiKey;

  public RewriteServiceClient(String baseUrl, String apiKey) {
    this.base = URI.create(baseUrl);
    this.apiKey = apiKey;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  public RewrittenQuery rewrite(String instance, String sql, String user, List<String> groups) {
    String payload;
    try {
      payload = JSON.writeValueAsString(java.util.Map.of(
          "sql", sql, "user", user == null ? "" : user,
          "groups", groups == null ? List.of() : groups));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    HttpRequest request = HttpRequest.newBuilder(
            URI.create(base + "/api/rewrite/instances/" + instance))
        .header("Content-Type", "application/json")
        .header("X-Api-Key", apiKey == null ? "" : apiKey)
        .timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new QueryException(QueryException.REWRITE_SERVICE_UNAVAILABLE,
          "rewrite service unreachable at '" + request.uri() + "'", e, true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new QueryException(QueryException.REWRITE_SERVICE_UNAVAILABLE,
          "interrupted while calling the rewrite service", e, true);
    }
    if (response.statusCode() >= 400 && response.statusCode() < 500) {
      String code = "CONFIG_ERROR";
      String message = "rewrite service returned HTTP " + response.statusCode();
      try {
        var node = JSON.readTree(response.body());
        if (node.hasNonNull("code")) code = node.get("code").asText();
        if (node.hasNonNull("message")) message = node.get("message").asText();
      } catch (IOException ignored) {
        // keep the HTTP fallback message
      }
      throw new QueryException(code, message, null, true);
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new QueryException(QueryException.REWRITE_SERVICE_UNAVAILABLE,
          "rewrite service returned HTTP " + response.statusCode(), null, true);
    }
    try {
      return JSON.readValue(response.body(), RewrittenQuery.class);
    } catch (IOException e) {
      throw new QueryException(QueryException.REWRITE_SERVICE_UNAVAILABLE,
          "rewrite service returned an unreadable payload", e, true);
    }
  }

  public record RewrittenQuery(List<StatementView> statements) {}

  public record StatementView(int ordinal, String originalSql, String rewrittenSql,
      boolean masked, boolean rowFiltered, String kind) {}
}
```

bean：`@Bean RewriteServiceClient rewriteServiceClient(UpstreamProperties props)`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-query -am test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add mask-query
git commit -m "feat(query): 改写服务客户端（错误码透传与改写阶段标记）"
```

---

### Task 9: mask-query — 引擎目录与 JDBC URL（QueryEngine）

**Files:**
- Create: `mask-query/src/main/java/io/sqlmask/query/executors/QueryEngine.java`
- Test: `mask-query/src/test/java/io/sqlmask/query/executors/QueryEngineTest.java`

**Interfaces:**
- Produces: `enum QueryEngine { POSTGRESQL("postgresql","postgresql",5432),
  MYSQL("mysql","mysql",3306), STARROCKS("starrocks","mysql",9030), TRINO("trino","trino",8080) }`
  ，方法 `static QueryEngine of(String engine)`（trim+小写，未知 →
  `QueryException(UNSUPPORTED_ENGINE)`）、`String dialect()`、
  `String defaultPort()` 不需要（URL 由 host/port 构造）。
  `static String jdbcUrl(QueryEngine engine, MetadataServiceClient.ConnectionView c)`
  —— URL 规则**逐字对齐 mask-core `ConnectionSpec.toJdbcUrl()`**（查询路径去
  socketTimeout，超时生命周期交给 setQueryTimeout+cancel）：
  - POSTGRESQL：`jdbc:postgresql://H:P/DB?sslmode=<sslmode,缺省 disable>&connectTimeout=<cs>&readOnly=true`
  - MYSQL / STARROCKS：`jdbc:mysql://H:P/DB?connectTimeout=<cs*1000>&sslMode=DISABLED&allowPublicKeyRetrieval=true`，
    `sslmode=require` 时 `&sslMode=REQUIRED&verifyServerCertificate=false`（不带
    allowPublicKeyRetrieval）；其他 sslmode 值 → `QueryException(CONFIG_ERROR)`
  - TRINO：`jdbc:trino://H:P/DB?SSL=true|false`（require→true），其他 sslmode 值 → `CONFIG_ERROR`

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.query.executors;

import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryEngineTest {

  private static ConnectionView conn(String sslmode) {
    return new ConnectionView("h", 1234, "db", "u", "REF", sslmode, 10);
  }

  @Test
  void resolvesEngineAndDialect() {
    assertThat(QueryEngine.of(" StarRocks ")).isEqualTo(QueryEngine.STARROCKS);
    assertThat(QueryEngine.STARROCKS.dialect()).isEqualTo("mysql");
    assertThatThrownBy(() -> QueryEngine.of("hive"))
        .hasFieldOrPropertyWithValue("code", "UNSUPPORTED_ENGINE");
  }

  @Test
  void buildsUrlsLikeConnectionSpec() {
    assertThat(QueryEngine.POSTGRESQL.jdbcUrl(conn("disable")))
        .isEqualTo("jdbc:postgresql://h:1234/db?sslmode=disable&connectTimeout=10&readOnly=true");
    assertThat(QueryEngine.MYSQL.jdbcUrl(conn("disable")))
        .isEqualTo("jdbc:mysql://h:1234/db?connectTimeout=10000&sslMode=DISABLED&allowPublicKeyRetrieval=true");
    assertThat(QueryEngine.STARROCKS.jdbcUrl(conn("require")))
        .isEqualTo("jdbc:mysql://h:1234/db?connectTimeout=10000&sslMode=REQUIRED&verifyServerCertificate=false");
    assertThat(QueryEngine.TRINO.jdbcUrl(conn("disable")))
        .isEqualTo("jdbc:trino://h:1234/db?SSL=false");
    assertThatThrownBy(() -> QueryEngine.MYSQL.jdbcUrl(conn("prefer")))
        .hasFieldOrPropertyWithValue("code", "CONFIG_ERROR");
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-query -am test -Dtest=QueryEngineTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

```java
package io.sqlmask.query.executors;

import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView;

import java.util.Locale;

/** Engine catalog: execution engine to rewrite dialect and JDBC URL rules.
 * URL rules mirror mask-core's ConnectionSpec.toJdbcUrl() minus socketTimeout
 * — statement timeout plus cancel own the query lifecycle. */
public enum QueryEngine {
  POSTGRESQL("postgresql", "postgresql", 5432),
  MYSQL("mysql", "mysql", 3306),
  STARROCKS("starrocks", "mysql", 9030),
  TRINO("trino", "trino", 8080);

  private final String id;
  private final String dialect;
  private final int defaultPort;

  QueryEngine(String id, String dialect, int defaultPort) {
    this.id = id;
    this.dialect = dialect;
    this.defaultPort = defaultPort;
  }

  public String id() { return id; }
  public String dialect() { return dialect; }
  public int defaultPort() { return defaultPort; }

  public static QueryEngine of(String engine) {
    String normalized = engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
    for (QueryEngine candidate : values()) {
      if (candidate.id.equals(normalized)) {
        return candidate;
      }
    }
    throw new QueryException(QueryException.UNSUPPORTED_ENGINE,
        "unsupported engine '" + engine + "' (supported: postgresql, mysql, starrocks, trino)");
  }

  public String jdbcUrl(ConnectionView c) {
    String sslmode = c.sslmode() == null || c.sslmode().isBlank() ? "disable" : c.sslmode();
    int cs = c.connectTimeoutSeconds() <= 0 ? 10 : c.connectTimeoutSeconds();
    return switch (this) {
      case POSTGRESQL -> "jdbc:postgresql://" + c.host() + ":" + c.port() + "/" + c.database()
          + "?sslmode=" + sslmode + "&connectTimeout=" + cs + "&readOnly=true";
      case MYSQL, STARROCKS -> {
        String mode = sslmode.toLowerCase(Locale.ROOT);
        if (!mode.equals("disable") && !mode.equals("require")) {
          throw new QueryException(QueryException.CONFIG_ERROR,
              "unsupported sslmode '" + sslmode + "' for " + id + " (supported: disable, require)");
        }
        yield "jdbc:mysql://" + c.host() + ":" + c.port() + "/" + c.database()
            + "?connectTimeout=" + (cs * 1000)
            + ("require".equals(mode)
                ? "&sslMode=REQUIRED&verifyServerCertificate=false"
                : "&sslMode=DISABLED&allowPublicKeyRetrieval=true");
      }
      case TRINO -> {
        String mode = sslmode.toLowerCase(Locale.ROOT);
        if (!mode.equals("disable") && !mode.equals("require")) {
          throw new QueryException(QueryException.CONFIG_ERROR,
              "unsupported sslmode '" + sslmode + "' for trino (supported: disable, require)");
        }
        yield "jdbc:trino://" + c.host() + ":" + c.port() + "/" + c.database()
            + ("require".equals(mode) ? "?SSL=true" : "?SSL=false");
      }
    };
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-query -am test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add mask-query
git commit -m "feat(query): 引擎目录（engine→方言/端口/JDBC URL，StarRocks 复用 MySQL 驱动）"
```

---

### Task 10: mask-query — 执行管线（QueryService：护栏链 + 结果集）

**Files:**
- Create: `mask-query/src/main/java/io/sqlmask/query/service/QueryService.java`
- Create: `mask-query/src/main/java/io/sqlmask/query/service/QueryModels.java`
- Create: `mask-query/src/main/java/io/sqlmask/query/service/ValueJson.java`
- Create: `mask-query/src/main/java/io/sqlmask/query/service/ErrorClassifier.java`
- Create: `mask-query/src/main/java/io/sqlmask/query/service/Credentials.java`
- Create: `mask-query/src/main/java/io/sqlmask/query/service/CancelRegistry.java`
- Test: `mask-query/src/test/java/io/sqlmask/query/service/QueryServiceTest.java`

**Interfaces:**
- Consumes: Task 7 `InstanceView/ConnectionView`、Task 8 `RewriteServiceClient`、
  Task 9 `QueryEngine`、Task 5 `QueryProperties`。
- Produces:
  `QueryService(InstanceDirectory, RewriteServiceClient, QueryProperties, ConnectionFactory, CredentialSource)`
  其中 `interface ConnectionFactory { Connection connect(QueryEngine engine,
  ConnectionView c, String password) throws SQLException; }`（生产 bean 用
  `DriverManager.getConnection(url, user, password)`；测试注入 Mockito 桩）；
  `interface InstanceDirectory { InstanceView fetch(String name); }`
  （`MetadataServiceClient` 实现之，方便测试桩）；
  `interface CredentialSource { String resolve(String passwordRef); }`（生产实现
  `Credentials` 读环境变量；测试传 lambda）。
  `QueryService.execute(QueryModels.QueryRequest request, CancelRegistry.Registration registration)`
  —— registration 由调用方创建（controller 持有它做断连取消，Task 11）；
  管线内在 statement 创建后 `registration.attach(statement)`、结束时 `detach()`。
  `QueryModels`：
  `record QueryRequest(String instance, String sql, String user, List<String> groups, Integer maxRows, Boolean includeRewrittenSql)`；
  `record QueryResult(String instance, String engine, List<ColumnView> columns,
  List<List<Object>> rows, int rowCount, boolean truncated, boolean masked, boolean
  rowFiltered, long elapsedMs, String rewrittenSql)`（`rewrittenSql` 仅
  includeRewrittenSql 时非空）；`record ColumnView(String name, String type)`。
  `Credentials.resolve(String passwordRef)`：env 查找，缺失 →
  `CREDENTIAL_UNAVAILABLE`（message 只含变量名，不含值）。

- [ ] **Step 1: 写失败测试**

Mockito 桩 JDBC 三件套，覆盖四个行为：

```java
package io.sqlmask.query.service;

import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.executors.QueryEngine;
import io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView;
import io.sqlmask.query.metadata.MetadataServiceClient.InstanceView;
import io.sqlmask.query.rewrite.RewriteServiceClient;
import io.sqlmask.query.rewrite.RewriteServiceClient.StatementView;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryServiceTest {

  private static final InstanceView PG = new InstanceView("pg", "postgresql", "postgresql", 1,
      new ConnectionView("h", 5432, "db", "u", "REF", "disable", 10));

  private final RewriteServiceClient rewrites = mock(RewriteServiceClient.class);
  private final QueryService.InstanceDirectory directory = mock(QueryService.InstanceDirectory.class);
  private final Connection connection = mock(Connection.class);
  private final Statement statement = mock(Statement.class);
  private final ResultSet resultSet = mock(ResultSet.class);
  private final ResultSetMetaData meta = mock(ResultSetMetaData.class);

  private QueryService service(QueryProperties props) throws SQLException {
    when(connection.createStatement()).thenReturn(statement);
    return new QueryService(directory, rewrites, props,
        (engine, c, password) -> connection, ref -> "pw");
  }

  private void stubOneSelect(String rewrittenSql, boolean masked, boolean rowFiltered) {
    when(directory.fetch("pg")).thenReturn(PG);
    when(rewrites.rewrite(any(), any(), any(), any()))
        .thenReturn(new RewriteServiceClient.RewrittenQuery(List.of(
            new StatementView(1, "in", rewrittenSql, masked, rowFiltered, "SELECT"))));
    when(statement.executeQuery(any())).thenReturn(resultSet);
    when(resultSet.getMetaData()).thenReturn(meta);
  }

  @Test
  void executesWithGuardsAndTruncationFlag() throws Exception {
    stubOneSelect("SELECT mask_phone(r.phone,3,4) FROM ...", true, true);
    when(meta.getColumnCount()).thenReturn(1);
    when(meta.getColumnLabel(1)).thenReturn("phone");
    when(meta.getColumnTypeName(1)).thenReturn("varchar");
    when(resultSet.next()).thenReturn(true, true, true, false); // max(2)+1 行可读 → truncated
    when(resultSet.getObject(1)).thenReturn("138****0001", "138****0002", "138****0003");

    QueryModels.QueryResult result = service(new QueryProperties(30, 2, 10000, 500, 10))
        .execute(new QueryModels.QueryRequest("pg", "SELECT phone FROM t", null, List.of(), null, false),
            new CancelRegistry().begin());

    verify(connection).setReadOnly(true);
    verify(statement).setQueryTimeout(30);
    verify(statement).setMaxRows(3);          // effectiveMax(2) + 1
    verify(statement).setFetchSize(500);
    verify(connection).rollback();            // PG 流式读取的只读事务收尾
    assertThat(result.truncated()).isTrue();
    assertThat(result.rowCount()).isEqualTo(2);
    assertThat(result.rows().get(0)).containsExactly("138****0001");
    assertThat(result.masked()).isTrue();
    assertThat(result.rowFiltered()).isTrue();
  }

  @Test
  void rejectsMultiStatementAndWrites() {
    when(directory.fetch("pg")).thenReturn(PG);
    when(rewrites.rewrite(any(), any(), any(), any()))
        .thenReturn(new RewriteServiceClient.RewrittenQuery(List.of(
            new StatementView(1, "a", "b", false, false, "SELECT"),
            new StatementView(2, "c", "d", false, false, "SELECT"))));
    QueryService svc;
    try {
      svc = service(new QueryProperties(null, null, null, null, null));
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    assertThatThrownBy(() -> svc.execute(
            new QueryModels.QueryRequest("pg", "SELECT 1; SELECT 2", null, List.of(), null, false),
            new CancelRegistry().begin()))
        .hasFieldOrPropertyWithValue("code", "MULTI_STATEMENT");

    when(rewrites.rewrite(any(), any(), any(), any()))
        .thenReturn(new RewriteServiceClient.RewrittenQuery(List.of(
            new StatementView(1, "a", "b", true, false, "INSERT_SELECT"))));
    assertThatThrownBy(() -> svc.execute(
            new QueryModels.QueryRequest("pg", "INSERT ...", null, List.of(), null, false),
            new CancelRegistry().begin()))
        .hasFieldOrPropertyWithValue("code", "WRITE_STATEMENT");
  }

  @Test
  void busyFailsFastWithoutRewrite() throws SQLException {
    when(directory.fetch("pg")).thenReturn(PG);
    QueryService svc = new QueryService(directory, rewrites,
        new QueryProperties(30, 1000, 10000, 500, 1), (e, c, p) -> connection, ref -> "pw");
    svc.holdPermitForTest("pg");   // 测试钩子：占满 1 个许可
    assertThatThrownBy(() -> svc.execute(
            new QueryModels.QueryRequest("pg", "SELECT 1", null, List.of(), null, false),
            new CancelRegistry().begin()))
        .hasFieldOrPropertyWithValue("code", "QUERY_BUSY");
    svc.releasePermitForTest("pg");
  }

  @Test
  void timeoutIsClassified() throws Exception {
    stubOneSelect("SELECT 1", false, false);
    when(meta.getColumnCount()).thenReturn(1);
    when(meta.getColumnLabel(1)).thenReturn("x");
    when(meta.getColumnTypeName(1)).thenReturn("int4");
    when(resultSet.next()).thenThrow(new SQLException(
        "canceling statement due to statement timeout", "57014"));
    assertThatThrownBy(() -> service(new QueryProperties(null, null, null, null, null)).execute(
            new QueryModels.QueryRequest("pg", "SELECT 1", null, List.of(), null, false),
            new CancelRegistry().begin()))
        .hasFieldOrPropertyWithValue("code", "QUERY_TIMEOUT");
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-query -am test -Dtest=QueryServiceTest`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`QueryModels.java`：

```java
package io.sqlmask.query.service;

import java.util.List;

/** Wire models of the query data plane. */
public final class QueryModels {
  private QueryModels() {}

  public record QueryRequest(String instance, String sql, String user, List<String> groups,
      Integer maxRows, Boolean includeRewrittenSql) {}

  public record ColumnView(String name, String type) {}

  public record QueryResult(String instance, String engine, List<ColumnView> columns,
      List<List<Object>> rows, int rowCount, boolean truncated, boolean masked,
      boolean rowFiltered, long elapsedMs, String rewrittenSql) {}
}
```

`Credentials.java`（含 `CredentialSource` 接口）：

```java
package io.sqlmask.query.service;

import io.sqlmask.query.error.QueryException;
import org.springframework.stereotype.Component;

/** Password source of the execution pipeline; tests stub it. */
public interface CredentialSource {

  String resolve(String passwordRef);
}

/** Resolves the instance's passwordRef from the process environment — the
 * same convention as mask-metadata's EnvCredentialResolver. The value never
 * appears in errors or logs. */
@Component
public class Credentials implements CredentialSource {

  @Override
  public String resolve(String passwordRef) {
    String value = System.getenv(passwordRef);
    if (value == null || value.isBlank()) {
      throw new QueryException(QueryException.CREDENTIAL_UNAVAILABLE,
          "referenced environment variable '" + passwordRef + "' is not set; query aborted");
    }
    return value;
  }
}
```

`CancelRegistry.java`：

```java
package io.sqlmask.query.service;

import org.springframework.stereotype.Component;

import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks in-flight statements so an async disconnect/timeout can cancel the
 * running query instead of letting it finish unobserved. cancel() on an
 * unknown id is a no-op. */
@Component
public class CancelRegistry {

  private final AtomicLong ids = new AtomicLong();
  private final Map<Long, Statement> active = new ConcurrentHashMap<>();

  public record Registration(long id, CancelRegistry registry) {
    public void attach(Statement statement) { registry.active.put(id, statement); }
    public void detach() { registry.active.remove(id); }
  }

  public Registration begin() {
    return new Registration(ids.incrementAndGet(), this);
  }

  public void cancel(long id) {
    Statement statement = active.get(id);
    if (statement != null) {
      try {
        statement.cancel();
      } catch (Exception ignored) {
        // the query is ending anyway; never let canceling mask the real outcome
      }
    }
  }

  public void end(long id) {
    active.remove(id);
  }
}
```

`ErrorClassifier.java`：

```java
package io.sqlmask.query.service;

import io.sqlmask.query.error.QueryException;

import java.sql.SQLException;
import java.util.Locale;

/** Maps driver failures to QUERY_TIMEOUT vs QUERY_ERROR. Timeout signatures:
 * PG SQLState 57014 / "statement timeout"; Connector/J "due to timeout";
 * generic "timed out". */
final class ErrorClassifier {

  private ErrorClassifier() {}

  static QueryException classify(SQLException e) {
    String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
    boolean timeout = "57014".equals(e.getSQLState())
        || message.contains("statement timeout")
        || message.contains("due to timeout")
        || message.contains("timed out");
    if (timeout) {
      return new QueryException(QueryException.QUERY_TIMEOUT,
          "query exceeded the statement timeout: " + describe(e), e);
    }
    return new QueryException(QueryException.QUERY_ERROR, describe(e), e);
  }

  private static String describe(SQLException e) {
    return (e.getMessage() == null ? "" : e.getMessage())
        + (e.getSQLState() == null ? "" : " (SQLState " + e.getSQLState() + ")");
  }
}
```

`ValueJson.java`：

```java
package io.sqlmask.query.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.Base64;

/** Normalizes JDBC values into JSON-safe objects (driver date/time classes
 * would otherwise serialize as arrays or epoch numbers). */
final class ValueJson {

  private ValueJson() {}

  static Object toSerializable(Object value) {
    if (value == null || value instanceof String || value instanceof Boolean
        || value instanceof Number || value instanceof BigDecimal) {
      return value;
    }
    if (value instanceof Timestamp || value instanceof Date || value instanceof Time
        || value instanceof LocalDateTime || value instanceof LocalDate
        || value instanceof LocalTime || value instanceof OffsetDateTime
        || value instanceof OffsetTime || value instanceof java.util.UUID) {
      return value.toString();
    }
    if (value instanceof byte[] bytes) {
      return Base64.getEncoder().encodeToString(bytes);
    }
    return String.valueOf(value);
  }
}
```

`QueryService.java`（核心管线）：

```java
package io.sqlmask.query.service;

import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.executors.QueryEngine;
import io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView;
import io.sqlmask.query.metadata.MetadataServiceClient.InstanceView;
import io.sqlmask.query.rewrite.RewriteServiceClient;
import io.sqlmask.query.rewrite.RewriteServiceClient.StatementView;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * The guarded execution pipeline: per-instance concurrency permit → rewrite
 * (via mask-core) → read-only connection → statement timeout → maxRows+1
 * truncation → streaming fetch. PostgreSQL reads inside a read-only
 * transaction (rolled back at the end) so the driver streams; every failure
 * maps to a structured QueryException.
 */
@Service
public class QueryService {

  private final InstanceDirectory directory;
  private final RewriteServiceClient rewrites;
  private final QueryProperties props;
  private final ConnectionFactory connections;
  private final CredentialSource credentials;
  private final ConcurrentHashMap<String, Semaphore> permits = new ConcurrentHashMap<>();

  public QueryService(InstanceDirectory directory, RewriteServiceClient rewrites,
      QueryProperties props, ConnectionFactory connections, CredentialSource credentials) {
    this.directory = directory;
    this.rewrites = rewrites;
    this.props = props;
    this.connections = connections;
    this.credentials = credentials;
  }

  public interface ConnectionFactory {
    Connection connect(QueryEngine engine, ConnectionView c, String password) throws SQLException;
  }

  public interface InstanceDirectory {
    InstanceView fetch(String name);
  }

  public QueryModels.QueryResult execute(QueryModels.QueryRequest request,
      CancelRegistry.Registration registration) {
    InstanceView instance = directory.fetch(request.instance());
    if (instance.connection() == null) {
      throw new QueryException(QueryException.INSTANCE_NOT_EXECUTABLE,
          "instance '" + instance.name() + "' declares no connection settings "
              + "(YAML-imported instances cannot serve queries)");
    }
    QueryEngine engine = QueryEngine.of(instance.engine());
    if (!engine.dialect().equals(instance.dialect())) {
      throw new QueryException(QueryException.CONFIG_ERROR,
          "instance engine '" + engine.id() + "' does not match its dialect '"
              + instance.dialect() + "'");
    }
    Semaphore permit = permits.computeIfAbsent(instance.name(),
        n -> new Semaphore(props.maxConcurrentPerInstance()));
    if (!permit.tryAcquire()) {
      throw new QueryException(QueryException.QUERY_BUSY,
          "instance '" + instance.name() + "' has reached the concurrent query limit ("
              + props.maxConcurrentPerInstance() + ")");
    }
    try {
      return executeUnderPermit(request, instance, engine);
    } finally {
      permit.release();
    }
  }

  private QueryModels.QueryResult executeUnderPermit(QueryModels.QueryRequest request,
      InstanceView instance, QueryEngine engine) {
    List<StatementView> statements = rewrites
        .rewrite(instance.name(), request.sql(), request.user(), request.groups())
        .statements();
    if (statements.size() > 1) {
      throw new QueryException(QueryException.MULTI_STATEMENT,
          "the query API accepts exactly one statement (got " + statements.size() + ")");
    }
    StatementView statementView = statements.get(0);
    if (!"SELECT".equals(statementView.kind())) {
      throw new QueryException(QueryException.WRITE_STATEMENT,
          "the query API is read-only; statement kind '" + statementView.kind()
              + "' is rejected");
    }
    String rewritten = statementView.rewrittenSql();
    String password = credentials.resolve(instance.connection().passwordRef());
    int effectiveMax = request.maxRows() == null ? props.maxRows()
        : Math.min(request.maxRows(), props.maxRowsHard());
    long start = System.nanoTime();
    try (Connection connection = connections.connect(engine, instance.connection(), password)) {
      connection.setReadOnly(true);
      boolean pgTransaction = engine == QueryEngine.POSTGRESQL;
      if (pgTransaction) {
        connection.setAutoCommit(false);
      }
      try (Statement statement = connection.createStatement()) {
        registration.attach(statement);
        try {
          statement.setQueryTimeout(props.timeoutSeconds());
          statement.setMaxRows(effectiveMax + 1);
          statement.setFetchSize(props.fetchSize());
          try (ResultSet rs = statement.executeQuery(rewritten)) {
          List<QueryModels.ColumnView> columns = columnsOf(rs);
          List<List<Object>> rows = new ArrayList<>();
          boolean truncated = false;
          while (rows.size() < effectiveMax && rs.next()) {
            List<Object> row = new ArrayList<>(columns.size());
            for (int i = 1; i <= columns.size(); i++) {
              row.add(ValueJson.toSerializable(rs.getObject(i)));
            }
            rows.add(row);
          }
          if (rows.size() == effectiveMax && rs.next()) {
            truncated = true;
          }
          long elapsedMs = (System.nanoTime() - start) / 1_000_000;
          return new QueryModels.QueryResult(instance.name(), engine.id(), columns, rows,
              rows.size(), truncated, statementView.masked(), statementView.rowFiltered(),
              elapsedMs, Boolean.TRUE.equals(request.includeRewrittenSql()) ? rewritten : null);
          }
        } finally {
          registration.detach();
        }
      } finally {
        if (pgTransaction) {
          connection.rollback();
        }
      }
    } catch (SQLException e) {
      throw ErrorClassifier.classify(e);
    }
  }

  private static List<QueryModels.ColumnView> columnsOf(ResultSet rs) throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    int count = meta.getColumnCount();
    List<QueryModels.ColumnView> columns = new ArrayList<>(count);
    for (int i = 1; i <= count; i++) {
      columns.add(new QueryModels.ColumnView(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
    }
    return columns;
  }

  // —— 并发信号量的测试钩子（生产不调用） ——
  void holdPermitForTest(String instance) {
    permits.computeIfAbsent(instance, n -> new Semaphore(props.maxConcurrentPerInstance()));
    try {
      permits.get(instance).acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  void releasePermitForTest(String instance) {
    Semaphore semaphore = permits.get(instance);
    if (semaphore != null) {
      semaphore.release();
    }
  }
}
```

生产 `ConnectionFactory` bean（`QueryServerApplication`）：

```java
@Bean
QueryService.ConnectionFactory queryConnectionFactory() {
  return (engine, c, password) -> java.sql.DriverManager.getConnection(
      engine.jdbcUrl(c), c.dbUser(), password);
}
```

（测试里 `service(0)` 辅助方法按需构造 `new QueryProperties(30, 2, 10000, 500, 10)`；
测试代码里 `props` 字段与辅助方法取一，保持编译通过即可。）

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-query -am test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add mask-query
git commit -m "feat(query): 执行管线（并发许可/只读/超时/maxRows+1 截断/流式读取/超时分类）"
```

---

### Task 11: mask-query — QueryController（校验 + 异步 + 断连取消）

**Files:**
- Create: `mask-query/src/main/java/io/sqlmask/query/web/QueryController.java`
- Test: `mask-query/src/test/java/io/sqlmask/query/web/QueryControllerTest.java`

**Interfaces:**
- Consumes: Task 10 的 `QueryService.execute(request, registration)` 与
  `CancelRegistry`（`begin()` / `Registration.attach/detach` / `cancel(id)` / `end(id)`）。
- Produces: `POST /api/v1/query`（请求/响应 JSON 见 spec §4）。

- [ ] **Step 1: 写失败测试**

MockMvc + `@MockitoBean`（Spring Boot 3.4+）或 `@TestConfiguration` 覆盖
`InstanceDirectory`/`RewriteServiceClient`/`ConnectionFactory`，断言：

```java
package io.sqlmask.query.web;

import io.sqlmask.query.service.QueryModels;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(QueryControllerTest.Stubs.class)
class QueryControllerTest {

  @Autowired MockMvc mockMvc;

  @TestConfiguration
  static class Stubs {
    static final java.sql.Statement STATEMENT =
        org.mockito.Mockito.mock(java.sql.Statement.class);

    @Bean
    io.sqlmask.query.service.QueryService queryService() throws Exception {
      var directory = org.mockito.Mockito.mock(io.sqlmask.query.service.QueryService.InstanceDirectory.class);
      var rewrites = org.mockito.Mockito.mock(io.sqlmask.query.rewrite.RewriteServiceClient.class);
      var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
      var statement = STATEMENT;
      var resultSet = org.mockito.Mockito.mock(java.sql.ResultSet.class);
      var meta = org.mockito.Mockito.mock(java.sql.ResultSetMetaData.class);
      org.mockito.Mockito.when(directory.fetch("pg")).thenReturn(
          new io.sqlmask.query.metadata.MetadataServiceClient.InstanceView("pg", "postgresql",
              "postgresql", 1, new io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView(
                  "h", 5432, "db", "u", "REF", "disable", 10)));
      org.mockito.Mockito.when(rewrites.rewrite(org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any()))
          .thenReturn(new io.sqlmask.query.rewrite.RewriteServiceClient.RewrittenQuery(
              java.util.List.of(new io.sqlmask.query.rewrite.RewriteServiceClient.StatementView(
                  1, "SELECT phone FROM customer", "SELECT mask_phone(r.phone,3,4) ...",
                  true, false, "SELECT"))));
      org.mockito.Mockito.when(connection.createStatement()).thenReturn(statement);
      org.mockito.Mockito.when(statement.executeQuery(org.mockito.ArgumentMatchers.anyString()))
          .thenReturn(resultSet);
      org.mockito.Mockito.when(resultSet.getMetaData()).thenReturn(meta);
      org.mockito.Mockito.when(meta.getColumnCount()).thenReturn(1);
      org.mockito.Mockito.when(meta.getColumnLabel(1)).thenReturn("phone");
      org.mockito.Mockito.when(meta.getColumnTypeName(1)).thenReturn("varchar");
      org.mockito.Mockito.when(resultSet.next()).thenReturn(true, false);
      org.mockito.Mockito.when(resultSet.getObject(1)).thenReturn("138****0001");
      return new io.sqlmask.query.service.QueryService(directory, rewrites,
          new io.sqlmask.query.config.QueryProperties(null, null, null, null, null),
          (engine, c, password) -> connection, ref -> "pw");
    }
  }

  @Test
  void happyPathReturnsMaskedRows() throws Exception {
    mockMvc.perform(post("/api/v1/query").contentType("application/json")
            .content("{\"instance\":\"pg\",\"sql\":\"SELECT phone FROM customer\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instance").value("pg"))
        .andExpect(jsonPath("$.engine").value("postgresql"))
        .andExpect(jsonPath("$.rowCount").value(1))
        .andExpect(jsonPath("$.truncated").value(false))
        .andExpect(jsonPath("$.rewrittenSql").doesNotExist());
  }

  @Test
  void missingInstanceFailsWithConfigError() throws Exception {
    mockMvc.perform(post("/api/v1/query").contentType("application/json")
            .content("{\"sql\":\"SELECT 1\"}"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void includeRewrittenSqlEchoesRewriteAndClampsMaxRows() throws Exception {
    mockMvc.perform(post("/api/v1/query").contentType("application/json")
            .content("{\"instance\":\"pg\",\"sql\":\"SELECT phone FROM customer\","
                + "\"maxRows\":999999,\"includeRewrittenSql\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rewrittenSql").value("SELECT mask_phone(r.phone,3,4) ..."));
    // maxRows 999999 超过硬上限 10000 → 生效 10000 → setMaxRows(10001)
    org.mockito.Mockito.verify(Stubs.STATEMENT).setMaxRows(10001);
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-query -am test -Dtest=QueryControllerTest`
Expected: 编译失败（Controller 不存在）。

- [ ] **Step 3: 实现**

（`CancelRegistry` 与 `QueryService.execute` 两参签名已在 Task 10 落地，本任务
只新增控制器。）

`QueryController`：

```java
package io.sqlmask.query.web;

import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.service.CancelRegistry;
import io.sqlmask.query.service.QueryModels.QueryRequest;
import io.sqlmask.query.service.QueryModels.QueryResult;
import io.sqlmask.query.service.QueryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;

import java.util.List;

@RestController
public class QueryController {

  private final QueryService service;
  private final CancelRegistry cancels;
  private final QueryProperties props;

  public QueryController(QueryService service, CancelRegistry cancels, QueryProperties props) {
    this.service = service;
    this.cancels = cancels;
    this.props = props;
  }

  @PostMapping("/api/v1/query")
  public WebAsyncTask<ResponseEntity<QueryResult>> query(@RequestBody QueryRequest request,
      jakarta.servlet.http.HttpServletRequest httpRequest) {
    if (request == null || request.instance() == null || request.instance().isBlank()) {
      throw new QueryException(QueryException.CONFIG_ERROR, "instance is required");
    }
    if (request.sql() == null || request.sql().isBlank()) {
      throw new QueryException(QueryException.CONFIG_ERROR, "sql is required");
    }
    // WebAsyncTask 的超时兜底比语句超时略长；断连/容器超时先 cancel 再回 503
    CancelRegistry.Registration registration = cancels.begin();
    WebAsyncTask<ResponseEntity<QueryResult>> task =
        new WebAsyncTask<>(props.timeoutSeconds() * 1000L + 10_000L, () ->
            ResponseEntity.ok(service.execute(request, registration)));
    task.onTimeout(() -> {
      cancels.cancel(registration.id());
      return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE).build();
    });
    task.onCompletion(() -> cancels.end(registration.id()));
    return task;
  }
}
```

（`onCompletion` 已覆盖正常/异常结束的注销；`onTimeout` 先 cancel 再回 503——
护栏语义以 QUERY_TIMEOUT 为主路径，容器超时是兜底。）
import 相应 `org.springframework.http.ResponseEntity`、`WebAsyncTask`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-query -am test`
Expected: PASS（QueryServiceTest 同步调用补 `new CancelRegistry().begin()` 后依旧绿）。

- [ ] **Step 5: Commit**

```bash
git add mask-query
git commit -m "feat(query): /api/v1/query 控制器（校验/异步 WebAsyncTask/断连取消）"
```

---

### Task 12: mask-query — QUERY 审计接入（mask-audit 第 4 类事件）

**Files:**
- Modify: `mask-audit/src/main/java/io/sqlmask/audit/AuditEvent.java`（+ `QUERY` 常量与工厂）
- Modify: `mask-audit/src/test/java/io/sqlmask/audit/AuditEventTest.java`（追加）
- Create: `mask-query/src/main/java/io/sqlmask/query/audit/QueryAuditor.java`
- Create: `mask-query/src/main/java/io/sqlmask/query/audit/AuditBridge.java`
- Modify: `mask-query/src/main/java/io/sqlmask/query/web/QueryController.java`（发射）
- Test: `mask-query/src/test/java/io/sqlmask/query/audit/QueryAuditorTest.java`

**前置条件：** `AuditRecorder` 接口来自审计计划 Task 5
（`mask-audit/src/main/java/io/sqlmask/audit/AuditRecorder.java`，
签名 `void record(AuditEvent event)`，永不抛出）。**执行本任务前先检查树上是否
已存在**：不存在则先创建该文件（内容就一行接口，与审计计划 Task 5 签名一致，
后续审计分支合入时以同名为准自然收敛）。

**Interfaces:**
- Consumes: `AuditRecorder.record(AuditEvent)`；`AuditEvent` 全字段。
- Produces: `AuditEvent.QUERY` 常量；工厂
  `AuditEvent.query(String service, String outcome, Long durationMs, String sourceIp,
  String authKind, String user, List<String> groups, String instance, String dialect,
  Boolean masked, Boolean rowFiltered, String originalSql, String errorCode,
  String errorMessage, Map<String,Object> detail)`（statementCount=1，rewrittenSql 不外带）。
  mask-query：`QueryAuditor`（`success(...)` / `failure(...)`，永不抛出）；
  `AuditBridge` 提供 `QueryAuditor` bean——容器里存在 `AuditRecorder` bean 则桥接，
  否则 no-op sink。**审计边界**：只有改写调用成功返回之后的失败
  （`!QueryException.rewritePhase()`）与成功结果才发 `QUERY` 事件。

- [ ] **Step 1: 写失败测试**

`AuditEventTest` 追加：

```java
@Test
void queryFactoryCarriesEnvelopeAndDetail() {
  AuditEvent event = AuditEvent.query("mask-query", AuditEvent.SUCCESS, 12L, "10.0.0.1",
      "API_KEY", "alice", List.of("devs"), "pg_prod", "postgresql", true, false,
      "SELECT phone FROM customer", null, null,
      java.util.Map.of("engine", "postgresql", "rowCount", 2, "truncated", false));
  assertThat(event.eventType()).isEqualTo("QUERY");
  assertThat(event.instance()).isEqualTo("pg_prod");
  assertThat(event.statementCount()).isEqualTo(1);
  assertThat(event.detail()).containsEntry("rowCount", 2);
}
```

`QueryAuditorTest`：

```java
@Test
void neverThrowsAndCarriesSqlAndCodes() {
  List<AuditEvent> captured = new ArrayList<>();
  QueryAuditor auditor = new QueryAuditor(captured::add);
  auditor.success(new QueryModels.QueryResult("pg", "postgresql", List.of(),
      List.of(List.of("x")), 1, false, true, false, 5L, null),
      "SELECT phone FROM customer", "alice", List.of("devs"), "127.0.0.1");
  auditor.failure(new QueryException(QueryException.QUERY_TIMEOUT, "t"),
      "SELECT pg_sleep(5)", "alice", List.of(), "127.0.0.1", "pg_prod");
  assertThat(captured).hasSize(2);
  assertThat(captured.get(0).outcome()).isEqualTo("SUCCESS");
  assertThat(captured.get(0).originalSql()).isEqualTo("SELECT phone FROM customer");
  assertThat(captured.get(1).errorCode()).isEqualTo("QUERY_TIMEOUT");
  assertThat(captured.get(1).instance()).isEqualTo("pg_prod");
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-audit -am test -Dtest=AuditEventTest && mvn -pl mask-query -am test -Dtest=QueryAuditorTest`
Expected: 编译失败（`query` 工厂 / `QueryAuditor` 不存在）。

- [ ] **Step 3: 实现**

`AuditEvent` 追加（照既有工厂排版）：

```java
public static final String QUERY = "QUERY";

/** Data-plane query execution (mask-query). detail carries engine/rowCount/truncated. */
public static AuditEvent query(String service, String outcome, Long durationMs, String sourceIp,
    String authKind, String user, List<String> groups, String instance, String dialect,
    Boolean masked, Boolean rowFiltered, String originalSql, String errorCode,
    String errorMessage, Map<String, Object> detail) {
  return new AuditEvent(null, QUERY, service, outcome, durationMs, sourceIp, user, groups,
      authKind, errorCode, errorMessage, dialect, 1, masked, rowFiltered, originalSql, null,
      null, null, instance, null, detail);
}
```

`AuditBridge`（mask-query）：

```java
package io.sqlmask.query.audit;

import io.sqlmask.audit.AuditRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

/** Bridges mask-audit's recorder when the ES pipeline is deployed; without it
 * the auditor is a no-op and queries stay fully functional. */
@Configuration
public class AuditBridge {

  @Bean
  public Consumer<io.sqlmask.audit.AuditEvent> queryAuditSink(
      ObjectProvider<AuditRecorder> recorder) {
    AuditRecorder available = recorder.getIfAvailable();
    return available == null ? event -> {} : available::record;
  }
}
```

`QueryAuditor`：

```java
package io.sqlmask.query.audit;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.service.QueryModels.QueryResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Emits QUERY audit events; never throws into the request path. */
@Component
public class QueryAuditor {

  private static final String SERVICE = "mask-query";

  private final Consumer<AuditEvent> sink;

  public QueryAuditor(Consumer<AuditEvent> sink) {
    this.sink = sink;
  }

  public void success(QueryResult result, String originalSql, String user,
      List<String> groups, String sourceIp) {
    try {
      sink.accept(AuditEvent.query(SERVICE, AuditEvent.SUCCESS, result.elapsedMs(), sourceIp,
          "API_KEY", user, groups, result.instance(), engineOf(result), result.masked(),
          result.rowFiltered(), originalSql, null, null,
          Map.of("engine", engineOf(result),
              "rowCount", result.rowCount(),
              "truncated", result.truncated())));
    } catch (RuntimeException ignored) {
      // audit must never break the query path
    }
  }

  public void failure(QueryException e, String originalSql, String user, List<String> groups,
      String sourceIp, String instance) {
    try {
      sink.accept(AuditEvent.query(SERVICE, AuditEvent.FAILURE, null, sourceIp, "API_KEY",
          user, groups, instance, null, null, null, originalSql, e.code(), e.getMessage(),
          null));
    } catch (RuntimeException ignored) {
      //同上
    }
  }

  private static String engineOf(QueryResult result) {
    return result.engine();
  }
}
```

（originalSql 传原始请求 SQL——AuditEvent 的 javadoc 说明截断发生在映射层，
事件原文携带、由 recorder 的 `sql-max-chars` 设置统一截断；失败事件的
dialect 一期取 null（spec 中该字段可空）。）

`QueryController` 集成（注入 `QueryAuditor`）：

```java
try {
  QueryResult result = service.execute(request, registration);
  auditor.success(result, request.sql(), request.user(), request.groups(),
      httpRequest.getRemoteAddr());
  return ResponseEntity.ok(result);
} catch (QueryException e) {
  if (!e.rewritePhase()) {
    auditor.failure(e, request.sql(), request.user(), request.groups(),
        httpRequest.getRemoteAddr(), request.instance());
  }
  throw e;
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-audit -am test && mvn -pl mask-query -am test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add mask-audit mask-query
git commit -m "feat(audit): QUERY 第4类事件（mask-query 执行审计，改写阶段不双记）"
```

---

### Task 13: mask-query — 全链路 IT（嵌入式 PG 真库 + 桩上游）

**Files:**
- Create: `mask-query/src/test/java/io/sqlmask/query/QueryEndToEndIT.java`

**Interfaces:**
- Consumes: 全部生产 bean + JDK HttpServer 桩（mask-core/mask-metadata 契约）+
  embedded-postgres（目标库，装真 UDF）+ 测试审计 sink 捕获。
- Produces: 验收证据：真库执行脱敏 SQL 结果正确、行过滤叠加、`truncated`、
  `QUERY_TIMEOUT`、QUERY 审计事件入桩。

- [ ] **Step 1: 写 IT（这是最终交付物，先写预期再让全链路跑通）**

结构（完整落地代码由此展开，关键断言如下）：

```java
package io.sqlmask.query;

// imports: EmbeddedPostgres, HttpServer, SpringBootTest, DynamicPropertySource …

@SpringBootTest(properties = "query.timeout-seconds=2")
class QueryEndToEndIT {

  static EmbeddedPostgres pg;
  static HttpServer metadataStub;   // GET /api/instances/pg_prod → InstanceDetailResponse JSON
  static HttpServer rewriteStub;    // POST /api/rewrite/instances/pg_prod → canned masked SQL
  static final java.util.concurrent.atomic.AtomicReference<String> stubsRewrittenSql =
      new java.util.concurrent.atomic.AtomicReference<>("");

  @BeforeAll
  static void startAll() throws Exception {
    // 1) embedded PG + 建表：customer(id bigint, phone varchar, status varchar)
    //    数据：('1','13812345678','active'), ('2','13900001111','archived')
    //    UDF：CREATE FUNCTION mask_phone(v varchar, keep_first int, keep_last int)
    //         RETURNS varchar AS $$ SELECT left(v, keep_first) || repeat('*', 4) || right(v, keep_last) $$ LANGUAGE sql;
    //    脱敏预期：mask_phone('13812345678', 3, 4) = '138' + '****' + '5678' = '138****5678'
    // 2) metadataStub：返回 connection{host=127.0.0.1,port=<pg 端口>,database=postgres,
    //    dbUser=postgres,passwordRef=IT_PG_PASSWORD,sslmode=disable}, engine=null, dialect=postgresql
    //    （表结构段与 Task 3 的 stub 相同形态）
    // 3) rewriteStub：返回单条 statement，kind=SELECT masked=true rowFiltered=true，
    //    rewrittenSql 取 stubsRewrittenSql.get()——缺省为
    //    "SELECT mask_phone(r.phone, 3, 4) AS phone FROM
    //      (SELECT id, phone, status FROM public.customer WHERE status = 'active') AS r"
    // 4) System.setProperty 不适用于 env——密码由 Stubs.credentialSource 桩提供
  }

  @DynamicPropertySource
  static void upstream(org.springframework.test.context.DynamicPropertyRegistry r) {
    r.register("upstream.metadata-base-url", () -> "http://127.0.0.1:" + metadataPort);
    r.register("upstream.rewrite-base-url", () -> "http://127.0.0.1:" + rewritePort);
    r.register("upstream.metadata-api-key", () -> "k");
    r.register("upstream.rewrite-api-key", () -> "k");
  }

  @TestConfiguration
  static class Stubs {
    // passwordRef 不依赖进程环境：embedded PG 密码直接给桩
    @Bean
    io.sqlmask.query.service.CredentialSource credentialSource() {
      return ref -> "postgres";
    }

    static final List<io.sqlmask.audit.AuditEvent> AUDIT = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Bean
    @org.springframework.context.annotation.Primary
    java.util.function.Consumer<io.sqlmask.audit.AuditEvent> capturingAuditSink() {
      return AUDIT::add;
    }
  }

  @Autowired MockMvc mockMvc;
  @Autowired Stubs stubs;

  @Test
  void maskedRowFilteredResultFromRealDatabase() throws Exception {
    mockMvc.perform(post("/api/v1/query").contentType("application/json")
            .content("{\"instance\":\"pg_prod\",\"sql\":\"SELECT phone FROM customer\",\"user\":\"alice\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rowCount").value(1))                 // archived 行被行过滤排除
        .andExpect(jsonPath("$.rows[0][0]").value("138****5678"))   // 真 UDF 计算结果
        .andExpect(jsonPath("$.masked").value(true))
        .andExpect(jsonPath("$.rowFiltered").value(true))
        .andExpect(jsonPath("$.truncated").value(false));
    // 审计桩：一条 QUERY SUCCESS，detail.rowCount=1
  }

  @Test
  void statementTimeoutBecomesQueryTimeout() throws Exception {
    stubsRewrittenSql.set("SELECT pg_sleep(5)");   // Atomic<String>，rewriteStub handler 读它
    mockMvc.perform(post("/api/v1/query").contentType("application/json")
            .content("{\"instance\":\"pg_prod\",\"sql\":\"SELECT pg_sleep(5)\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("QUERY_TIMEOUT"));
    // Stubs.AUDIT 新增一条 QUERY FAILURE errorCode=QUERY_TIMEOUT（断言列表增量）
  }

  @Test
  void truncationDetectedAgainstRealRows() throws Exception {
    stubsRewrittenSql.set("SELECT id FROM public.customer");  // 真库 2 行
    mockMvc.perform(post("/api/v1/query").contentType("application/json")
            .content("{\"instance\":\"pg_prod\",\"sql\":\"SELECT id FROM customer\",\"maxRows\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rowCount").value(1))
        .andExpect(jsonPath("$.truncated").value(true));
  }
}
```

落地注意：
- `Credentials` 是 `@Component` 且读 env——测试用 `@TestConfiguration` 覆盖为
  `ref -> "postgres"`（embedded PG 密码），避免依赖进程环境；
- `auditSink` 覆盖：`@TestConfiguration` 提供 `Consumer<AuditEvent>` 捕获 bean
  （`@Primary`）替代 `AuditBridge` 的产物；
- embedded PG 依赖与 `JdbcMetaStoreTest` 同源（`io.zonky.test:embedded-postgres`，
  test scope——mask-query pom 需加该 test 依赖，版本对齐 mask-core）。

- [ ] **Step 2: 运行确认通过**

Run: `mvn -pl mask-query -am test -Dtest=QueryEndToEndIT`
Expected: PASS（三个用例全绿）。

- [ ] **Step 3: Commit**

```bash
git add mask-query
git commit -m "test(query): 嵌入式 PG 全链路 IT（真 UDF 脱敏/行过滤/截断/超时/审计桩）"
```

---

### Task 14: 文档、验收套件与收尾

**Files:**
- Modify: `README.md`（mask-query 章节）
- Create: `docker-compose.query.yml`
- Create: `docker/query.Dockerfile`（照 `docker/metadata.Dockerfile` 模式改 jar 与端口）
- Create: `docs/query-acceptance/golden-queries.md`
- Modify: `docs/superpowers/specs/2026-09-17-query-service-design.md`（错误码补录）

**Interfaces:** 无代码接口；交付可运行的验收与文档。

- [ ] **Step 1: spec 错误码补录**

spec §4 mask-query 自有错误码表补两行（实现中新增）：`REWRITE_SERVICE_UNAVAILABLE`
（mask-core 不可达，fail closed）、`CREDENTIAL_UNAVAILABLE`（passwordRef 环境变量
缺失）、`UNSUPPORTED_ENGINE`（实例 engine 超出批 1 目录）。附一句说明：
该表与实现同步于 2026-09-17 计划执行期。

- [ ] **Step 2: README**

新增「查询服务（mask-query，8083）」章节：定位（统一查询 API 数据面）、
`POST /api/v1/query` 请求/响应示例（与 spec §4 相同 JSON）、错误码清单、
配置项表（`query.*` 五项）、环境变量（`SQLMASK_QUERY_API_KEY` 未配置全 401、
`SQLMASK_REWRITE_API_KEY`、`SQLMASK_METADATA_*`、passwordRef 约定
`SQLMASK_DS_<INSTANCE>_PASSWORD`）、引擎侧 UDF 部署前提表（PG/MySQL 建函数、
Hive/Spark JAR、StarRocks 3.x Java UDF、Trino 插件）、批 2 路线一句话
（Hive/SparkSQL 方言另立 spec）。构建运行：

```bash
mvn -pl mask-query -am package
java -jar mask-query/target/mask-query.jar
```

- [ ] **Step 3: docker compose 验收套件**

`docker-compose.query.yml`（与 `docker-compose.metadata.yml` 同风格）：
`mask-query` 服务（build `docker/query.Dockerfile`，8083，注入
`SQLMASK_METADATA_BASE_URL` 等 env）+ `mysql:8.0`（3306）+ `trino:446`（8080）+
`starrocks`（`starrocks/allin1-ubuntu:3.2`，9030/8030）。全部挂 profile
`query-acceptance`，默认 `docker compose -f docker-compose.query.yml up` 不启动。

`docs/query-acceptance/golden-queries.md`：每引擎一节，每节列出
「原始 SQL → 改写后 SQL → 期望脱敏结果」三列表格，至少包含：
- 通用：`SELECT phone FROM customer`（基础脱敏）、`SELECT count(phone) FROM customer`
  （聚合列 + 类型重载前提注记）、`SELECT phone FROM customer WHERE ...`（行过滤叠加）、
  带 `LIMIT` 的查询；
- StarRocks 专节注明兼容口径：「MySQL 方言改写产物的可执行子集，以本清单为准」；
- 运行步骤：起 compose → 各引擎装 UDF 的最小 DDL（PG/MySQL `CREATE FUNCTION`
  示例全文、StarRocks Java UDF 链接、Trino 插件部署链接）→ 逐条 curl
  `POST /api/v1/query` → 比对期望列。

- [ ] **Step 4: 全仓回归**

Run: `mvn test`
Expected: 全部模块 PASS。

- [ ] **Step 5: Commit**

```bash
git add README.md docker-compose.query.yml docker/query.Dockerfile docs/query-acceptance docs/superpowers/specs/2026-09-17-query-service-design.md
git commit -m "docs(query): mask-query 使用文档、验收 compose 与 golden 清单；spec 错误码补录"
```

---

## 任务依赖与执行顺序

```
Task 1 → Task 2 → Task 3          （mask-core 链）
Task 4                             （mask-metadata，独立）
Task 5 → 6 → 7 → 8 → 9 → 10 → 11  （mask-query 依赖链）
Task 12 依赖 Task 6/11 + AuditRecorder 存在
Task 13 依赖 Task 5..12 全部
Task 14 收尾（全仓回归 + 文档）
```

Task 3 与 Task 4 相互独立可并行；Task 5 起的 mask-query 链依赖 Task 3 的端点
契约（可先用桩并行开发，联调在 Task 13）。
