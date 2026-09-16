# 策略微服务独立部署（mask-policy-server 拆分 + instance 模式接线）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把内嵌在 mask-core 里的策略微服务拆成独立模块 `mask-policy-server`（8081，PG 默认存储），并给 `/api/rewrite` 与 CLI 接上"按实例名从策略服务拉取生效配置"的 instance 模式。

**Architecture:** 新建 Maven 模块 `mask-policy-server`，整包迁入 `io.sqlmask.policyserver` 与管理面/数据面 REST（包名不变、行为逐字节不变）；core 保留消费方代码（`ConfigSource` 客户端族）并新增 `InstanceConfigSources`（按实例复用客户端 + 30s 轮询刷新）与 `/admin/cache/refresh`。core ↔ policy-server 运行时仅有 HTTP。

**Tech Stack:** Java 17、Spring Boot（parent `sql-mask-parent` 统一管理版本）、Maven 多模块、JUnit 5 + MockMvc、JDK 内置 `com.sun.net.httpserver` 做 HTTP 桩、embedded-postgres（JdbcPolicyStore 测试）、docker compose。

**Spec:** `docs/superpowers/specs/2026-09-17-policy-server-extraction-design.md`（本计划从该 spec 论证；执行者需同读）

## Global Constraints

- 迁移端点（`/api/instances/**`、`/api/effective/**`、UDF CRUD、`import-metadata`）对外行为**逐字节不变**：路径、请求/响应 JSON、错误形状（`SqlMaskException` → 400 + `{code, message}`）、鉴权（`X-Api-Key`，未配置不拦截）全部照搬；
- `io.sqlmask.policyserver` 包名原样保留；迁移的 5 个 `io.sqlmask.server` 类进新包 `io.sqlmask.policyserver.web`（`PolicyApiKeyFilter` 为复制件，同包名）；
- 端口：core 8080 / **policy 8081** / metadata 8082；
- 环境变量：服务端 `POLICY_PG_URL` / `POLICY_PG_USER` / `POLICY_PG_PASSWORD`、`SQLMASK_ADMIN_API_KEY` / `SQLMASK_DATA_API_KEY`；core 客户端 `POLICY_SERVICE_URL` / `POLICY_SERVICE_API_KEY` / `POLICY_SERVICE_POLL_INTERVAL_MS`（默认 30000）；
- 新行为一律 TDD（先写失败测试）；迁移以"迁移测试在新模块全绿 + core 回归全绿"为验收；
- Windows / Git Bash 环境；模块级测试命令用 `mvn -q -pl <module> -am test`（`-am` 保证依赖模块先构建）；
- 提交信息用仓库既有 conventional 风格（`feat(policy-server): ...` / `test:` / `docs:`），每任务至少一个提交；
- **执行前预检（Global）**：本计划在隔离 worktree（`C:/Users/yhh/orca/mask-policy-exec`，分支 `feature/audit-es-impl2`）中执行；worktree 创建时自然干净，主工作区（`C:/Users/yhh/orca/mask`）其他工作线（如 prometheus-metrics）的未提交 WIP 与本计划无关、绝不动。若执行中 worktree 出现计划之外的脏文件，中止并报告。

---

### Task 1: 模块拆分——创建 mask-policy-server，服务端整体迁出 core

**Files:**
- Create: `mask-policy-server/pom.xml`
- Create: `mask-policy-server/src/main/java/io/sqlmask/policyserver/PolicyServerApplication.java`
- Create: `mask-policy-server/src/main/java/io/sqlmask/policyserver/web/PolicyApiExceptionHandler.java`
- Create: `mask-policy-server/src/main/resources/application.yml`
- Create: `mask-policy-server/src/test/resources/application.yml`
- Move (git mv，包名不变): `mask-core/src/main/java/io/sqlmask/policyserver/` → `mask-policy-server/src/main/java/io/sqlmask/policyserver/`
- Move + 改包名（`io.sqlmask.server` → `io.sqlmask.policyserver.web`）:
  - `mask-core/.../server/EffectiveConfigController.java`
  - `mask-core/.../server/PolicyAdminController.java`
  - `mask-core/.../server/UdfController.java`
  - `mask-core/.../server/MetadataImportController.java`
  - `mask-core/.../server/MetadataStructureFetcher.java`
- Copy（**不迁**，core 保留原类与注册——审计工作线在 core 扩展该过滤器，见 spec §9）:
  - `mask-core/.../server/PolicyApiKeyFilter.java` → `mask-policy-server/.../policyserver/web/PolicyApiKeyFilter.java`（仅改 package 声明）
  - `mask-core/src/test/java/io/sqlmask/server/PolicyApiKeyFilterTest.java` 保留在 core **不动**；另复制一份到 `mask-policy-server/src/test/java/io/sqlmask/policyserver/web/PolicyApiKeyFilterTest.java`（仅改 package 声明，按 HEAD 版本复制，不带任何未提交修改）
- Move: `mask-core/src/main/resources/schema.sql` → `mask-policy-server/src/main/resources/schema.sql`
- Move (测试): `mask-core/src/test/java/io/sqlmask/policyserver/` → `mask-policy-server/src/test/java/io/sqlmask/policyserver/`；`mask-core/.../server/{EffectiveConfigEndpointTest,PolicyAdminEndpointTest,UdfEndpointTest,MetadataImportEndpointTest}.java` → `mask-policy-server/src/test/java/io/sqlmask/policyserver/web/`（包名同步改）
- Modify: `pom.xml`（根，`<modules>` 增加 mask-policy-server）
- Modify: `mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java`（删 4 个 policy 相关 @Bean，其余——审计 bean、`policyApiKeyFilter` 注册——全部保留）

**Interfaces:**
- Consumes: core 的 `io.sqlmask.config.source.EffectiveConfigResponse`、`io.sqlmask.metadataclient.MetadataClient`、`io.sqlmask.error.SqlMaskException`（经 mask-core 依赖获得）；mask-policy 的 `SubjectSelector`/`Subject`；**mask-audit**（`AuditEvents` 常量——复制的 `PolicyApiKeyFilter` 打标 authKind 用，pom 需显式依赖）；
- Produces: 独立 Spring Boot 应用 `PolicyServerApplication`（fat jar `mask-policy-server-*.jar`，8081）；`PolicyStore` bean 规则——配置了 DataSource（生产默认）→ `JdbcPolicyStore`，未配置（测试排除 DataSource 自动装配）→ `InMemoryPolicyStore`。后续任务不直接依赖这些 bean，但依赖"8081 上管理面/数据面可用"这一事实。

- [ ] **Step 1: 创建模块 pom 与根 pom 登记**

创建 `mask-policy-server/pom.xml`（照 mask-metadata 模板，多一行显式 mask-policy 依赖）：

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

  <artifactId>mask-policy-server</artifactId>
  <packaging>jar</packaging>

  <name>mask-policy-server</name>
  <description>Standalone masking policy microservice: engine instances, policies, UDF registry and the effective-config data plane.</description>

  <dependencies>
    <dependency>
      <groupId>io.sqlmask</groupId>
      <artifactId>mask-core</artifactId>
      <version>0.1.0-SNAPSHOT</version>
    </dependency>
    <dependency>
      <groupId>io.sqlmask</groupId>
      <artifactId>mask-policy</artifactId>
      <version>0.1.0-SNAPSHOT</version>
    </dependency>
    <dependency>
      <groupId>io.sqlmask</groupId>
      <artifactId>mask-audit</artifactId>
      <version>0.1.0-SNAPSHOT</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
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
    <dependency>
      <groupId>io.zonky.test</groupId>
      <artifactId>embedded-postgres</artifactId>
      <version>2.0.7</version>
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
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <version>${spring-boot.version}</version>
        <executions>
          <execution>
            <goals><goal>repackage</goal></goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

根 `pom.xml` 的 `<modules>` 在 `<module>mask-metadata</module>` 之后加：

```xml
    <module>mask-policy-server</module>
```

- [ ] **Step 2: git mv 迁移代码与测试**

```bash
cd /c/Users/yhh/orca/mask
# 主代码：policyserver 整包（包名不变）
git mv mask-core/src/main/java/io/sqlmask/policyserver mask-policy-server/src/main/java/io/sqlmask/policyserver
# 主代码：5 个 server 类 → web 包（注意：PolicyApiKeyFilter 不迁，见 Step 4 复制）
mkdir -p mask-policy-server/src/main/java/io/sqlmask/policyserver/web
for f in EffectiveConfigController PolicyAdminController UdfController MetadataImportController MetadataStructureFetcher; do
  git mv "mask-core/src/main/java/io/sqlmask/server/$f.java" "mask-policy-server/src/main/java/io/sqlmask/policyserver/web/$f.java"
done
# schema.sql
git mv mask-core/src/main/resources/schema.sql mask-policy-server/src/main/resources/schema.sql
# 测试：policyserver 整包
git mv mask-core/src/test/java/io/sqlmask/policyserver mask-policy-server/src/test/java/io/sqlmask/policyserver
# 测试：4 个端点测试 → web 包（PolicyApiKeyFilterTest 保留在 core，不迁）
mkdir -p mask-policy-server/src/test/java/io/sqlmask/policyserver/web
for f in EffectiveConfigEndpointTest PolicyAdminEndpointTest UdfEndpointTest MetadataImportEndpointTest; do
  git mv "mask-core/src/test/java/io/sqlmask/server/$f.java" "mask-policy-server/src/test/java/io/sqlmask/policyserver/web/$f.java"
done
```

- [ ] **Step 3: 修正迁移文件的 package 与 import**

对 5 个迁移的主代码类与 4 个迁移的测试类：

1. `package io.sqlmask.server;` → `package io.sqlmask.policyserver.web;`；
2. 删除这 9 个文件之间因同包化而多余的 `io.sqlmask.server.*` / `io.sqlmask.policyserver.web.*` import（如 `MetadataImportController` 引用 `PolicyAdminController.TableDto`）；
3. 其余 `io.sqlmask.policyserver.*` import 原样有效（包名未变）。

用 grep 确认无残留：`grep -rn "io\.sqlmask\.server" mask-policy-server/src` → 期望：无输出。

- [ ] **Step 4: 复制 PolicyApiKeyFilter（不迁移）并写启动类、两个 application.yml、异常处理器**

复制过滤器（core 保留原类与原测试——审计工作线在 core 扩展它，两份必须逐字节一致；worktree 干净，直接复制已提交版本，测试里的 `/api/audit` 用例经 mask-audit 依赖编译、对复制件同样成立）：

```bash
sed 's/^package io\.sqlmask\.server;/package io.sqlmask.policyserver.web;/' \
  mask-core/src/main/java/io/sqlmask/server/PolicyApiKeyFilter.java \
  > mask-policy-server/src/main/java/io/sqlmask/policyserver/web/PolicyApiKeyFilter.java
sed 's/^package io\.sqlmask\.server;/package io.sqlmask.policyserver.web;/' \
  mask-core/src/test/java/io/sqlmask/server/PolicyApiKeyFilterTest.java \
  > mask-policy-server/src/test/java/io/sqlmask/policyserver/web/PolicyApiKeyFilterTest.java
```

`mask-policy-server/src/main/java/io/sqlmask/policyserver/PolicyServerApplication.java`：

```java
package io.sqlmask.policyserver;

import io.sqlmask.policyserver.store.InMemoryPolicyStore;
import io.sqlmask.policyserver.store.JdbcPolicyStore;
import io.sqlmask.policyserver.store.PolicyStore;
import io.sqlmask.policyserver.web.MetadataStructureFetcher;
import io.sqlmask.policyserver.web.PolicyApiKeyFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Standalone policy service: engine instances, UDF registry and policies with
 * their admin REST, plus the subject-parameterized effective-config data
 * plane. The JDBC store is used whenever a datasource is configured (the
 * production default, PostgreSQL); tests exclude the datasource
 * auto-configuration and fall back to the in-memory store.
 */
@SpringBootApplication
public class PolicyServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(PolicyServerApplication.class, args);
  }

  @Bean
  PolicyValidator policyValidator() {
    return new PolicyValidator();
  }

  @Bean
  PolicyService policyService(PolicyStore store, PolicyValidator validator) {
    return new PolicyService(store, validator);
  }

  @Bean
  MetadataStructureFetcher metadataStructureFetcher() {
    return new MetadataStructureFetcher.HttpMetadataStructureFetcher();
  }

  @Bean
  PolicyStore policyStore(ObjectProvider<JdbcTemplate> jdbc) {
    JdbcTemplate template = jdbc.getIfAvailable();
    return template != null ? new JdbcPolicyStore(template) : new InMemoryPolicyStore();
  }

  @Bean
  FilterRegistrationBean<PolicyApiKeyFilter> policyApiKeyFilter() {
    FilterRegistrationBean<PolicyApiKeyFilter> registration =
        new FilterRegistrationBean<>(new PolicyApiKeyFilter(
            System.getenv("SQLMASK_ADMIN_API_KEY"), System.getenv("SQLMASK_DATA_API_KEY")));
    registration.addUrlPatterns("/api/instances/*", "/api/effective/*");
    registration.setOrder(1);
    return registration;
  }
}
```

`mask-policy-server/src/main/resources/application.yml`：

```yaml
server:
  port: 8081

spring:
  application:
    name: mask-policy
  datasource:
    url: ${POLICY_PG_URL:jdbc:postgresql://127.0.0.1:5432/mask_policy}
    username: ${POLICY_PG_USER:postgres}
    password: ${POLICY_PG_PASSWORD:postgres}
  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql
```

`mask-policy-server/src/test/resources/application.yml`（测试一律排除 DataSource 自动装配 → 落到 InMemoryPolicyStore，与迁移前 core 内嵌行为一致）：

```yaml
spring:
  autoconfigure:
    exclude: org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
```

`mask-policy-server/src/main/java/io/sqlmask/policyserver/web/PolicyApiExceptionHandler.java`——core `ApiExceptionHandler` 的复制件（4 个 handler 全保留，形状逐字节一致）：

```java
package io.sqlmask.policyserver.web;

import io.sqlmask.error.SqlMaskException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps processing failures to structured JSON errors, byte-identical to the
 * shapes this service served while embedded in mask-core.
 */
@RestControllerAdvice
public class PolicyApiExceptionHandler {

  public record ApiError(String code, String message) {
  }

  @ExceptionHandler(SqlMaskException.class)
  public ResponseEntity<ApiError> handle(SqlMaskException e) {
    return ResponseEntity.badRequest().body(new ApiError(e.getCode().name(), e.getMessage()));
  }

  /** Policy subsystem errors arrive as plain configuration errors. */
  @ExceptionHandler(io.sqlmask.policy.PolicyException.class)
  public ResponseEntity<ApiError> handlePolicy(io.sqlmask.policy.PolicyException e) {
    return ResponseEntity.badRequest().body(new ApiError("CONFIG_ERROR", e.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST",
        "request body is not valid JSON: " + e.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(Exception e) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
        "INTERNAL_ERROR", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
  }
}
```

- [ ] **Step 5: 从 SqlMaskServiceApplication 删除内嵌策略服务**

`mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java`：删除 import `io.sqlmask.policyserver.PolicyService`、`io.sqlmask.policyserver.PolicyValidator`、`io.sqlmask.policyserver.store.InMemoryPolicyStore`、`io.sqlmask.policyserver.store.PolicyStore`，以及 4 个 @Bean 方法：`policyStore`、`policyValidator`、`policyService`、`metadataStructureFetcher`。保留：CLI 分派逻辑、`rewriteEngine`、`pgMetadataIntrospector`、`policyApiKeyFilter` 注册（core 的过滤器类原地保留，审计工作线依赖它；其 urlPatterns 现为 `/api/instances/*`、`/api/effective/*`、`/api/audit/*`，前两组指向已迁走的控制器无害，由审计工作线按需调整）、其余全部审计相关 @Bean、类上的 `exclude = DataSourceAutoConfiguration.class`（core 依旧无库）。

再全局确认 core 主代码无残留引用：`grep -rln "io\.sqlmask\.policyserver\|MetadataStructureFetcher" mask-core/src/main/java` → 期望：无输出。

- [ ] **Step 6: 构建与测试（迁移验收）**

```bash
mvn -q -pl mask-policy-server -am test    # 迁移测试在新模块全绿（含 JdbcPolicyStoreTest 的 embedded-postgres）
mvn -q -pl mask-core test                 # core 回归全绿（rewrite/CLI/UI/config/metadata）
```

Expected: 两个命令全部 BUILD SUCCESS，0 failures。若 policy-server 端点测试因缺 `PolicyStore` bean 失败，检查 Step 4 的 `policyStore` bean 与 test `application.yml` 是否就位。

- [ ] **Step 7: Commit**

显式列出暂存路径，**禁止 `git add -A`**——工作区可能有其他工作线（审计）的未提交文件，绝不能被卷入：

```bash
git add pom.xml mask-policy-server \
        mask-core/src/main/java/io/sqlmask/policyserver \
        mask-core/src/test/java/io/sqlmask/policyserver \
        mask-core/src/main/java/io/sqlmask/server \
        mask-core/src/test/java/io/sqlmask/server \
        mask-core/src/main/resources/schema.sql
git commit -m "feat(policy-server): extract standalone policy service module from core"
git status --porcelain   # 确认未把无关文件（审计 WIP、untitled.md）带进提交
```

注意：若 Step 7 前工作区仍存在审计工作线的未提交修改（`git status` 中 `mask-core/pom.xml` 的 mask-audit 依赖、`PolicyApiKeyFilterTest.java` 的审计测试等），**中止执行并报告**——这些文件与 Task 1 操作对象重叠，强行继续会把半成品卷进迁移。

---

### Task 2: core 接线——/api/rewrite instance 模式

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/server/InstanceConfigSources.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/RewriteController.java`
- Test: `mask-core/src/test/java/io/sqlmask/server/RewriteInstanceModeTest.java`
- Test: `mask-core/src/test/java/io/sqlmask/server/RewriteInstanceModeUnconfiguredTest.java`

**Interfaces:**
- Consumes: `PolicyServiceConfigSource(String baseUrl, String apiKey, String instanceName)`；`load(Subject)` → `ConfigSource.ResolvedConfig(LoadedConfig config, String dialect, long configVersion)`；`RewriteEngine.rewrite(LoadedConfig, String policyYaml, String sqlText, String dialectName, Subject)`（policyYaml 传 `null` = 用配置自带策略段）；
- Produces: `InstanceConfigSources`（`boolean configured()`；`PolicyServiceConfigSource get(String instance)`；`int clear(String instance)`——null/blank 清全部、返回清除数；`void refreshAll()` 定时入口）。Task 3 的 `/admin/cache/refresh` 依赖 `clear` 的返回值语义。

- [ ] **Step 1: 写失败测试（主测试类）**

`mask-core/src/test/java/io/sqlmask/server/RewriteInstanceModeTest.java`：

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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Instance mode: the compiled effective config comes from the (stubbed)
 * policy service over HTTP; inline YAML is rejected in the same request.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RewriteInstanceModeTest {

  private static final String EFFECTIVE_BODY = """
      {"instance":"pg_prod","dialect":"postgresql","configVersion":1,
       "policySummary":{"enabled":1,"disabled":0},
       "config":{"metadata":{"tables":[{"catalog":"crm","schema":"public","name":"customer",
         "rowFilter":null,"columns":[{"name":"phone","type":"varchar"}]}]},
         "columns":[{"catalog":"crm","schema":"public","table":"customer","column":"phone",
           "policy":"mask_phone"}],
         "policies":{"mask_phone":{"udf":"mask_phone","arguments":[3,4]}}}}
      """;

  private static HttpServer stub;
  private static final AtomicReference<Integer> stubStatus = new AtomicReference<>(200);
  private static final AtomicReference<String> seenApiKey = new AtomicReference<>("");

  @BeforeAll
  static void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress(0), 0);
    stub.createContext("/api/effective/pg_prod", exchange -> {
      seenApiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
      byte[] body = EFFECTIVE_BODY.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(stubStatus.get(), body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    stub.start();
  }

  @AfterAll
  static void stopStub() {
    stub.stop(0);
  }

  @DynamicPropertySource
  static void policyService(DynamicPropertyRegistry registry) {
    registry.add("policy.service.url", () -> "http://localhost:" + stub.getAddress().getPort());
    registry.add("policy.service.api-key", () -> "data-key-1");
  }

  @Autowired
  private MockMvc mvc;

  @Autowired
  private ObjectMapper objectMapper;

  private String rewrite(Map<String, Object> request) throws Exception {
    return mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
        .andReturn().getResponse().getContentAsString();
  }

  @Test
  void rewritesWithCompiledConfigAndSendsConfiguredApiKey() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "instance", "pg_prod", "sql", "SELECT id, phone FROM customer"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statements[0].masked").value(true))
        .andExpect(jsonPath("$.rewrittenSql")
            .value(containsString("mask_phone(r.phone, 3, 4) AS phone")));
    assertThat(seenApiKey.get()).isEqualTo("data-key-1");
  }

  @Test
  void instanceModeIgnoresRequestDialectField() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "instance", "pg_prod", "sql", "SELECT id, phone FROM customer",
                "dialect", "mysql"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rewrittenSql")
            .value(containsString("mask_phone(r.phone, 3, 4) AS phone")));
  }

  @Test
  void rejectsInstanceTogetherWithInlineYaml() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "instance", "pg_prod", "metadataYaml", "metadata: {tables: []}",
                "sql", "SELECT 1"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message", containsString("exactly one of metadataYaml or instance")));
  }

  @Test
  void rejectsMissingBothConfigSources() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("sql", "SELECT 1"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message", containsString("exactly one of metadataYaml or instance")));
  }

  @Test
  void rejectsPolicyYamlInInstanceMode() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "instance", "pg_prod", "policyYaml", "policies: []",
                "sql", "SELECT 1"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message", containsString("policyYaml cannot be combined with instance")));
  }
}
```

- [ ] **Step 2: 写失败测试（未配置 URL 场景，独立上下文）**

`mask-core/src/test/java/io/sqlmask/server/RewriteInstanceModeUnconfiguredTest.java`：

```java
package io.sqlmask.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Instance mode without policy.service.url fails fast with a actionable error. */
@SpringBootTest
@AutoConfigureMockMvc
class RewriteInstanceModeUnconfiguredTest {

  @Autowired
  private MockMvc mvc;

  @Test
  void instanceModeWithoutConfiguredUrlIsAConfigError() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"instance": "pg_prod", "sql": "SELECT id, phone FROM customer"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message", containsString("POLICY_SERVICE_URL")));
  }
}
```

注意：这个测试类与主测试类属性不同，Spring TestContext 会各开一个上下文（`policy.service.url` 一个有值一个没有），互不污染。

- [ ] **Step 3: 运行测试确认编译失败**

```bash
mvn -q -pl mask-core -am test -Dtest='RewriteInstanceMode*Test'
```

Expected: COMPILATION ERROR（`InstanceConfigSources` 不存在、`RewriteRequest` 无 `instance` 字段）。

- [ ] **Step 4: 实现 InstanceConfigSources**

`mask-core/src/main/java/io/sqlmask/server/InstanceConfigSources.java`：

```java
package io.sqlmask.server;

import io.sqlmask.config.source.PolicyServiceConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-instance cache of policy-service clients so rewrite requests reuse the
 * client's subject LRU and stale-but-available cache. The poll cycle calls
 * every client's refresh(); a failed poll is logged and leaves the cached
 * config in place (fail-closed on a cold cache lives in the client itself).
 */
@Component
public class InstanceConfigSources {

  private static final Logger log = LoggerFactory.getLogger(InstanceConfigSources.class);

  private final String baseUrl;
  private final String apiKey;
  private final Map<String, PolicyServiceConfigSource> sources = new ConcurrentHashMap<>();

  public InstanceConfigSources(@Value("${policy.service.url:}") String baseUrl,
      @Value("${policy.service.api-key:}") String apiKey) {
    this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    this.apiKey = apiKey;
  }

  /** False when policy.service.url is not configured: instance mode is unavailable. */
  public boolean configured() {
    return !baseUrl.isEmpty();
  }

  public PolicyServiceConfigSource get(String instance) {
    return sources.computeIfAbsent(instance,
        i -> new PolicyServiceConfigSource(baseUrl, apiKey, i));
  }

  /** Drops one instance (null/blank = all); the next load re-fetches. Returns the count. */
  public int clear(String instance) {
    if (instance == null || instance.isBlank()) {
      int n = sources.size();
      sources.clear();
      return n;
    }
    return sources.remove(instance) != null ? 1 : 0;
  }

  @Scheduled(fixedDelayString = "${policy.service.poll-interval-ms:30000}")
  public void refreshAll() {
    for (Map.Entry<String, PolicyServiceConfigSource> entry : sources.entrySet()) {
      try {
        if (entry.getValue().refresh()) {
          log.info("policy config refreshed for instance '{}'", entry.getKey());
        }
      } catch (RuntimeException e) {
        log.warn("policy config refresh failed for instance '{}' (serving stale cache): {}",
            entry.getKey(), e.getMessage());
      }
    }
  }
}
```

- [ ] **Step 5: 改 RewriteController**

`mask-core/src/main/java/io/sqlmask/server/RewriteController.java` 整文件替换为：

```java
package io.sqlmask.server;

import io.sqlmask.config.source.ConfigSource;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.rewrite.RewriteEngine;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SQL rewriting endpoint, two mutually exclusive configuration modes:
 * inline {@code metadataYaml} (+ optional Ranger-style {@code policyYaml})
 * or a policy-service {@code instance} name whose compiled effective config
 * is fetched per request subject (cached per instance; stale-but-available
 * while the service is down, fail-closed on a cold cache). The whole input
 * is processed atomically; any failure is a structured error.
 */
@RestController
@RequestMapping("/api")
public class RewriteController {

  private final RewriteEngine engine;
  private final InstanceConfigSources sources;

  public RewriteController(RewriteEngine engine, InstanceConfigSources sources) {
    this.engine = engine;
    this.sources = sources;
  }

  @PostMapping("/rewrite")
  public RewriteResponse rewrite(@RequestBody RewriteRequest request) {
    if (request == null || request.sql() == null || request.sql().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "sql is required: provide at least one SELECT statement");
    }
    boolean hasInstance = request.instance() != null && !request.instance().isBlank();
    boolean hasYaml = request.metadataYaml() != null && !request.metadataYaml().isBlank();
    if (hasInstance == hasYaml) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "exactly one of metadataYaml or instance is required: pass the inline YAML "
              + "configuration, or the policy-service instance name to rewrite with "
              + "the compiled effective config");
    }
    Subject subject = Subject.of(request.user(), request.groups());
    List<StatementRewrite> statements;
    String dialect;
    if (hasInstance) {
      if (request.policyYaml() != null && !request.policyYaml().isBlank()) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "policyYaml cannot be combined with instance: instance mode uses the "
                + "policies already compiled into the effective config");
      }
      if (!sources.configured()) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "policy.service.url (env POLICY_SERVICE_URL) is not configured: "
                + "instance mode requires the policy service location");
      }
      ConfigSource.ResolvedConfig resolved = sources.get(request.instance()).load(subject);
      dialect = resolved.dialect();
      statements = engine.rewrite(resolved.config(), null, request.sql(), dialect, subject);
    } else {
      dialect = request.dialect() == null || request.dialect().isBlank()
          ? "postgresql"
          : request.dialect();
      statements = engine.rewrite(request.metadataYaml(), request.policyYaml(),
          request.sql(), dialect, subject);
    }
    return new RewriteResponse(statements, RewriteEngine.join(statements));
  }

  /** Per-statement rewrite request. */
  public record RewriteRequest(String metadataYaml, String policyYaml, String instance,
      String sql, String dialect, String user, java.util.List<String> groups) {
  }

  /** Per-statement rewrite response plus the combined script. */
  public record RewriteResponse(List<StatementRewrite> statements, String rewrittenSql) {
  }
}
```

（`RewriteRequest` 的 `groups` 用 `List<String>`，按原文件的 import 风格放顶部 import 亦可——保持与原文件一致的 import 组织。）

- [ ] **Step 6: 运行测试确认通过**

```bash
mvn -q -pl mask-core -am test -Dtest='RewriteInstanceMode*Test,RewriteControllerTest'
```

Expected: 全部 PASS（含既有内联模式测试零回归）。

- [ ] **Step 7: Commit**

```bash
git add mask-core/src/main/java/io/sqlmask/server/InstanceConfigSources.java \
        mask-core/src/main/java/io/sqlmask/server/RewriteController.java \
        mask-core/src/test/java/io/sqlmask/server/RewriteInstanceModeTest.java \
        mask-core/src/test/java/io/sqlmask/server/RewriteInstanceModeUnconfiguredTest.java
git commit -m "feat(core): instance mode for /api/rewrite via policy service"
```

---

### Task 3: 手动刷新端点 + 定时刷新装配

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/server/CacheRefreshController.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java`（加 `@EnableScheduling`）
- Test: `mask-core/src/test/java/io/sqlmask/server/CacheRefreshControllerTest.java`

**Interfaces:**
- Consumes: Task 2 的 `InstanceConfigSources.clear(String)`（返回清除数）与 `refreshAll()`；
- Produces: `POST /admin/cache/refresh`（可选 body `{"instance": "..."}`，响应 `{"cleared": <int>}`）。Task 6 端到端用它验证"禁用策略后立即生效"。

- [ ] **Step 1: 写失败测试**

`mask-core/src/test/java/io/sqlmask/server/CacheRefreshControllerTest.java`：

```java
package io.sqlmask.server;

import io.sqlmask.config.source.PolicyServiceConfigSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Manual cache drop and the scheduled refresh entry (no live service needed). */
class CacheRefreshControllerTest {

  @Test
  void cachesPerInstanceAndClearsSelectivelyOrWholly() {
    InstanceConfigSources sources = new InstanceConfigSources("http://127.0.0.1:1", "k");
    PolicyServiceConfigSource a = sources.get("a");
    assertThat(sources.get("a")).isSameAs(a);
    assertThat(sources.get("b")).isNotSameAs(a);

    assertThat(sources.clear("a")).isEqualTo(1);
    assertThat(sources.get("a")).isNotSameAs(a);
    assertThat(sources.clear(null)).isEqualTo(2); // a (rebuilt above) + b
  }

  @Test
  void scheduledRefreshSurvivesUnreachableService() {
    InstanceConfigSources sources = new InstanceConfigSources("http://127.0.0.1:1", "k");
    sources.get("a");
    assertThatCode(sources::refreshAll).doesNotThrowAnyException(); // stale semantics
  }

  @Test
  void refreshEndpointReportsClearedCountAndAcceptsEmptyBody() throws Exception {
    InstanceConfigSources sources = new InstanceConfigSources("http://127.0.0.1:1", "k");
    sources.get("a");
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new CacheRefreshController(sources)).build();

    mvc.perform(post("/admin/cache/refresh")
            .contentType("application/json")
            .content("{\"instance\": \"a\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cleared").value(1));
    mvc.perform(post("/admin/cache/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cleared").value(0));
  }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -q -pl mask-core -am test -Dtest='CacheRefreshControllerTest'
```

Expected: COMPILATION ERROR（`CacheRefreshController` 不存在）。

- [ ] **Step 3: 实现 CacheRefreshController 并启用调度**

`mask-core/src/main/java/io/sqlmask/server/CacheRefreshController.java`：

```java
package io.sqlmask.server;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual cache drop on the rewrite service (policy-service spec §5.2): the
 * next instance-mode request re-fetches from the policy service.
 */
@RestController
public class CacheRefreshController {

  public record RefreshRequest(String instance) {
  }

  public record RefreshResponse(int cleared) {
  }

  private final InstanceConfigSources sources;

  public CacheRefreshController(InstanceConfigSources sources) {
    this.sources = sources;
  }

  @PostMapping("/admin/cache/refresh")
  public RefreshResponse refresh(@RequestBody(required = false) RefreshRequest request) {
    return new RefreshResponse(sources.clear(request == null ? null : request.instance()));
  }
}
```

`SqlMaskServiceApplication` 类上加 `@EnableScheduling`（import `org.springframework.scheduling.annotation.EnableScheduling`）——驱动 Task 2 `InstanceConfigSources.refreshAll()` 的 `@Scheduled`。

- [ ] **Step 4: 运行测试确认通过**

```bash
mvn -q -pl mask-core -am test -Dtest='CacheRefreshControllerTest'
```

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add mask-core/src/main/java/io/sqlmask/server/CacheRefreshController.java \
        mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java \
        mask-core/src/test/java/io/sqlmask/server/CacheRefreshControllerTest.java
git commit -m "feat(core): manual cache refresh endpoint and scheduled config polling"
```

---

### Task 4: CLI 接线——--instance / --policy-service

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/cli/CliOptions.java`
- Modify: `mask-core/src/main/java/io/sqlmask/cli/SqlMaskApplication.java`
- Modify: `mask-core/src/main/java/io/sqlmask/cli/SqlMaskRunner.java`
- Modify: `mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java`（CLI 选项清单加两个新旗标）
- Test: `mask-core/src/test/java/io/sqlmask/cli/SqlMaskInstanceModeTest.java`

**Interfaces:**
- Consumes: 同 Task 2 的 `PolicyServiceConfigSource` / `ResolvedConfig` / `RewriteEngine`；
- Produces: `CliOptions(Path metadataPath, Path policiesPath, String user, List<String> groups, String sql, Path inputPath, Path outputPath, String dialect, String instance, String policyServiceUrl)`（10 参规范构造器；原 8 参与 5 参构造器保留并委托）、`boolean instanceMode()`。jar 调用识别清单 `CLI_OPTIONS` 含 `--instance`、`--policy-service`。

- [ ] **Step 1: 写失败测试**

`mask-core/src/test/java/io/sqlmask/cli/SqlMaskInstanceModeTest.java`：

```java
package io.sqlmask.cli;

import com.sun.net.httpserver.HttpServer;
import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CLI instance mode: config from the policy service instead of --metadata. */
class SqlMaskInstanceModeTest {

  private static final String EFFECTIVE_BODY = """
      {"instance":"pg_prod","dialect":"postgresql","configVersion":1,
       "policySummary":{"enabled":1,"disabled":0},
       "config":{"metadata":{"tables":[{"catalog":"crm","schema":"public","name":"customer",
         "rowFilter":null,"columns":[{"name":"phone","type":"varchar"}]}]},
         "columns":[{"catalog":"crm","schema":"public","table":"customer","column":"phone",
           "policy":"mask_phone"}],
         "policies":{"mask_phone":{"udf":"mask_phone","arguments":[3,4]}}}}
      """;

  private static HttpServer stub;

  @BeforeAll
  static void startStub() throws IOException {
    stub = HttpServer.create(new InetSocketAddress(0), 0);
    stub.createContext("/api/effective/pg_prod", exchange -> {
      byte[] body = EFFECTIVE_BODY.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    stub.start();
  }

  @AfterAll
  static void stopStub() {
    stub.stop(0);
  }

  @Test
  void rewritesViaPolicyServiceWithoutMetadataFile() {
    CliOptions options = new CliOptions(null, null, null, null,
        "SELECT id, phone FROM customer", null, null, "postgresql",
        "pg_prod", "http://localhost:" + stub.getAddress().getPort());
    String out = new SqlMaskRunner().run(options);
    assertThat(out).contains("mask_phone(r.phone, 3, 4) AS phone");
  }

  @Test
  void missingServiceUrlFailsClosed() {
    Assumptions.assumeTrue(System.getenv("POLICY_SERVICE_URL") == null,
        "POLICY_SERVICE_URL is set on this machine");
    CliOptions options = new CliOptions(null, null, null, null,
        "SELECT 1", null, null, "postgresql", "pg_prod", null);
    assertThatThrownBy(() -> new SqlMaskRunner().run(options))
        .isInstanceOf(SqlMaskException.class)
        .hasMessageContaining("policy service");
  }

  @Test
  void instanceCannotBeCombinedWithMetadata() {
    String out = new SqlMaskApplication().run(
        new String[] {"--instance", "pg_prod", "--metadata", "whatever.yaml",
            "--sql", "SELECT 1"},
        System.in, System.out, System.err);
    assertThat(out).isEqualTo(2);
  }
}
```

（第三个测试的 stderr 文案断言省略——返回码 2 即契约；需要时可捕获 err 流断言 `cannot be combined`。）

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -q -pl mask-core -am test -Dtest='SqlMaskInstanceModeTest'
```

Expected: COMPILATION ERROR（`CliOptions` 无 10 参构造器）。

- [ ] **Step 3: 扩展 CliOptions**

`CliOptions.java` 改为（10 参为规范构造器，旧构造器委托）：

```java
public record CliOptions(Path metadataPath, Path policiesPath, String user, List<String> groups,
    String sql, Path inputPath, Path outputPath, String dialect,
    String instance, String policyServiceUrl) {

  public CliOptions {
    if (metadataPath == null && (instance == null || instance.isBlank())) {
      throw new IllegalArgumentException("metadataPath or instance is required");
    }
    groups = groups == null ? List.of() : List.copyOf(groups);
    dialect = dialect == null ? "postgresql" : dialect;
  }

  /** Legacy convenience constructor: no policy file and an anonymous subject. */
  public CliOptions(Path metadataPath, String sql, Path inputPath, Path outputPath,
      String dialect) {
    this(metadataPath, null, null, null, sql, inputPath, outputPath, dialect, null, null);
  }

  /** Legacy constructor from before policy-service instance mode. */
  public CliOptions(Path metadataPath, Path policiesPath, String user, List<String> groups,
      String sql, Path inputPath, Path outputPath, String dialect) {
    this(metadataPath, policiesPath, user, groups, sql, inputPath, outputPath, dialect,
        null, null);
  }

  /** True when the configuration should come from the policy service. */
  public boolean instanceMode() {
    return instance != null && !instance.isBlank();
  }

  public Optional<Path> output() {
    return Optional.ofNullable(outputPath);
  }
}
```

javadoc 的 `@param` 列表补 `@param instance  policy-service instance name (instance mode; mutually exclusive with metadataPath)`、`@param policyServiceUrl  policy service base URL override (default $POLICY_SERVICE_URL)`。

- [ ] **Step 4: 改 SqlMaskApplication**

`SqlMaskApplication.java` 三处：

1. 加两个 @Option 字段（放在 `--dialect` 之后）：

```java
  @Option(names = "--instance", paramLabel = "<name>",
      description = "Policy-service instance name: rewrite with the compiled effective "
          + "config fetched from the policy service. Mutually exclusive with --metadata "
          + "and --policies.")
  private String instance;

  @Option(names = "--policy-service", paramLabel = "<url>",
      description = "Policy service base URL for --instance (default $POLICY_SERVICE_URL). "
          + "API key is read from $POLICY_SERVICE_API_KEY.")
  private String policyService;
```

2. `execute(...)` 中把 `if (metadataPath == null) { err.println("sql-mask: --metadata is required for rewriting"); return 2; }` 替换为：

```java
    if (instance != null && !instance.isBlank()) {
      if (metadataPath != null) {
        err.println("sql-mask: --instance cannot be combined with --metadata");
        return 2;
      }
      if (policiesPath != null) {
        err.println("sql-mask: --policies cannot be combined with --instance");
        return 2;
      }
    } else if (metadataPath == null) {
      err.println("sql-mask: --metadata is required for rewriting");
      return 2;
    }
```

3. `CliOptions` 构造改为 10 参：

```java
    CliOptions options = new CliOptions(metadataPath, policiesPath, user, groups,
        sql, inputPath, outputPath, dialect, instance, policyService);
```

- [ ] **Step 5: 改 SqlMaskRunner**

`SqlMaskRunner.run` 替换为双模式：

```java
  public String run(CliOptions options) {
    String sqlText = options.sql() != null
        ? options.sql()
        : readUtf8(options.inputPath(), "SQL input file");
    List<StatementRewrite> statements;
    if (options.instanceMode()) {
      String baseUrl = options.policyServiceUrl() != null && !options.policyServiceUrl().isBlank()
          ? options.policyServiceUrl()
          : System.getenv("POLICY_SERVICE_URL");
      if (baseUrl == null || baseUrl.isBlank()) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "policy service URL is required: pass --policy-service or set "
                + "$POLICY_SERVICE_URL");
      }
      Subject subject = Subject.of(options.user(), options.groups());
      ConfigSource.ResolvedConfig resolved = new PolicyServiceConfigSource(baseUrl,
          System.getenv("POLICY_SERVICE_API_KEY"), options.instance()).load(subject);
      statements = new RewriteEngine().rewrite(resolved.config(), null, sqlText,
          resolved.dialect(), subject);
    } else {
      String metadataYaml = readUtf8(options.metadataPath(), "metadata file");
      String policyYaml = options.policiesPath() == null
          ? null
          : readUtf8(options.policiesPath(), "policies file");
      statements = new RewriteEngine().rewrite(metadataYaml, policyYaml, sqlText,
          options.dialect(), Subject.of(options.user(), options.groups()));
    }
    return RewriteEngine.join(statements);
  }
```

新增 import：`io.sqlmask.config.source.ConfigSource`、`io.sqlmask.config.source.PolicyServiceConfigSource`。

- [ ] **Step 6: jar 模式识别清单加新旗标**

`SqlMaskServiceApplication.CLI_OPTIONS` 列表追加 `"--instance", "--policy-service"`（否则 `java -jar sql-mask.jar --instance ...` 会误启 Web 服务）。

- [ ] **Step 7: 运行测试确认通过 + core 全量回归**

```bash
mvn -q -pl mask-core -am test -Dtest='SqlMaskInstanceModeTest'
mvn -q -pl mask-core test
```

Expected: 全部 PASS（既有 CLI 测试零回归——旧构造器仍在）。

- [ ] **Step 8: Commit**

```bash
git add mask-core/src/main/java/io/sqlmask/cli/ mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java mask-core/src/test/java/io/sqlmask/cli/SqlMaskInstanceModeTest.java
git commit -m "feat(cli): --instance/--policy-service for policy-service-backed rewrites"
```

---

### Task 5: 部署物——Dockerfile、compose、README

**Files:**
- Create: `docker/policy.Dockerfile`
- Create: `docker-compose.policy.yml`
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 1 的 fat jar（`mask-policy-server/target/mask-policy-server-*.jar`）与 8081 服务；
- Produces: 本地全套部署入口（Task 6 使用）。

- [ ] **Step 1: 写 policy.Dockerfile**

`docker/policy.Dockerfile`（逐行照 metadata.Dockerfile，仅换 jar 路径）：

```
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY mask-policy-server/target/mask-policy-server-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 2: 写 docker-compose.policy.yml**

与 `docker-compose.metadata.yml` 对称；host 侧 PG 用 5433 避免与 metadata compose 的 5432 冲突：

```yaml
services:
  policy-postgres:
    image: postgres:16
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
      POSTGRES_DB: mask_policy
    ports:
      - "5433:5432"

  policy:
    build:
      context: .
      dockerfile: docker/policy.Dockerfile
    environment:
      SQLMASK_ADMIN_API_KEY: local-admin-key
      SQLMASK_DATA_API_KEY: local-data-key
      POLICY_PG_URL: jdbc:postgresql://policy-postgres:5432/mask_policy
      POLICY_PG_USER: postgres
      POLICY_PG_PASSWORD: postgres
    ports:
      - "8081:8081"
    depends_on:
      - policy-postgres
```

- [ ] **Step 3: 更新 README**

`README.md` 做四处手术式修改（锚点用 grep 找，不要整段重写无关内容）：

1. 开头「Web 服务（默认）」小节之后加「服务形态」小节：

```markdown
## 服务形态

同一仓库产出三个微服务 + 一个独立 CLI 模块：

| 服务 | 模块 | 端口 | 职责 |
|---|---|---|---|
| 改写服务 | mask-core | 8080 | `/api/rewrite`（内联 YAML 或 instance 模式）、内置页面、CLI |
| 策略服务 | mask-policy-server | 8081 | 实例/策略/UDF 管理面、按主体编译的 `/api/effective` 数据面 |
| 元数据服务 | mask-metadata | 8082 | 库表结构采集与存储 |

改写服务的 instance 模式按实例名从策略服务拉取编译配置：`POLICY_SERVICE_URL` +
`POLICY_SERVICE_API_KEY` 两个环境变量接入；进程内按主体缓存（LRU 256），每
`POLICY_SERVICE_POLL_INTERVAL_MS`（默认 30000）轮询刷新，策略服务短暂不可用时
继续用缓存改写（stale-but-available），无缓存时绝不降级（fail closed）。紧急
止血可 `POST /admin/cache/refresh`（可带 `{"instance": "..."}`）立即清缓存。
```

2. 找到内嵌管理面相关描述（grep `api/instances`），如 README 有「策略微服务已内嵌」类表述，改为「管理面在独立策略服务（8081）上」；单 jar 说明处补一句：

```markdown
注意：单 jar（mask-core）不再内嵌策略管理面；实例/策略/UDF 的 REST 由
mask-policy-server 在 8081 提供。
```

3. CLI 章节补 instance 模式用法：

```markdown
instance 模式（配置来自策略服务，与 --metadata 互斥）：

```bash
java -jar mask-core/target/sql-mask.jar --instance pg_prod \
  --policy-service http://localhost:8081 \
  --user alice --sql "SELECT phone FROM customer"
```

`--policy-service` 缺省读 `$POLICY_SERVICE_URL`；API Key 读 `$POLICY_SERVICE_API_KEY`。
```

4. 部署/构建章节（如有）补：

```markdown
本地起策略服务 + PG：

```bash
mvn -pl mask-policy-server -am package
docker compose -f docker-compose.policy.yml up -d
```

环境变量：`POLICY_PG_URL/USER/PASSWORD`（PG 连接）、`SQLMASK_ADMIN_API_KEY`
（管理面 `/api/instances/**`）、`SQLMASK_DATA_API_KEY`（数据面 `/api/effective/**`），
未配置 Key 则不拦截。
```

- [ ] **Step 4: Commit**

```bash
git add docker/policy.Dockerfile docker-compose.policy.yml README.md
git commit -m "docs(deploy): policy service docker image, compose file and README"
```

---

### Task 6: 端到端验证（compose 起全套 + curl 全流程）

**Files:**
- 无新文件（验证任务；产物是验证记录，如遇修复则修复进对应文件并单独提交）

**Interfaces:**
- Consumes: Task 1–5 全部产物。

- [ ] **Step 1: 全量构建**

```bash
mvn -q -DskipTests package
ls mask-policy-server/target/mask-policy-server-*.jar mask-core/target/sql-mask*.jar
```

Expected: 两个 jar 存在。

- [ ] **Step 2: 起策略服务 + PG**

```bash
docker compose -f docker-compose.policy.yml up -d --build
sleep 8
curl -s http://localhost:8081/actuator/health || curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/instances
```

Expected: 后者返回 `200`（未配 Key 时；compose 里配了 Key，则用 `-H "X-Api-Key: local-admin-key"`，期望 200；无 Key 期望 401）。

- [ ] **Step 3: 管理面建实例 / UDF / 策略**

```bash
K='X-Api-Key: local-admin-key'
curl -s -X POST http://localhost:8081/api/instances -H "$K" -H 'Content-Type: application/json' -d '{
  "name": "pg_prod", "dialect": "postgresql",
  "tables": [{"catalog": "crm", "schema": "public", "name": "customer",
              "columns": [{"name": "id", "type": "bigint"}, {"name": "phone", "type": "varchar"}]}]}'

curl -s -X POST http://localhost:8081/api/instances/pg_prod/udfs -H "$K" -H 'Content-Type: application/json' -d '{
  "name": "mask_phone",
  "signatures": [{"params": ["varchar", "bigint", "bigint"], "returns": "varchar"}]}'

curl -s -X POST http://localhost:8081/api/instances/pg_prod/policies -H "$K" -H 'Content-Type: application/json' -d '{
  "name": "mask_analysts", "policyType": "datamask", "isEnabled": true,
  "resource": {"catalog": "crm", "schema": "public", "table": "customer", "columns": ["phone"]},
  "subjects": {"users": ["*"]},
  "udf": "mask_phone", "arguments": [3, 4]}'

curl -s "http://localhost:8081/api/effective/pg_prod?user=alice" -H 'X-Api-Key: local-data-key'
```

Expected: 前三个返回 200/创建成功 JSON；最后一步 JSON 含 `"mask_phone"` 与 `"configVersion"`。

- [ ] **Step 4: core 以 instance 模式改写**

```bash
POLICY_SERVICE_URL=http://localhost:8081 POLICY_SERVICE_API_KEY=local-data-key \
  java -jar mask-core/target/sql-mask*.jar &
sleep 6
curl -s -X POST http://localhost:8080/api/rewrite -H 'Content-Type: application/json' -d '{
  "instance": "pg_prod", "user": "alice", "sql": "SELECT id, phone FROM customer"}'
```

Expected: 响应 `rewrittenSql` 含 `mask_phone(r.phone, 3, 4) AS phone`。

- [ ] **Step 5: 禁用策略 → 手动刷新 → 立即原样输出**

```bash
curl -s -X PUT http://localhost:8081/api/instances/pg_prod/policies/mask_analysts \
  -H 'X-Api-Key: local-admin-key' -H 'Content-Type: application/json' -d '{
  "name": "mask_analysts", "policyType": "datamask", "isEnabled": false,
  "resource": {"catalog": "crm", "schema": "public", "table": "customer", "columns": ["phone"]},
  "subjects": {"users": ["*"]},
  "udf": "mask_phone", "arguments": [3, 4]}'

curl -s -X POST http://localhost:8080/admin/cache/refresh -H 'Content-Type: application/json' -d '{"instance": "pg_prod"}'

curl -s -X POST http://localhost:8080/api/rewrite -H 'Content-Type: application/json' -d '{
  "instance": "pg_prod", "user": "alice", "sql": "SELECT id, phone FROM customer"}'
```

Expected: 刷新响应 `{"cleared":1}`；改写响应不再含 `mask_phone`（禁用策略剔除 → 原样输出，spec §7.1 语义）。

- [ ] **Step 6: 复跑全量测试 + 收尾**

```bash
kill %1 2>/dev/null; true
docker compose -f docker-compose.policy.yml down
mvn -q test
```

Expected: 根模块全量测试 BUILD SUCCESS。有任何端到端失败：按 spec 定位修复，修复单独提交（`fix(...)`），并复跑本任务失败步骤。

- [ ] **Step 7: 汇报**

在最终汇报中列出：迁移的文件数、新增端点/参数、三个服务的端口、测试结果（模块级 + 全量）、e2e 各步的实际响应摘录。

---

## Self-Review 记录

- **Spec 覆盖**：spec §2 模块（Task 1）、§3 服务形态/存储/鉴权（Task 1 Step 4）、§4.1 移除（Task 1 Step 5）、§4.3 instance 接线（Task 2）、手动刷新与轮询（Task 2/3）、CLI（Task 4）、§5 部署（Task 5）、§8 测试（Task 1 Step 6 / Task 6 + 各任务测试步骤）——无缺口；spec §1.2 YAGNI（/version 端点、404/503 细分）未引入对应任务，符合。
- **占位符**：无 TBD/TODO；所有代码步骤给出完整代码或精确到行的替换说明。
- **类型一致性**：`InstanceConfigSources.clear(String)→int` / `configured()` / `get(String)` 在 Task 2 定义、Task 3 消费一致；`ResolvedConfig` 统一经 `ConfigSource.ResolvedConfig` 引用；`CliOptions` 10 参规范构造器与 Task 4 Step 4 的构造调用一致。
