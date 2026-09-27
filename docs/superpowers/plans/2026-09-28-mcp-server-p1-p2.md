# MCP Server（P1+P2：改写面 + HTTP 传输）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 `mask-mcp-server` Maven 模块，以 MCP tools 形式暴露 sql-mask 的改写面（`rewrite_sql` / `validate_config` / `list_dialects`），stdio 与 Streamable HTTP 双传输，交付单 jar 与部署物。

**Architecture:** 进程内直调 `mask-engine` 的 `RewriteEngine`（与 mask-core Controller 同源），零 Spring（enforcer 强制，与 mask-engine 同纪律）。每个 tool 是实现 `McpTool` 接口的纯函数类（`call(Map) -> CallToolResult`），单测直调不经协议层；SDK 装配集中在 `McpServerFactory`，协议正确性由 stdio/HTTP 两个端到端测试覆盖。

**Tech Stack:** Java 17、官方 MCP Java SDK 2.0.0（`mcp-core` + `mcp-json-jackson2`）、Jackson 2.17.2、Jetty 12.0.16（ee10，仅 HTTP 模式）、JUnit 5.10.2、Maven shade。

**Spec:** `docs/superpowers/specs/2026-09-28-mcp-server-design.md`（本计划实现其 P1+P2；P3 instance/查询面另立计划）

## Global Constraints

- 模块 **零 Spring、零 picocli**：pom 里 enforcer bannedDependencies 照抄 mask-engine 的规则（Task 1 给全文）。**mask-common 依赖 spring-boot-starter-web，本模块禁止依赖 mask-common**；`ApiError` 在本模块自带同构 record。
- **stdout 只留给 MCP 协议**（stdio 模式）：任何日志/警告一律 stderr（slf4j-simple 默认即 stderr，不要改）。
- 版本固定：`io.modelcontextprotocol.sdk:mcp-bom:2.0.0`；Jackson `2.17.2`；Jetty `12.0.16`；JUnit 用 parent 的 `${junit.version}`。
- 提交信息用中文 conventional commits（`feat(mcp): ...`），每个任务一提交。
- 测试命令（仓库根执行）：`mvn -pl mask-mcp-server -am test`（首次或改了上游模块时带 `-am`；只跑本模块 `mvn -pl mask-mcp-server test`）。
- 内核 API 事实（后续任务直接引用，勿再考据）：
  - `new RewriteEngine().rewrite(String metadataYaml, String policyYaml /*blank=legacy*/, String sqlText, String dialectName, Subject subject)` → `List<RewriteEngine.StatementRewrite>`
  - `StatementRewrite` 字段：`ordinal, originalSql, rewrittenSql, masked, rowFiltered, kind, inheritedColumns, inheritedTables` + `unchanged()`
  - `RewriteEngine.join(List<StatementRewrite>)` 静态合并
  - `io.sqlmask.policy.model.Subject.of(String user, List<String> groups)` / `Subject.anonymous()`
  - `io.sqlmask.error.SqlMaskException extends RuntimeException`，`getCode()` → 枚举 `SqlMaskException.Code`
  - `new io.sqlmask.config.YamlConfigLoader().loadContent(String yaml, String sourceName, String dialect)` → `LoadedConfig`
  - `new io.sqlmask.policy.store.PolicyYamlLoader().parse(String yaml, String sourceName)` → `List<Policy>`（mask-policy 模块，经 mask-engine 传递可用）
  - `io.sqlmask.dialect.DialectProfiles.names()` → `Set<String>`（实际值：`postgresql, trino, mysql, hive, sparksql`）
  - `ApiError` 同构（mask-common 的形状）：`record ApiError(String code, String message, List<String> details)`

## SDK 校准点（写给执行者）

SDK 2.0 的下列 API 细节来自官方文档，与 jackson2 变体可能有出入。**Task 1/2 的测试转绿是唯一验收标准**；若编译失败，按以下顺序自查（javdoc: https://java.sdk.modelcontextprotocol.io）并修正调用点，但**不得改动已定义的类名/方法签名**（`McpTool`、`McpErrors`、`McpServerFactory.specFor`、`Schemas.parse`），后续任务依赖它们：

1. `Tool.builder(name, schema)` 的 `schema` 参数：首选 `Schemas.parse(json)` 返回的类型；若 builder 直接收 `String`，则 `Schemas.parse` 改为透传字符串、签名不变。
2. `StdioServerTransportProvider` 的 mapper 构造参数：首选 SDK 的 jackson2 mapper 工厂（形如 `McpJson`/`McpJsonDefaults` 的 jackson2 变体）；找不到就用 `new ObjectMapper().findAndRegisterModules()` 包成 SDK 要求的类型。
3. `CallToolResult.builder().structuredContent(Map)`：存在则 `McpErrors` 里启用；不存在则删除该调用，text JSON 已承载同 payload（两条路径的代码都写在 Task 2）。

---

### Task 1: 模块骨架——pom + enforcer + Main 参数解析 + tool spec 构造

**Files:**
- Create: `mask-mcp-server/pom.xml`
- Modify: `pom.xml`（根，`<modules>` 加一行）
- Create: `mask-mcp-server/src/main/java/io/sqlmask/mcp/McpTool.java`
- Create: `mask-mcp-server/src/main/java/io/sqlmask/mcp/Schemas.java`
- Create: `mask-mcp-server/src/main/java/io/sqlmask/mcp/Main.java`
- Create: `mask-mcp-server/src/main/java/io/sqlmask/mcp/McpServerFactory.java`
- Test: `mask-mcp-server/src/test/java/io/sqlmask/mcp/MainTest.java`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces（后续任务依赖的签名，不得偏离）:
  - `interface McpTool { String name(); String description(); String schemaJson(); io.modelcontextprotocol.sdk.mcp.McpSchema.CallToolResult call(Map<String,Object> arguments); }`
  - `final class Schemas { public static Object parse(String json) }`（返回值类型以 SDK 为准，但方法签名不变）
  - `final class Main { static Main.Config parseArgs(String[] args) }`，`record Config(String transport, int port)`，缺省 `("stdio", 8084)`
  - `final class McpServerFactory { public static SyncToolSpecification specFor(McpTool tool) }`（类型名以 SDK 为准，方法签名不变）

- [ ] **Step 1: 建目录与 pom**

`mask-mcp-server/pom.xml`（enforcer 规则照抄 mask-engine，message 措辞改为本模块）：

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

  <artifactId>mask-mcp-server</artifactId>
  <packaging>jar</packaging>

  <name>mask-mcp-server</name>
  <description>MCP server exposing the masking rewrite kernel: stdio + Streamable HTTP, zero Spring (enforced).</description>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.modelcontextprotocol.sdk</groupId>
        <artifactId>mcp-bom</artifactId>
        <version>2.0.0</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>io.sqlmask</groupId>
      <artifactId>mask-engine</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>io.modelcontextprotocol.sdk</groupId>
      <artifactId>mcp-core</artifactId>
    </dependency>
    <dependency>
      <groupId>io.modelcontextprotocol.sdk</groupId>
      <artifactId>mcp-json-jackson2</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>2.17.2</version>
    </dependency>
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
      <version>2.0.13</version>
    </dependency>
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-simple</artifactId>
      <version>2.0.13</version>
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
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <version>3.4.1</version>
        <executions>
          <execution>
            <id>enforce-mcp-purity</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
              <rules>
                <bannedDependencies>
                  <excludes>
                    <exclude>org.springframework:*:*:jar:compile</exclude>
                    <exclude>org.springframework:*:*:jar:runtime</exclude>
                    <exclude>org.springframework:*:*:jar:provided</exclude>
                    <exclude>org.springframework.boot:*:*:jar:compile</exclude>
                    <exclude>org.springframework.boot:*:*:jar:runtime</exclude>
                    <exclude>org.springframework.boot:*:*:jar:provided</exclude>
                    <exclude>info.picocli:*:*:jar:compile</exclude>
                    <exclude>info.picocli:*:*:jar:runtime</exclude>
                    <exclude>info.picocli:*:*:jar:provided</exclude>
                    <exclude>io.sqlmask:mask-common:*</exclude>
                  </excludes>
                  <message>mask-mcp-server is a Spring-free MCP facade; ApiError is duplicated locally by design</message>
                </bannedDependencies>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

根 `pom.xml` 的 `<modules>` 尾部（`mask-lite` 之后）加：

```xml
    <module>mask-mcp-server</module>
```

- [ ] **Step 2: 写失败测试**

`mask-mcp-server/src/test/java/io/sqlmask/mcp/MainTest.java`：

```java
package io.sqlmask.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MainTest {

  @Test
  void defaultsAreStdioAnd8084() {
    Main.Config cfg = Main.parseArgs(new String[0]);
    assertEquals("stdio", cfg.transport());
    assertEquals(8084, cfg.port());
  }

  @Test
  void parsesTransportAndPort() {
    Main.Config cfg = Main.parseArgs(new String[] {"--transport", "http", "--port", "9090"});
    assertEquals("http", cfg.transport());
    assertEquals(9090, cfg.port());
  }

  @Test
  void rejectsUnknownArg() {
    IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> Main.parseArgs(new String[] {"--nope"}));
    assertEquals("unknown arg: --nope", e.getMessage());
  }

  /** 骨架冒烟：Schemas.parse 可解析合法 schema，specFor 能组装 SyncToolSpecification。 */
  @Test
  void specForAssemblesToolSpecification() {
    McpTool ping = new McpTool() {
      @Override public String name() { return "ping"; }
      @Override public String description() { return "smoke"; }
      @Override public String schemaJson() {
        return "{\"type\":\"object\",\"properties\":{},\"required\":[]}"; }
      @Override public io.modelcontextprotocol.sdk.mcp.McpSchema.CallToolResult call(
          Map<String, Object> arguments) {
        throw new UnsupportedOperationException();
      }
    };
    Object spec = McpServerFactory.specFor(ping);
    assertNotNull(spec);
  }
}
```

- [ ] **Step 3: 跑测试确认编译失败**

Run: `mvn -pl mask-mcp-server -am test -Dtest=MainTest`
Expected: COMPILATION ERROR（`McpTool`/`Main`/`McpServerFactory`/`Schemas` 不存在）

- [ ] **Step 4: 写最小实现**

`McpTool.java`：

```java
package io.sqlmask.mcp;

import io.modelcontextprotocol.sdk.mcp.McpSchema;

import java.util.Map;

/**
 * One MCP tool: name/description/schema plus a pure call function. Handlers
 * are plain classes so unit tests call them directly without any transport.
 */
public interface McpTool {

  String name();

  String description();

  /** JSON Schema (object) describing the call arguments. */
  String schemaJson();

  /** Never throws: failures come back as an isError CallToolResult via McpErrors. */
  McpSchema.CallToolResult call(Map<String, Object> arguments);
}
```

`Schemas.java`：

```java
package io.sqlmask.mcp;

/** JSON-schema helper: the only place that knows how the SDK ingests schemas. */
public final class Schemas {

  /** Parses a JSON Schema string into whatever Tool.builder accepts (SDK-calibrated). */
  public static Object parse(String json) {
    // 校准点 1：首选把 json 反序列化为 SDK 的 JsonSchema 类型（mcp-json-jackson2 的
    // mapper readValue(json, McpSchema.JsonSchema.class)）；若 Tool.builder 直接收
    // String，则直接 return json。签名不变。
    return json;
  }

  private Schemas() {
  }
}
```

`Main.java`：

```java
package io.sqlmask.mcp;

/** Entry point: --transport stdio|http (default stdio), --port (default 8084). */
public final class Main {

  record Config(String transport, int port) {
  }

  public static void main(String[] args) throws Exception {
    Config cfg = parseArgs(args);
    // P1: stdio; Task 7 adds the http branch delegating to JettyHttpServer.run(cfg.port(), tools)
    throw new UnsupportedOperationException("wired in Task 6");
  }

  static Config parseArgs(String[] args) {
    String transport = "stdio";
    int port = 8084;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--transport" -> transport = args[++i];
        case "--port" -> port = Integer.parseInt(args[++i]);
        default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
      }
    }
    return new Config(transport, port);
  }

  private Main() {
  }
}
```

`McpServerFactory.java`：

```java
package io.sqlmask.mcp;

import io.modelcontextprotocol.sdk.mcp.McpSchema;
import io.modelcontextprotocol.sdk.server.McpServer;
import io.modelcontextprotocol.sdk.server.McpSyncServer;
import io.modelcontextprotocol.sdk.server.SyncToolSpecification;
import io.modelcontextprotocol.sdk.tool.Tool;

import java.util.List;

/** SDK assembly: the only place that touches SDK builders (calibration surface). */
public final class McpServerFactory {

  /** Builds a SyncToolSpecification wrapping the pure McpTool handler. */
  public static SyncToolSpecification specFor(McpTool tool) {
    return SyncToolSpecification.builder()
        .tool(Tool.builder(tool.name(), (io.modelcontextprotocol.sdk.mcp.McpSchema.JsonSchema) Schemas.parse(tool.schemaJson()))
            .description(tool.description())
            .build())
        .callHandler((exchange, request) -> tool.call(request.arguments()))
        .build();
  }

  /** stdio server with the given tools (used by Task 6 end-to-end). */
  public static McpSyncServer stdio(List<McpTool> tools) {
    return register(McpServer.sync(
            new io.modelcontextprotocol.sdk.server.transport.StdioServerTransportProvider(
                /* 校准点 2: jackson2 mapper */ io.modelcontextprotocol.json.McpJson.getMapper())),
        tools)
        .build();
  }

  private static McpServer.SyncSpecification register(McpServer.SyncSpecification builder,
      List<McpTool> tools) {
    builder.serverInfo("sql-mask", "0.1.0")
        .capabilities(io.modelcontextprotocol.sdk.spec.McpSchema.ServerCapabilities.builder()
            .tools(true)
            .build());
    for (McpTool tool : tools) {
      builder.addTool(specFor(tool));
    }
    return builder;
  }

  private McpServerFactory() {
  }
}
```

注意：上面 import/类型名是文档口径，编译失败时按「SDK 校准点」修正，只改调用点不改签名。`Schemas.parse` 返回 `Object` 时此处强转按实际调整。

- [ ] **Step 5: 跑测试转绿**

Run: `mvn -pl mask-mcp-server -am test -Dtest=MainTest`
Expected: PASS (4 tests)。enforcer 不报 Spring/picocli/mask-common 违规。

- [ ] **Step 6: Commit**

```bash
git add mask-mcp-server/pom.xml pom.xml mask-mcp-server/src
git commit -m "feat(mcp): mask-mcp-server 模块骨架——零 Spring 门禁与 SDK 装配面"
```

---

### Task 2: 错误契约——ApiError 同构 + 异常转译 + CallToolResult 构造

**Files:**
- Create: `mask-mcp-server/src/main/java/io/sqlmask/mcp/McpErrors.java`
- Test: `mask-mcp-server/src/test/java/io/sqlmask/mcp/McpErrorsTest.java`

**Interfaces:**
- Consumes: `McpTool.call` 约定（Task 1）
- Produces:
  - `record McpErrors.ApiError(String code, String message, List<String> details)`
  - `static ApiError McpErrors.of(Throwable t)`：`SqlMaskException` → `code.name()`；`IllegalArgumentException` → `"CONFIG_ERROR"`；其余 → `"INTERNAL"`
  - `static McpSchema.CallToolResult McpErrors.ok(String json)`（isError 缺省 false）
  - `static McpSchema.CallToolResult McpErrors.errorResult(ApiError e)`（isError true + text JSON [+ structuredContent，校准点 3]）
  - `static String McpErrors.json(ApiError e)`：`{"code":...,"message":...,"details":[...]}`，`details` 为 null 时输出 `[]`

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.mcp;

import io.modelcontextprotocol.sdk.mcp.McpSchema;
import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpErrorsTest {

  @Test
  void sqlMaskExceptionKeepsKernelCode() {
    McpErrors.ApiError e = McpErrors.of(
        new SqlMaskException(SqlMaskException.Code.REWRITE_ERROR, "boom"));
    assertEquals("REWRITE_ERROR", e.code());
    assertEquals("boom", e.message());
  }

  @Test
  void illegalArgumentBecomesConfigError() {
    McpErrors.ApiError e = McpErrors.of(new IllegalArgumentException("bad dialect"));
    assertEquals("CONFIG_ERROR", e.code());
    assertEquals("bad dialect", e.message());
  }

  @Test
  void anythingElseIsInternal() {
    assertEquals("INTERNAL", McpErrors.of(new RuntimeException("x")).code());
  }

  @Test
  void errorResultIsFlaggedAndCarriesJson() {
    McpSchema.CallToolResult r =
        McpErrors.errorResult(new McpErrors.ApiError("CONFIG_ERROR", "bad", null));
    assertTrue(r.isError());
    McpSchema.TextContent text = (McpSchema.TextContent) r.content().get(0);
    assertTrue(text.text().contains("\"code\":\"CONFIG_ERROR\""));
    assertTrue(text.text().contains("\"details\":[]"));
  }

  @Test
  void okResultIsNotError() {
    McpSchema.CallToolResult r = McpErrors.ok("{\"a\":1}");
    assertFalse(r.isError());
    McpSchema.TextContent text = (McpSchema.TextContent) r.content().get(0);
    assertEquals("{\"a\":1}", text.text());
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-mcp-server test -Dtest=McpErrorsTest`
Expected: COMPILATION ERROR（McpErrors 不存在）

- [ ] **Step 3: 实现**

```java
package io.sqlmask.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.sdk.mcp.McpSchema;
import io.sqlmask.error.SqlMaskException;

import java.util.List;

/** Kernel exceptions -> ApiError-shaped JSON -> MCP error results. */
public final class McpErrors {

  /** Same shape as mask-common ApiError (duplicated here: mask-common drags Spring in). */
  public record ApiError(String code, String message, List<String> details) {
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static ApiError of(Throwable t) {
    if (t instanceof SqlMaskException e) {
      return new ApiError(e.getCode().name(), e.getMessage(), List.of());
    }
    if (t instanceof IllegalArgumentException e) {
      return new ApiError("CONFIG_ERROR", e.getMessage(), List.of());
    }
    return new ApiError("INTERNAL", String.valueOf(t.getMessage()), List.of());
  }

  public static McpSchema.CallToolResult ok(String json) {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(json)))
        .build();
  }

  public static McpSchema.CallToolResult errorResult(ApiError e) {
    // 校准点 3：若 builder 有 structuredContent(Map) 则加
    // .structuredContent(MAPPER.convertValue(e, Map.class))，text 保留（双通道）。
    return McpSchema.CallToolResult.builder()
        .isError(true)
        .content(List.of(new McpSchema.TextContent(json(e))))
        .build();
  }

  public static String json(ApiError e) {
    try {
      return MAPPER.writeValueAsString(e);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      return "{\"code\":\"INTERNAL\",\"message\":\"unserializable error\",\"details\":[]}";
    }
  }

  private McpErrors() {
  }
}
```

（`json` 依赖 Jackson 对 record 的序列化——`details` 为 `List.of()` 时输出 `[]`，与测试一致；`errorResult` 的 text 断言因此可直接过。）

- [ ] **Step 4: 跑测试转绿**

Run: `mvn -pl mask-mcp-server test -Dtest=McpErrorsTest`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add mask-mcp-server/src
git commit -m "feat(mcp): 错误契约——内核错误码透传为 ApiError 同构 MCP 结果"
```

---

### Task 3: rewrite_sql tool

**Files:**
- Create: `mask-mcp-server/src/main/java/io/sqlmask/mcp/RewriteSqlTool.java`
- Create: `mask-mcp-server/src/test/java/io/sqlmask/mcp/Fixtures.java`（共享 fixture，后续任务也用）
- Test: `mask-mcp-server/src/test/java/io/sqlmask/mcp/RewriteSqlToolTest.java`

**Interfaces:**
- Consumes: `McpErrors`（Task 2）、内核 `RewriteEngine`/`Subject`（Global Constraints 事实表）
- Produces: `final class RewriteSqlTool implements McpTool`，`name()="rewrite_sql"`；结果 JSON 形状 `{"statements":[<StatementRewrite 序列化>],"rewrittenSql":"<join 结果>"}`；`Fixtures.METADATA / MASK_POLICIES / POLICIES / TRINO_YAML`（public static final String）

- [ ] **Step 1: 写共享 fixture**

`Fixtures.java`（照抄 `mask-engine` 的 `RewriteEnginePolicyTest` / `MultiDialectRewriteTest`，勿改内容）：

```java
package io.sqlmask.mcp;

/** Test fixtures copied verbatim from mask-engine tests (same schema the kernel accepts). */
public final class Fixtures {

  /** metadata 无内嵌策略（policies: {}），配合 Ranger 式 policyYaml 用。 */
  public static final String METADATA = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
              - {name: status, type: varchar}
      policies: {}
      """;

  public static final String MASK_POLICIES = """
      policies:
        - name: mask-phone
          resources:
            - {catalog: crm, schema: public, table: customer, column: phone}
          dataMaskItems:
            - {groups: ["*"], udf: mask_phone, arguments: [3, 4]}
      """;

  public static final String SQL = "SELECT phone FROM customer;";

  /** 内嵌 legacy 策略的 metadata，trino/pg/mysql 通用。 */
  public static final String TRINO_YAML = """
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
              - name: email
                type: varchar
      columns:
        - catalog: crm
          schema: public
          table: customer
          column: phone
          policy: phone_mask
        - catalog: crm
          schema: public
          table: customer
          column: email
          policy: email_mask
      policies:
        phone_mask:
          udf: mask_phone
          arguments: [3, 4]
        email_mask:
          udf: mask_email
          arguments: []
      """;

  private Fixtures() {
  }
}
```

- [ ] **Step 2: 写失败测试**

```java
package io.sqlmask.mcp;

import io.modelcontextprotocol.sdk.mcp.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewriteSqlToolTest {

  private final RewriteSqlTool tool = new RewriteSqlTool();

  private static Map<String, Object> args(String metadata, String policy, String sql,
      String dialect, String user) {
    Map<String, Object> m = new HashMap<>();
    m.put("metadataYaml", metadata);
    if (policy != null) {
      m.put("policyYaml", policy);
    }
    m.put("sql", sql);
    m.put("dialect", dialect);
    if (user != null) {
      m.put("user", user);
    }
    return m;
  }

  private static String text(McpSchema.CallToolResult r) {
    return ((McpSchema.TextContent) r.content().get(0)).text();
  }

  @Test
  void trinoRendersExpectedWrapper() {
    // 确切输出对齐 MultiDialectRewriteTest.trinoWrapperRendersPlainLowercaseIdentifiers
    McpSchema.CallToolResult r = tool.call(
        args(Fixtures.TRINO_YAML, null, "SELECT id, phone FROM customer", "trino", null));
    assertFalse(r.isError());
    String json = text(r);
    assertTrue(json.contains("\"masked\":true"), json);
    assertTrue(json.contains("mask_phone(r.phone, 3, 4)"), json);
    assertTrue(json.contains("\"rewrittenSql\":\"SELECT r.id, mask_phone(r.phone, 3, 4)"
        + " AS phone FROM ( SELECT id, phone FROM customer ) AS r"), json);
    assertTrue(json.contains("\"unchanged\":false"), json);
  }

  @Test
  void mysqlBacktickQuotingSurvivesMapping() {
    McpSchema.CallToolResult r = tool.call(
        args(Fixtures.TRINO_YAML, null, "SELECT id, phone FROM customer", "mysql", null));
    assertFalse(r.isError());
    assertTrue(text(r).contains("`mask_phone`"), text(r));
  }

  @Test
  void postgresqlAndSparkSqlAcceptLegacyYaml() {
    for (String dialect : new String[] {"postgresql", "sparksql"}) {
      McpSchema.CallToolResult r = tool.call(
          args(Fixtures.TRINO_YAML, null, "SELECT id, phone FROM customer", dialect, null));
      assertFalse(r.isError(), dialect);
      assertTrue(text(r).contains("mask_phone"), dialect);
    }
  }

  @Test
  void noPolicyStatementPassesThroughUnchanged() {
    McpSchema.CallToolResult r = tool.call(
        args(Fixtures.TRINO_YAML, null, "SELECT id FROM customer", "trino", null));
    assertFalse(r.isError());
    assertTrue(text(r).contains("\"masked\":false"), text(r));
    assertTrue(text(r).contains("\"unchanged\":true"), text(r));
  }

  @Test
  void policyYamlWithSubjectMasks() {
    // 对齐 RewriteEnginePolicyTest.namedUserGetsMaskAndRowFilter 的输入形状（仅 mask 策略）
    Map<String, Object> a = new HashMap<>();
    a.put("metadataYaml", Fixtures.METADATA);
    a.put("policyYaml", Fixtures.MASK_POLICIES);
    a.put("sql", "SELECT phone FROM customer");
    a.put("dialect", "postgresql");
    a.put("user", "alice");
    a.put("groups", java.util.List.of("analyst"));
    McpSchema.CallToolResult r = tool.call(a);
    assertFalse(r.isError());
    assertTrue(text(r).contains("mask_phone"), text(r));
  }

  @Test
  void multipleStatementsReturnOneEntryEach() {
    McpSchema.CallToolResult r = tool.call(args(Fixtures.TRINO_YAML, null,
        "SELECT id FROM customer; SELECT phone FROM customer", "trino", null));
    assertFalse(r.isError());
    assertTrue(text(r).contains("\"ordinal\":0"), text(r));
    assertTrue(text(r).contains("\"ordinal\":1"), text(r));
  }

  @Test
  void invalidDialectIsConfigError() {
    McpSchema.CallToolResult r = tool.call(
        args(Fixtures.TRINO_YAML, null, "SELECT 1", "oracle", null));
    assertTrue(r.isError());
    String json = text(r);
    assertTrue(json.contains("\"code\":"), json);
    assertFalse(json.contains("INTERNAL"), json);
  }

  @Test
  void brokenYamlKeepsKernelErrorCode() {
    McpSchema.CallToolResult r = tool.call(
        args("metadata:\n  tables: [", null, "SELECT 1", "trino", null));
    assertTrue(r.isError());
    assertTrue(text(r).contains("\"code\":\"CONFIG_ERROR\"")
        || text(r).contains("\"code\":\"PARSE_ERROR\""), text(r));
  }

  @Test
  void missingRequiredArgIsConfigError() {
    McpSchema.CallToolResult r = tool.call(Map.of("sql", "SELECT 1"));
    assertTrue(r.isError());
    assertTrue(text(r).contains("\"code\":\"CONFIG_ERROR\""), text(r));
  }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -pl mask-mcp-server test -Dtest=RewriteSqlToolTest`
Expected: COMPILATION ERROR（RewriteSqlTool 不存在）

- [ ] **Step 4: 实现**

```java
package io.sqlmask.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.sdk.mcp.McpSchema;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.rewrite.RewriteEngine;

import java.util.List;
import java.util.Map;

/** rewrite_sql: inline-YAML masking rewrite, same kernel call as /api/rewrite. */
public final class RewriteSqlTool implements McpTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "rewrite_sql";
  }

  @Override
  public String description() {
    return "Rewrite SQL statements so result columns are wrapped in masking UDFs "
        + "(and row filters injected), per the supplied metadata/policy YAML. "
        + "Input and output are the same dialect; nothing is executed. "
        + "Error codes come back verbatim from the kernel (e.g. CONFIG_ERROR, "
        + "PARSE_ERROR, REWRITE_ERROR, UNSUPPORTED_STATEMENT).";
  }

  @Override
  public String schemaJson() {
    return """
        {"type":"object","properties":{
          "metadataYaml":{"type":"string","description":"metadata YAML: tables, columns, legacy policies/rowFilter"},
          "policyYaml":{"type":"string","description":"Ranger-style policy YAML; when non-blank the metadata must not embed policies"},
          "sql":{"type":"string","description":"one or more semicolon-separated SQL statements"},
          "dialect":{"type":"string","enum":["postgresql","trino","mysql","hive","sparksql"],"description":"target dialect (see list_dialects)"},
          "user":{"type":"string","description":"query subject for policy matching; default anonymous"},
          "groups":{"type":"array","items":{"type":"string"},"description":"subject groups"}
        },"required":["metadataYaml","sql","dialect"]}""";
  }

  @Override
  public McpSchema.CallToolResult call(Map<String, Object> arguments) {
    try {
      String metadataYaml = requireString(arguments, "metadataYaml");
      String sql = requireString(arguments, "sql");
      String dialect = requireString(arguments, "dialect");
      String policyYaml = optionalString(arguments, "policyYaml");
      String user = optionalString(arguments, "user");
      @SuppressWarnings("unchecked")
      List<String> groups = (List<String>) arguments.get("groups");

      Subject subject = user == null && groups == null
          ? Subject.anonymous()
          : Subject.of(user == null ? "anonymous" : user, groups == null ? List.of() : groups);

      List<RewriteEngine.StatementRewrite> statements = new RewriteEngine()
          .rewrite(metadataYaml, policyYaml == null ? "" : policyYaml, sql, dialect, subject);
      return McpErrors.ok(MAPPER.writeValueAsString(Map.of(
          "statements", statements,
          "rewrittenSql", RewriteEngine.join(statements))));
    } catch (Exception e) {
      return McpErrors.errorResult(McpErrors.of(e));
    }
  }

  private static String requireString(Map<String, Object> args, String key) {
    Object v = args.get(key);
    if (!(v instanceof String s) || s.isBlank()) {
      throw new IllegalArgumentException(key + " is required");
    }
    return s;
  }

  private static String optionalString(Map<String, Object> args, String key) {
    Object v = args.get(key);
    return v instanceof String s && !s.isBlank() ? s : null;
  }
}
```

（`requireString` 抛 `IllegalArgumentException` → `CONFIG_ERROR`，与测试一致；`StatementRewrite` 带 Jackson 注解、`Map.of` 序列化保留其字段名。）

- [ ] **Step 5: 跑测试转绿**

Run: `mvn -pl mask-mcp-server test -Dtest=RewriteSqlToolTest`
Expected: PASS (9 tests)

- [ ] **Step 6: Commit**

```bash
git add mask-mcp-server/src
git commit -m "feat(mcp): rewrite_sql 工具——进程内改写与逐语句结果映射"
```

---

### Task 4: validate_config tool

**Files:**
- Create: `mask-mcp-server/src/main/java/io/sqlmask/mcp/ValidateConfigTool.java`
- Test: `mask-mcp-server/src/test/java/io/sqlmask/mcp/ValidateConfigToolTest.java`

**Interfaces:**
- Consumes: `McpErrors`、`Fixtures.METADATA/MASK_POLICIES`、`YamlConfigLoader.loadContent(yaml, sourceName, dialect)`、`PolicyYamlLoader.parse(yaml, sourceName)`
- Produces: `final class ValidateConfigTool implements McpTool`，`name()="validate_config"`；结果 JSON `{"valid":boolean,"errors":[{"source":"metadata"|"policy","code":...,"message":...}]}`；**部分失败仍 isError=false**（校验结果本身是正常返回，不是 tool 故障）

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.mcp;

import io.modelcontextprotocol.sdk.mcp.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidateConfigToolTest {

  private final ValidateConfigTool tool = new ValidateConfigTool();

  private static String text(McpSchema.CallToolResult r) {
    return ((McpSchema.TextContent) r.content().get(0)).text();
  }

  @Test
  void validMetadataAndPolicyPass() {
    McpSchema.CallToolResult r = tool.call(Map.of(
        "metadataYaml", Fixtures.METADATA,
        "policyYaml", Fixtures.MASK_POLICIES,
        "dialect", "postgresql"));
    assertFalse(r.isError());
    assertTrue(text(r).contains("\"valid\":true"), text(r));
    assertTrue(text(r).contains("\"errors\":[]"), text(r));
  }

  @Test
  void brokenMetadataReportsSource() {
    McpSchema.CallToolResult r = tool.call(Map.of(
        "metadataYaml", "metadata:\n  tables: [",
        "dialect", "trino"));
    assertFalse(r.isError());
    String json = text(r);
    assertTrue(json.contains("\"valid\":false"), json);
    assertTrue(json.contains("\"source\":\"metadata\""), json);
  }

  @Test
  void brokenPolicyReportsSource() {
    McpSchema.CallToolResult r = tool.call(Map.of(
        "policyYaml", "policies:\n  - name: [",
        "dialect", "postgresql"));
    assertFalse(r.isError());
    String json = text(r);
    assertTrue(json.contains("\"valid\":false"), json);
    assertTrue(json.contains("\"source\":\"policy\""), json);
  }

  @Test
  void neitherInputIsConfigError() {
    McpSchema.CallToolResult r = tool.call(Map.of("dialect", "postgresql"));
    assertTrue(r.isError());
    assertTrue(text(r).contains("\"code\":\"CONFIG_ERROR\""), text(r));
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-mcp-server test -Dtest=ValidateConfigToolTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现**

```java
package io.sqlmask.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.sdk.mcp.McpSchema;
import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.policy.store.PolicyYamlLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** validate_config: dry-parse metadata/policy YAML, report errors per source. */
public final class ValidateConfigTool implements McpTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "validate_config";
  }

  @Override
  public String description() {
    return "Validate metadata YAML and/or Ranger-style policy YAML without touching SQL. "
        + "Returns per-source errors; a false-valid result is a normal response, not a failure.";
  }

  @Override
  public String schemaJson() {
    return """
        {"type":"object","properties":{
          "metadataYaml":{"type":"string","description":"metadata YAML to validate (needs dialect)"},
          "policyYaml":{"type":"string","description":"Ranger-style policy YAML to validate"},
          "dialect":{"type":"string","enum":["postgresql","trino","mysql","hive","sparksql"],"description":"required when metadataYaml is present"}
        },"required":["dialect"]}""";
  }

  @Override
  public McpSchema.CallToolResult call(Map<String, Object> arguments) {
    try {
      String dialect = requireString(arguments, "dialect");
      String metadataYaml = optionalString(arguments, "metadataYaml");
      String policyYaml = optionalString(arguments, "policyYaml");
      if (metadataYaml == null && policyYaml == null) {
        throw new IllegalArgumentException("metadataYaml or policyYaml is required");
      }
      List<Map<String, Object>> errors = new ArrayList<>();
      if (metadataYaml != null) {
        try {
          new YamlConfigLoader().loadContent(metadataYaml, "metadata.yaml", dialect);
        } catch (Exception e) {
          errors.add(entry("metadata", e));
        }
      }
      if (policyYaml != null) {
        try {
          new PolicyYamlLoader().parse(policyYaml, "policies.yaml");
        } catch (Exception e) {
          errors.add(entry("policy", e));
        }
      }
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("valid", errors.isEmpty());
      out.put("errors", errors);
      return McpErrors.ok(MAPPER.writeValueAsString(out));
    } catch (Exception e) {
      return McpErrors.errorResult(McpErrors.of(e));
    }
  }

  private static Map<String, Object> entry(String source, Exception e) {
    String code = e instanceof io.sqlmask.error.SqlMaskException s
        ? s.getCode().name() : "CONFIG_ERROR";
    return Map.of("source", source, "code", code, "message", String.valueOf(e.getMessage()));
  }

  private static String requireString(Map<String, Object> args, String key) {
    Object v = args.get(key);
    if (!(v instanceof String s) || s.isBlank()) {
      throw new IllegalArgumentException(key + " is required");
    }
    return s;
  }

  private static String optionalString(Map<String, Object> args, String key) {
    Object v = args.get(key);
    return v instanceof String s && !s.isBlank() ? s : null;
  }
}
```

- [ ] **Step 4: 跑测试转绿**

Run: `mvn -pl mask-mcp-server test -Dtest=ValidateConfigToolTest`
Expected: PASS (4 tests)。若 `brokenPolicyReportsSource` 里 `policies:\n  - name: [` 恰好被 SnakeYAML 接受（不抛），把 fixture 改成 `"policies:\n  - resources: {table: x}\n"`（缺 name 必抛）并重跑——修正 fixture 而不是断言。

- [ ] **Step 5: Commit**

```bash
git add mask-mcp-server/src
git commit -m "feat(mcp): validate_config 工具——metadata/policy YAML 干跑校验"
```

---

### Task 5: list_dialects tool

**Files:**
- Create: `mask-mcp-server/src/main/java/io/sqlmask/mcp/ListDialectsTool.java`
- Test: `mask-mcp-server/src/test/java/io/sqlmask/mcp/ListDialectsToolTest.java`

**Interfaces:**
- Consumes: `DialectProfiles.names()`
- Produces: `final class ListDialectsTool implements McpTool`，`name()="list_dialects"`；结果 JSON `{"dialects":[{"name":"...","description":"..."}]}`（按 name 排序）

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.mcp;

import io.modelcontextprotocol.sdk.mcp.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListDialectsToolTest {

  @Test
  void listsAllFiveDialectsSorted() {
    McpSchema.CallToolResult r = new ListDialectsTool().call(Map.of());
    assertFalse(r.isError());
    String json = ((McpSchema.TextContent) r.content().get(0)).text();
    for (String d : new String[] {"hive", "mysql", "postgresql", "sparksql", "trino"}) {
      assertTrue(json.contains("\"name\":\"" + d + "\""), d + " missing in " + json);
    }
    assertTrue(json.indexOf("\"name\":\"hive\"") < json.indexOf("\"name\":\"trino\""), json);
    assertTrue(json.contains("\"description\":"), json);
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-mcp-server test -Dtest=ListDialectsToolTest`
Expected: COMPILATION ERROR

- [ ] **Step 3: 实现**

```java
package io.sqlmask.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.sdk.mcp.McpSchema;
import io.sqlmask.dialect.DialectProfiles;

import java.util.Comparator;
import java.util.Map;

/** list_dialects: registry-driven enumeration of supported dialects. */
public final class ListDialectsTool implements McpTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Map<String, String> DESCRIPTIONS = Map.of(
      "postgresql", "PostgreSQL（pg_catalog 采集，行过滤与脱敏 UDF 包装）",
      "trino", "Trino（catalog/schema 三段名，information_schema 采集）",
      "mysql", "MySQL（反引号标识符，unsigned 剥离告警）",
      "hive", "Hive（大小写不敏感标识符折叠）",
      "sparksql", "Spark SQL（Hive 兼容语法面）");

  @Override
  public String name() {
    return "list_dialects";
  }

  @Override
  public String description() {
    return "List the SQL dialects accepted by rewrite_sql/validate_config "
        + "(input and output are always the same dialect).";
  }

  @Override
  public String schemaJson() {
    return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
  }

  @Override
  public McpSchema.CallToolResult call(Map<String, Object> arguments) {
    try {
      var dialects = DialectProfiles.names().stream()
          .sorted(Comparator.naturalOrder())
          .map(n -> Map.of("name", n, "description", DESCRIPTIONS.getOrDefault(n, "")))
          .toList();
      return McpErrors.ok(MAPPER.writeValueAsString(Map.of("dialects", dialects)));
    } catch (Exception e) {
      return McpErrors.errorResult(McpErrors.of(e));
    }
  }
}
```

- [ ] **Step 4: 跑测试转绿**

Run: `mvn -pl mask-mcp-server test -Dtest=ListDialectsToolTest`
Expected: PASS (1 test)。若 `DESCRIPTIONS` 的 key 与 `DialectProfiles.names()` 实际值有出入（如 hive 实际叫别的），以 names() 输出为准改 DESCRIPTIONS 的 key——测试断言的就是五方言真名。

- [ ] **Step 5: Commit**

```bash
git add mask-mcp-server/src
git commit -m "feat(mcp): list_dialects 工具——方言注册表枚举"
```

---

### Task 6: 装配 + 单 jar + stdio 端到端 + README（P1 完成线）

**Files:**
- Modify: `mask-mcp-server/src/main/java/io/sqlmask/mcp/Main.java`（main 方法接线）
- Modify: `mask-mcp-server/pom.xml`（shade plugin）
- Test: `mask-mcp-server/src/test/java/io/sqlmask/mcp/StdioEndToEndTest.java`
- Modify: `README.md`（新章节「MCP 接入」的 stdio 部分）
- Test: `mask-mcp-server/src/test/java/io/sqlmask/mcp/MainTest.java`（已存在，不动）

**Interfaces:**
- Consumes: `McpServerFactory.stdio(List<McpTool>)`、三个 tool 类（Task 3-5）
- Produces: 可运行的 `mask-mcp-server`（`java -cp ... io.sqlmask.mcp.Main` 与 shade 后单 jar 两种形态）；`Main.TOOLS`（`static List<McpTool> TOOLS = List.of(new RewriteSqlTool(), new ValidateConfigTool(), new ListDialectsTool())`）

- [ ] **Step 1: 写失败的端到端测试**

```java
package io.sqlmask.mcp;

import io.modelcontextprotocol.sdk.mcp.McpSchema;
import io.modelcontextprotocol.sdk.client.McpClient;
import io.modelcontextprotocol.sdk.client.McpSyncClient;
import io.modelcontextprotocol.sdk.client.transport.ServerParameters;
import io.modelcontextprotocol.sdk.client.transport.StdioClientTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.assertTrue;

/**
 * Spawns the real server as a subprocess (same classpath as the test JVM) and
 * drives it over stdio: initialize -> listTools -> callTool.
 */
class StdioEndToEndTest {

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  void fullRoundTripOverStdio() throws Exception {
    String cp = System.getProperty("java.class.path");
    try (McpSyncClient client = McpClient.sync(
            new StdioClientTransport(
                ServerParameters.builder("java")
                    .args("-cp", cp, "io.sqlmask.mcp.Main")
                    .build(),
                /* 校准点 2 同款 mapper */ io.modelcontextprotocol.json.McpJson.getMapper()))
        .requestTimeout(java.time.Duration.ofSeconds(30))
        .build()) {
      client.initialize();

      var tools = client.listTools();
      assertEquals(3, tools.tools().size());

      Map<String, Object> args = new HashMap<>();
      args.put("metadataYaml", Fixtures.METADATA);
      args.put("policyYaml", Fixtures.MASK_POLICIES);
      args.put("sql", Fixtures.SQL);
      args.put("dialect", "postgresql");
      args.put("user", "alice");
      McpSchema.CallToolResult r = client.callTool(
          io.modelcontextprotocol.sdk.mcp.McpSchema.CallToolRequest.builder("rewrite_sql")
              .arguments(args)
              .build());
      assertFalse(r.isError());
      String json = ((McpSchema.TextContent) r.content().get(0)).text();
      assertTrue(json.contains("mask_phone"), json);
    }
  }
}
```

（若 `CallToolRequest.builder(...)`/`.arguments(Map)` 与 SDK 有出入，按「SDK 校准点」修正调用点；mapper 参数同 Task 1 校准。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-mcp-server test -Dtest=StdioEndToEndTest`
Expected: FAIL——子进程 main 抛 `UnsupportedOperationException("wired in Task 6")`，客户端 initialize 超时或报 IO 错误

- [ ] **Step 3: 接线 Main + 加 shade**

`Main.main` 替换为：

```java
  static final java.util.List<McpTool> TOOLS = java.util.List.of(
      new RewriteSqlTool(), new ValidateConfigTool(), new ListDialectsTool());

  public static void main(String[] args) throws Exception {
    Config cfg = parseArgs(args);
    if ("http".equals(cfg.transport())) {
      throw new UnsupportedOperationException("http transport lands in Task 7");
    }
    if (!"stdio".equals(cfg.transport())) {
      throw new IllegalArgumentException("unknown transport: " + cfg.transport());
    }
    McpSyncServer server = McpServerFactory.stdio(TOOLS);
    Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    // stdio server runs on daemon threads; hold the main thread until the
    // transport dies (client closes stdin) so the JVM does not exit early
    Thread.currentThread().join();
  }
```

（import `io.modelcontextprotocol.sdk.server.McpSyncServer`。若 `server.close()` 抛 checked 异常，包成 `() -> { try { server.close(); } catch (Exception ignored) { } }`。）

`mask-mcp-server/pom.xml` 的 `<plugins>` 加：

```xml
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.1</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>io.sqlmask.mcp.Main</mainClass>
                </transformer>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
              </transformers>
              <filters>
                <filter>
                  <artifact>*:*</artifact>
                  <excludes>
                    <exclude>META-INF/*.SF</exclude>
                    <exclude>META-INF/*.DSA</exclude>
                    <exclude>META-INF/*.RSA</exclude>
                  </excludes>
                </filter>
              </filters>
            </configuration>
          </execution>
        </executions>
      </plugin>
```

- [ ] **Step 4: 跑测试转绿（含全模块回归）**

Run: `mvn -pl mask-mcp-server -am test`
Expected: PASS（含 MainTest/McpErrorsTest/RewriteSqlToolTest/ValidateConfigToolTest/ListDialectsToolTest/StdioEndToEndTest 全部）

Run: `mvn -pl mask-mcp-server package -DskipTests`
Expected: BUILD SUCCESS，`mask-mcp-server/target/` 出现 shaded jar（带 Main-Class）

冒烟（手动）：

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}' \
  | java -jar mask-mcp-server/target/mask-mcp-server-0.1.0-SNAPSHOT.jar | head -1
```

Expected: stdout 一行 initialize 响应 JSON（serverInfo.name = "sql-mask"）

- [ ] **Step 5: README 新章节**

`README.md` 在「服务形态」表格之后插入新章节（保留既有结构，仅新增）：

```markdown
## MCP 接入（mask-mcp-server）

mask-mcp-server 把改写内核以 MCP tools 形式提供给 AI 客户端（Claude Desktop、
ZCode 等），进程内直调 RewriteEngine，不经 HTTP：

| Tool | 说明 |
|---|---|
| `rewrite_sql` | 内联 YAML 改写（对齐 `/api/rewrite`），逐语句返回脱敏结果 |
| `validate_config` | metadata / policy YAML 干跑校验，逐源报告错误 |
| `list_dialects` | 枚举五方言（postgresql/trino/mysql/hive/sparksql） |

stdio 模式（本地客户端）构建后直接配置：

```json
{
  "mcpServers": {
    "sql-mask": {
      "command": "java",
      "args": ["-jar", "/path/to/mask-mcp-server-0.1.0-SNAPSHOT.jar"]
    }
  }
}
```

错误契约与 REST 面一致：`{"code","message","details"}`，内核错误码
（`CONFIG_ERROR`/`PARSE_ERROR`/`REWRITE_ERROR`/`UNSUPPORTED_STATEMENT` 等）原样透传。
```

- [ ] **Step 6: Commit**

```bash
git add mask-mcp-server/src mask-mcp-server/pom.xml README.md
git commit -m "feat(mcp): stdio 装配与端到端验收——P1 改写面交付"
```

---

### Task 7: Streamable HTTP 传输（Jetty）

**Files:**
- Create: `mask-mcp-server/src/main/java/io/sqlmask/mcp/JettyHttpServer.java`
- Modify: `mask-mcp-server/pom.xml`（Jetty 依赖，compile scope）
- Modify: `mask-mcp-server/src/main/java/io/sqlmask/mcp/McpServerFactory.java`（新增 transport-agnostic 注册方法）
- Modify: `mask-mcp-server/src/main/java/io/sqlmask/mcp/Main.java`（http 分支）
- Test: `mask-mcp-server/src/test/java/io/sqlmask/mcp/HttpEndToEndTest.java`

**Interfaces:**
- Consumes: `Main.TOOLS`、`McpServerFactory` 的 SDK 装配
- Produces:
  - `static void JettyHttpServer.run(int port, List<McpTool> tools)`（阻塞直至进程终止；测试用 `JettyHttpServer.start(int port, List<McpTool> tools)` 返回 `AutoCloseable`——`start` 是 `run` 的非阻塞内核）
  - `McpServerFactory.build(McpStreamableServerTransportProvider provider, List<McpTool> tools)` → `McpSyncServer`

- [ ] **Step 1: pom 加 Jetty 依赖**

`<dependencies>` 加（版本写死，enforcer 不受影响——Jetty 无 Spring）：

```xml
    <dependency>
      <groupId>org.eclipse.jetty.ee10</groupId>
      <artifactId>jetty-ee10-servlet</artifactId>
      <version>12.0.16</version>
    </dependency>
```

- [ ] **Step 2: 写失败测试**

```java
package io.sqlmask.mcp;

import io.modelcontextprotocol.sdk.mcp.McpSchema;
import io.modelcontextprotocol.sdk.client.McpClient;
import io.modelcontextprotocol.sdk.client.McpSyncClient;
import io.modelcontextprotocol.sdk.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpEndToEndTest {

  private AutoCloseable server;

  @AfterEach
  void stop() throws Exception {
    if (server != null) {
      server.close();
    }
  }

  private static int freePort() throws Exception {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void fullRoundTripOverStreamableHttp() throws Exception {
    int port = freePort();
    server = JettyHttpServer.start(port, Main.TOOLS);

    try (McpSyncClient client = McpClient.sync(
            HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .build())
        .requestTimeout(java.time.Duration.ofSeconds(20))
        .build()) {
      client.initialize();
      assertEquals(3, client.listTools().tools().size());

      Map<String, Object> args = new HashMap<>();
      args.put("metadataYaml", Fixtures.METADATA);
      args.put("policyYaml", Fixtures.MASK_POLICIES);
      args.put("sql", Fixtures.SQL);
      args.put("dialect", "postgresql");
      args.put("user", "alice");
      McpSchema.CallToolResult r = client.callTool(
          McpSchema.CallToolRequest.builder("rewrite_sql").arguments(args).build());
      assertFalse(r.isError());
      assertTrue(((McpSchema.TextContent) r.content().get(0)).text().contains("mask_phone"));
    }
  }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -pl mask-mcp-server test -Dtest=HttpEndToEndTest`
Expected: COMPILATION ERROR（JettyHttpServer 不存在）

- [ ] **Step 4: 实现**

`McpServerFactory` 加方法（并让 `stdio(List<McpTool>)` 与 `build(...)` 共用 `register`）：

```java
  /** Streamable-HTTP server wired to a servlet transport provider (Task 7). */
  public static McpSyncServer build(
      io.modelcontextprotocol.sdk.server.transport.McpStreamableServerTransportProvider provider,
      List<McpTool> tools) {
    return register(McpServer.sync(provider), tools).build();
  }
```

`JettyHttpServer.java`：

```java
package io.sqlmask.mcp;

import io.modelcontextprotocol.sdk.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;

import java.util.EnumSet;
import java.util.List;

/** Streamable HTTP facade on embedded Jetty (zero Spring, jakarta.servlet). */
public final class JettyHttpServer {

  /** Non-blocking core for tests: returns a handle that stops server + transport. */
  public static AutoCloseable start(int port, List<McpTool> tools) throws Exception {
    HttpServletStreamableServerTransportProvider transport =
        HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(/* 校准点 2 同款 mapper */ io.modelcontextprotocol.json.McpJson.getMapper())
            .mcpEndpoint("/mcp")
            .build();
    var mcpServer = McpServerFactory.build(transport, tools);

    ServletContextHandler ctx = new ServletContextHandler();
    ctx.setContextPath("/");
    ctx.addServlet(new ServletHolder(transport), "/mcp");

    Server jetty = new Server(port);
    jetty.setHandler(ctx);
    jetty.start();
    return () -> {
      try {
        jetty.stop();
      } finally {
        mcpServer.close();
      }
    };
  }

  /** Blocking entry used by Main --transport http. */
  public static void run(int port, List<McpTool> tools) throws Exception {
    start(port, tools);
    Thread.currentThread().join();
  }

  private JettyHttpServer() {
  }
}
```

`Main.main` 的 http 分支替换 `UnsupportedOperationException`：

```java
    if ("http".equals(cfg.transport())) {
      JettyHttpServer.run(cfg.port(), TOOLS);
      return;
    }
```

（`HttpServletStreamableServerTransportProvider` 在 mcp-core 的 servlet transport 包；若类名/包名不同——例如在独立 artifact——按「SDK 校准点」修正 import。）

- [ ] **Step 5: 跑测试转绿**

Run: `mvn -pl mask-mcp-server test`
Expected: PASS（全部既有测试 + HttpEndToEndTest）

- [ ] **Step 6: Commit**

```bash
git add mask-mcp-server/src mask-mcp-server/pom.xml
git commit -m "feat(mcp): Streamable HTTP 传输——内嵌 Jetty 与 /mcp 端点"
```

---

### Task 8: ApiKey 鉴权

**Files:**
- Create: `mask-mcp-server/src/main/java/io/sqlmask/mcp/ApiKeyFilter.java`
- Modify: `mask-mcp-server/src/main/java/io/sqlmask/mcp/JettyHttpServer.java`（挂 filter，key 从环境变量读）
- Modify: `mask-mcp-server/src/main/java/io/sqlmask/mcp/Main.java`（读 `MASK_MCP_API_KEY` 传入）
- Test: `mask-mcp-server/src/test/java/io/sqlmask/mcp/ApiKeyFilterTest.java`

**Interfaces:**
- Consumes: `JettyHttpServer.start(int, List<McpTool>)`（Task 7；本任务把签名改为 `start(int port, List<McpTool> tools, String apiKey)`——不保留旧签名，Task 7 的测试与 `run` 同步改为三参，无死代码）
- Produces: `final class ApiKeyFilter implements jakarta.servlet.Filter`；语义：**未配置 key（null/blank）→ 一律 401**；配置了 → 请求头 `X-Api-Key` 必须精确相等，否则 401；401 响应体 = `McpErrors.json(new ApiError("UNAUTHORIZED", "...", List.of()))`，Content-Type `application/json`

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.mcp;

import io.modelcontextprotocol.sdk.client.McpClient;
import io.modelcontextprotocol.sdk.client.McpSyncClient;
import io.modelcontextprotocol.sdk.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Auth three-state over real HTTP: no key configured / wrong key / right key. */
class ApiKeyFilterTest {

  private AutoCloseable server;

  @AfterEach
  void stop() throws Exception {
    if (server != null) {
      server.close();
    }
  }

  private static int freePort() throws Exception {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void missingConfiguredKeyRejectsEverything() throws Exception {
    int port = freePort();
    server = JettyHttpServer.start(port, Main.TOOLS, null); // 未配置 → 默认拒绝
    org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
        () -> connectExpectingFailure(port, null));
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void wrongKeyIsRejectedAndRightKeyPasses() throws Exception {
    int port = freePort();
    server = JettyHttpServer.start(port, Main.TOOLS, "secret-1");
    org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
        () -> connectExpectingFailure(port, "secret-2"));
    try (McpSyncClient ok = McpClient.sync(
            HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .addHeaderInterceptor(h -> h.put("X-Api-Key", "secret-1"))
                .build())
        .requestTimeout(java.time.Duration.ofSeconds(20))
        .build()) {
      ok.initialize();
      assertTrue(ok.listTools().tools().size() == 3);
    }
  }

  /** No/incorrect key must fail at HTTP level (401), before MCP initialize. */
  private static void connectExpectingFailure(int port, String key) throws Exception {
    var builder = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
        .endpoint("/mcp");
    if (key != null) {
      builder.addHeaderInterceptor(h -> h.put("X-Api-Key", key));
    }
    try (McpSyncClient c = McpClient.sync(builder.build())
        .requestTimeout(java.time.Duration.ofSeconds(10))
        .build()) {
      c.initialize(); // must throw: 401 on the MCP endpoint
    }
  }
}
```

（`addHeaderInterceptor` 的真实方法名以 SDK `HttpClientStreamableHttpTransport.builder` 为准——常见为 `addHeaderInterceptor`/`headersConsumer`/`customizeRequest`；找不到就换成 JDK `HttpClient` 直接 POST `/mcp` 断言 401 + JSON 错误体，连接成功路径保留 SDK client。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-mcp-server test -Dtest=ApiKeyFilterTest`
Expected: COMPILATION ERROR（`JettyHttpServer.start(int,List,String)` 不存在）

- [ ] **Step 3: 实现**

`ApiKeyFilter.java`：

```java
package io.sqlmask.mcp;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * X-Api-Key gate for the HTTP transport. Same principle as the admin cache
 * refresh endpoint: when no key is configured, every request is rejected.
 */
public final class ApiKeyFilter implements Filter {

  private final String expectedKey;

  public ApiKeyFilter(String expectedKey) {
    this.expectedKey = expectedKey;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest req = (HttpServletRequest) request;
    HttpServletResponse res = (HttpServletResponse) response;
    if (expectedKey == null || expectedKey.isBlank()
        || !expectedKey.equals(req.getHeader("X-Api-Key"))) {
      res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      res.setContentType("application/json");
      res.getWriter().write(McpErrors.json(
          new McpErrors.ApiError("UNAUTHORIZED", "missing or invalid X-Api-Key", java.util.List.of())));
      return;
    }
    chain.doFilter(request, response);
  }
}
```

`JettyHttpServer`：`start` 加 `String apiKey` 参数，filter **无条件挂载**（`apiKey == null` 表示「未配置 → 拒绝一切」，全拒绝逻辑在 `ApiKeyFilter` 内部，见其实现）：

```java
    ctx.addFilter(
        new org.eclipse.jetty.ee10.servlet.FilterHolder(new ApiKeyFilter(apiKey)),
        "/mcp", EnumSet.of(DispatcherType.REQUEST));
```

**同时把 Task 7 的 `HttpEndToEndTest` 改为三参** `start(port, Main.TOOLS, "e2e-key")` 并给 client 加 `X-Api-Key: e2e-key` header 拦截器（与本任务 `wrongKeyIsRejectedAndRightKeyPasses` 里成功路径同款写法）——这是语义修正而非返工：无配置默认拒绝是产品行为（spec 安全边界），无 key 的端到端测试本来就应期待 401。

`Main.main` http 分支改为：

```java
    if ("http".equals(cfg.transport())) {
      String apiKey = System.getenv("MASK_MCP_API_KEY");
      JettyHttpServer.run(cfg.port(), TOOLS, apiKey);
      return;
    }
```

`JettyHttpServer.run` 同步加 `String apiKey` 参数。

- [ ] **Step 4: 跑测试转绿（全模块）**

Run: `mvn -pl mask-mcp-server test`
Expected: PASS（含修正后的 HttpEndToEndTest 与新 ApiKeyFilterTest 3 态）

- [ ] **Step 5: Commit**

```bash
git add mask-mcp-server/src
git commit -m "feat(mcp): HTTP 面 X-Api-Key 门禁——未配置默认拒绝"
```

---

### Task 9: 部署物 + README 补全 + 全量验证（P2 完成线）

**Files:**
- Create: `docker/mcp.Dockerfile`
- Create: `docker-compose.mcp.yml`
- Modify: `README.md`（「MCP 接入」章节补 HTTP 部分）
- Modify: `mask-mcp-server/pom.xml`（无改动预期；若 shade 未含 jetty 则验证）

**Interfaces:**
- Consumes: Task 6-8 的全部产出
- Produces: 可部署的 HTTP 模式交付物与文档

- [ ] **Step 1: docker/mcp.Dockerfile**

```dockerfile
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY mask-mcp-server/target/mask-mcp-server-*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--transport", "http", "--port", "8084"]
```

- [ ] **Step 2: docker-compose.mcp.yml**

```yaml
# mask-mcp-server（MCP Streamable HTTP 面）：改写内核的 MCP tools 接入。
#   docker compose -f docker-compose.mcp.yml up
# 鉴权：MASK_MCP_API_KEY 未配置时服务默认拒绝所有请求（与 admin 端点同原则）。
services:
  mask-mcp:
    build:
      context: .
      dockerfile: docker/mcp.Dockerfile
    environment:
      MASK_MCP_API_KEY: ${MASK_MCP_API_KEY:?set MASK_MCP_API_KEY before up}
    ports:
      - "8084:8084"
```

- [ ] **Step 3: README「MCP 接入」章节补 HTTP 段**

在 Task 6 写入的章节末尾追加：

```markdown
Streamable HTTP 模式（团队共享）：

```bash
mvn -pl mask-mcp-server -am package -DskipTests
MASK_MCP_API_KEY=local-dev-mcp-key \
  java -jar mask-mcp-server/target/mask-mcp-server-0.1.0-SNAPSHOT.jar \
  --transport http --port 8084
```

客户端接入 URL 为 `http://<host>:8084/mcp`，请求头带 `X-Api-Key`。
**未配置 `MASK_MCP_API_KEY` 时服务拒绝一切请求**（默认 fail-closed，与
`/admin/cache/refresh` 同原则）。Docker 部署见 `docker-compose.mcp.yml`。

| 环境变量 | 作用 |
|---|---|
| `MASK_MCP_API_KEY` | HTTP 模式鉴权（必配，否则全拒绝） |

stdio 模式无鉴权面（本地进程，信任桌面用户），无环境变量要求。
```

- [ ] **Step 4: 全量验证**

Run: `mvn -pl mask-mcp-server -am verify`
Expected: BUILD SUCCESS（单测 + 端到端 + enforcer 全绿）

Run: `mvn test -pl mask-engine,mask-mcp-server`
Expected: PASS（确认未波及内核）

手动冒烟（可选）：

```bash
MASK_MCP_API_KEY=k1 java -jar mask-mcp-server/target/mask-mcp-server-0.1.0-SNAPSHOT.jar --transport http --port 8084 &
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://127.0.0.1:8084/mcp \
  -H "Content-Type: application/json" -H "X-Api-Key: wrong" -d '{}'
```

Expected: `401`

- [ ] **Step 5: Commit**

```bash
git add docker/mcp.Dockerfile docker-compose.mcp.yml README.md
git commit -m "feat(mcp): HTTP 部署物与接入文档——P2 交付"
```

---

## 不在本计划（spec P3，另立计划）

- `PolicyServiceConfigSource` 下沉 mask-engine + `rewrite_instance`
- `run_masked_query`（mask-query 联调 + mock）
- MCP 面的 Prometheus 指标（对齐 mask-common 指标签）
