# 策略微服务（Ranger 骨架 + 配置仓库型 API）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把策略部分从改写服务拆为独立策略微服务（Ranger 三层骨架：EngineDef/实例/策略），提供管理面 CRUD 与数据面「已编译生效配置」API，改写引擎经 `PolicyConfigProvider`（版本轮询 + 缓存）接入，实现运行时解耦。

**Architecture:** 同仓转 Maven 多模块（`mask-core` 现有全部代码 / `mask-policy` 新策略服务）。策略服务编译产物即 core 的 `MaskingConfig` 等价 JSON（选择器展开、row_filter 回填表声明、禁用剔除），改写管线零改动。core 持有 `ConfigSource` 抽象（内联 YAML / 策略服务 HTTP 拉取两个实现）与版本缓存；`mask-policy → mask-core` 单向依赖，core 对策略服务零依赖。

**Tech Stack:** Java 17、Spring Boot 3.3（两个独立 fat jar）、PostgreSQL（策略存储，`spring-boot-starter-jdbc` + JdbcTemplate）、Jackson（编译产物 DTO 序列化）、JUnit 5、JDK 内置 `com.sun.net.httpserver`（客户端桩测试）。

**Spec:** `docs/superpowers/specs/2026-09-06-policy-service-design.md`（计划与 spec 一起阅读；接口形状以本计划为准）

## Global Constraints

- 前置条件：`feature/policy` 分支上多方言计划（`2026-09-06-multi-dialect.md` 全部 11 任务）已提交、工作区干净后再开始本计划。
- Java 17；既有依赖版本一律不变（Calcite 1.42.0、snakeyaml 2.2、Boot 3.3.5、trino-parser 446 test 作用域）；mask-core **不得新增任何依赖**；mask-policy 新增依赖仅限 `mask-core`、`spring-boot-starter-web`、`spring-boot-starter-test`、`spring-boot-starter-jdbc`、`org.postgresql:postgresql`。
- 依赖方向唯一合法：`mask-policy → mask-core`。core 中任何类不得 import `io.sqlmask.policyserver.*`。
- mask-core 全部既有测试（含 TPC-DS golden）保持通过；golden 文件 byte 级不变。
- 每个任务结束 `mvn test`（仓库根目录）必须全绿才能 commit；提交信息中文 + conventional commits。
- 错误码新增两个：`POLICY_SERVICE_UNAVAILABLE`、`POLICY_INSTANCE_NOT_FOUND`（加入 `SqlMaskException.Code`）。改写侧语义：策略服务不可达且无缓存 → `POLICY_SERVICE_UNAVAILABLE`，**绝不 fail-open**（不降级为原样输出）。
- 安全语义：`isEnabled=false` 的 datamask 策略编译剔除 = 对应列不脱敏直通（管理员有意行为，文档写死）；改写引擎内既有校验全部保留（第二道防线）。
- 实例名与策略名必须匹配 `[A-Za-z0-9_.-]+`（出现在 URL 路径中，禁止空白与 `/`）。
- 表/列/策略名归一化比较统一用 core 的 `ColumnKey.normalize`（折叠小写），与现有 YAML 语义一致。

---

### Task 1: Maven 多模块拆分（零行为变化）

**Files:**
- Modify: `pom.xml`（改为 parent，packaging=pom）
- Create: `mask-core/pom.xml`
- Create: `mask-policy/pom.xml`
- Move: `src/` → `mask-core/src/`（main + test + resources 全部）
- Move: `tpcds/` → `mask-core/tpcds/`（`GoldenOutputTest` 用相对路径 `tpcds/metadata.yaml` 访问，surefire 工作目录 = 模块目录，必须随迁）

**Interfaces:**
- Produces: 可从仓库根 `mvn test` / `mvn package` 构建；`mask-core/target/sql-mask.jar` 产物不变；`mask-policy` 模块存在但暂无代码。

- [ ] **Step 1: 移动源码与测试数据**

```bash
cd /c/Users/yhh/orca/mask
mkdir mask-core mask-policy
git mv src mask-core/src
git mv tpcds mask-core/tpcds
```

- [ ] **Step 2: 改写根 pom 为 parent**

`pom.xml` 全文替换为：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>io.sqlmask</groupId>
  <artifactId>sql-mask-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <name>sql-mask-parent</name>

  <modules>
    <module>mask-core</module>
    <module>mask-policy</module>
  </modules>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>17</maven.compiler.release>

    <calcite.version>1.42.0</calcite.version>
    <snakeyaml.version>2.2</snakeyaml.version>
    <picocli.version>4.7.7</picocli.version>
    <junit.version>5.10.2</junit.version>
    <spring-boot.version>3.3.5</spring-boot.version>

    <surefire.version>3.2.5</surefire.version>
    <jar.plugin.version>3.3.0</jar.plugin.version>
    <shade.version>3.5.1</shade.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

- [ ] **Step 3: 创建 mask-core/pom.xml（继承原 jar pom 全部内容）**

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

  <artifactId>mask-core</artifactId>
  <packaging>jar</packaging>

  <name>mask-core</name>
  <description>SQL masking rewrite engine: parse, validate, lineage, rewrite, render; CLI and HTTP service.</description>

  <dependencies>
    <dependency>
      <groupId>org.apache.calcite</groupId>
      <artifactId>calcite-core</artifactId>
      <version>${calcite.version}</version>
    </dependency>
    <dependency>
      <groupId>org.apache.calcite</groupId>
      <artifactId>calcite-babel</artifactId>
      <version>${calcite.version}</version>
    </dependency>
    <dependency>
      <groupId>org.yaml</groupId>
      <artifactId>snakeyaml</artifactId>
      <version>${snakeyaml.version}</version>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
    </dependency>
    <dependency>
      <groupId>info.picocli</groupId>
      <artifactId>picocli</artifactId>
      <version>${picocli.version}</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
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
      <groupId>io.trino</groupId>
      <artifactId>trino-parser</artifactId>
      <version>446</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <finalName>sql-mask</finalName>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>${surefire.version}</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        <version>${jar.plugin.version}</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>${shade.version}</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <filters>
                <filter>
                  <artifact>*:*</artifact>
                  <excludes>
                    <exclude>module-info.class</exclude>
                    <exclude>META-INF/*.SF</exclude>
                    <exclude>META-INF/*.DSA</exclude>
                    <exclude>META-INF/*.RSA</exclude>
                  </excludes>
                </filter>
              </filters>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>io.sqlmask.server.SqlMaskServiceApplication</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 4: 创建 mask-policy/pom.xml（最小骨架，shade 到 Task 8 再加）**

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

  <artifactId>mask-policy</artifactId>
  <packaging>jar</packaging>

  <name>mask-policy</name>
  <description>Masking policy microservice: Ranger-style policy store, compiler and REST API.</description>

  <dependencies>
    <dependency>
      <groupId>io.sqlmask</groupId>
      <artifactId>mask-core</artifactId>
      <version>0.1.0-SNAPSHOT</version>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
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

- [ ] **Step 5: 验证构建与全量测试**

Run: `mvn test`（根目录）
Expected: 全部既有测试 PASS（含 TPC-DS golden——`tpcds/` 已随模块迁移，相对路径恢复成立）。

Run: `mvn package -DskipTests && ls mask-core/target/sql-mask.jar`
Expected: jar 生成。

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "build: 拆分 Maven 多模块（mask-core + mask-policy 骨架）"
```

---

### Task 2: core 编译契约 DTO + ConfigSource 抽象 + 引擎重载

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/config/source/EffectiveConfigResponse.java`
- Create: `mask-core/src/main/java/io/sqlmask/config/source/ConfigSource.java`
- Create: `mask-core/src/main/java/io/sqlmask/config/source/InlineYamlConfigSource.java`
- Create: `mask-core/src/main/java/io/sqlmask/config/source/EffectiveConfigAssembler.java`
- Modify: `mask-core/src/main/java/io/sqlmask/policy/PolicyRegistry.java`（新增静态工厂 `of(MaskingConfig)`）
- Modify: `mask-core/src/main/java/io/sqlmask/config/YamlConfigLoader.java`（私有 `buildRegistry` 改为委托 `PolicyRegistry.of`）
- Modify: `mask-core/src/main/java/io/sqlmask/rewrite/RewriteEngine.java`（新增 `rewrite(LoadedConfig, String, String)` 重载，原方法委托）
- Test: `mask-core/src/test/java/io/sqlmask/config/source/EffectiveConfigAssemblerTest.java`

**Interfaces:**
- Consumes: `DialectProfiles.byName(String).typeResolver()`、`TypeResolver.parseColumn(String,String)`、`TableMetadata.Column`（含 declaration 原文）、`LoadedConfig(MaskingConfig, PolicyRegistry)`。
- Produces（Task 3/4/6/8 依赖，名字必须一致）:
  - `EffectiveConfigResponse(String instance, String dialect, long configVersion, PolicySummary policySummary, ConfigPayload config)` 及嵌套 record：`PolicySummary(int enabled, int disabled)`、`ConfigPayload(MetadataPayload metadata, List<ColumnBinding> columns, Map<String, UdfDefinition> policies)`、`MetadataPayload(List<TablePayload> tables)`、`TablePayload(String catalog, String schema, String name, String rowFilter, List<ColumnPayload> columns)`、`ColumnPayload(String name, String type)`、`ColumnBinding(String catalog, String schema, String table, String column, String policy)`、`UdfDefinition(String udf, List<Object> arguments)`。
  - `ConfigSource { ResolvedConfig load(); record ResolvedConfig(LoadedConfig config, String dialect, long configVersion) }`（`ResolvedConfig` 提供两参便捷构造，configVersion=0）。
  - `InlineYamlConfigSource(String yaml, String dialect)`。
  - `EffectiveConfigAssembler().assemble(EffectiveConfigResponse) -> LoadedConfig`。
  - `PolicyRegistry.of(MaskingConfig) -> PolicyRegistry`。
  - `RewriteEngine.rewrite(LoadedConfig loaded, String sqlText, String dialectName)`。

- [ ] **Step 1: 写失败测试（Assembler + 引擎重载等价性）**

```java
package io.sqlmask.config.source;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.rewrite.RewriteEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectiveConfigAssemblerTest {

  private EffectiveConfigResponse.ResponseBuilder() {}

  private EffectiveConfigResponse sample() {
    return new EffectiveConfigResponse("pg_prod", "postgresql", 42,
        new EffectiveConfigResponse.PolicySummary(1, 0),
        new EffectiveConfigResponse.ConfigPayload(
            new EffectiveConfigResponse.MetadataPayload(List.of(
                new EffectiveConfigResponse.TablePayload("crm", "public", "customer", null,
                    List.of(new EffectiveConfigResponse.ColumnPayload("phone", "varchar"),
                        new EffectiveConfigResponse.ColumnPayload("amount", "decimal(10,2)"))))),
            List.of(new EffectiveConfigResponse.ColumnBinding(
                "crm", "public", "customer", "phone", "phone_mask")),
            Map.of("phone_mask", new EffectiveConfigResponse.UdfDefinition("mask_phone", List.of(3, 4)))));
  }

  @Test
  void assemblesTypesBindingsAndRegistry() {
    LoadedConfig loaded = new EffectiveConfigAssembler().assemble(sample());
    assertEquals(1, loaded.tables().size());
    assertEquals("decimal(10,2)",
        loaded.tables().get(0).columns().get(1).typeDeclaration());
    assertTrue(loaded.policyRegistry().find(
        io.sqlmask.metadata.ColumnKey.of("CRM", "PUBLIC", "CUSTOMER", "PHONE")).isPresent());
  }

  @Test
  void rejectsUnknownPolicyBinding() {
    EffectiveConfigResponse bad = new EffectiveConfigResponse("pg_prod", "postgresql", 42,
        new EffectiveConfigResponse.PolicySummary(0, 0), sample().config());
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new EffectiveConfigAssembler().assemble(bad));
    // ColumnBinding 引用了不存在的策略
    assertEquals(io.sqlmask.error.SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  @Test
  void rejectsUnknownTypeWithDialectContext() {
    EffectiveConfigResponse bad = new EffectiveConfigResponse("trino_prod", "trino", 1,
        new EffectiveConfigResponse.PolicySummary(0, 0),
        new EffectiveConfigResponse.ConfigPayload(
            new EffectiveConfigResponse.MetadataPayload(List.of(
                new EffectiveConfigResponse.TablePayload("crm", "public", "t", null,
                    List.of(new EffectiveConfigResponse.ColumnPayload("a", "datetime"))))),
            List.of(), Map.of()));
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new EffectiveConfigAssembler().assemble(bad));
    assertTrue(e.getMessage().contains("trino"));
  }

  @Test
  void engineOverloadMatchesYamlPath() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - { name: phone, type: varchar }
        columns:
          - { catalog: crm, schema: public, table: customer, column: phone, policy: phone_mask }
        policies:
          phone_mask: { udf: mask_phone, arguments: [3, 4] }
        """;
    String sql = "SELECT phone FROM customer;";
    var fromYaml = new RewriteEngine().rewrite(yaml, sql, "postgresql");
    LoadedConfig loaded = new EffectiveConfigAssembler().assemble(sample());
    var fromLoaded = new RewriteEngine().rewrite(loaded, sql, "postgresql");
    assertEquals(fromYaml, fromLoaded);
  }
}
```

注意：`sample().config()` 复用会带上 `phone_mask` 绑定一致的 policies——`rejectsUnknownPolicyBinding` 若直接复用 sample 则不会失败；改成把 binding 换成 `"no_such_policy"`。上面测试代码在该用例中修正为构造一个 binding 指向不存在策略的 ConfigPayload（实现者按语义修正即可，断言 `CONFIG_ERROR` 不变）。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl mask-core test -Dtest=EffectiveConfigAssemblerTest`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现 DTO、ConfigSource、Assembler、PolicyRegistry.of、引擎重载**

`EffectiveConfigResponse.java`：

```java
package io.sqlmask.config.source;

import java.util.List;
import java.util.Map;

/**
 * Wire contract of the policy service data plane: the compiled effective
 * configuration, structurally equivalent to the YAML the rewrite engine
 * already consumes. Shared by mask-core (consumer) and mask-policy (producer).
 */
public record EffectiveConfigResponse(String instance, String dialect, long configVersion,
    PolicySummary policySummary, ConfigPayload config) {

  public record PolicySummary(int enabled, int disabled) {
  }

  public record ConfigPayload(MetadataPayload metadata, List<ColumnBinding> columns,
      Map<String, UdfDefinition> policies) {
  }

  public record MetadataPayload(List<TablePayload> tables) {
  }

  public record TablePayload(String catalog, String schema, String name, String rowFilter,
      List<ColumnPayload> columns) {
  }

  public record ColumnPayload(String name, String type) {
  }

  public record ColumnBinding(String catalog, String schema, String table, String column,
      String policy) {
  }

  public record UdfDefinition(String udf, List<Object> arguments) {
  }
}
```

`ConfigSource.java`：

```java
package io.sqlmask.config.source;

import io.sqlmask.config.LoadedConfig;

/** Where a rewrite run gets its validated configuration from. */
public interface ConfigSource {

  ResolvedConfig load();

  record ResolvedConfig(LoadedConfig config, String dialect, long configVersion) {

    public ResolvedConfig(LoadedConfig config, String dialect) {
      this(config, dialect, 0);
    }
  }
}
```

`InlineYamlConfigSource.java`：

```java
package io.sqlmask.config.source;

import io.sqlmask.config.YamlConfigLoader;

/** The legacy path: YAML text carried with the request itself. */
public final class InlineYamlConfigSource implements ConfigSource {

  private final String yaml;
  private final String dialect;

  public InlineYamlConfigSource(String yaml, String dialect) {
    this.yaml = yaml;
    this.dialect = dialect;
  }

  @Override
  public ResolvedConfig load() {
    return new ResolvedConfig(
        new YamlConfigLoader().loadContent(yaml, "metadata.yaml", dialect), dialect);
  }
}
```

`EffectiveConfigAssembler.java`：

```java
package io.sqlmask.config.source;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.MaskingConfig;
import io.sqlmask.dialect.DialectProfiles;
import io.sqlmask.dialect.TypeResolver;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.policy.MaskingPolicy;
import io.sqlmask.policy.PolicyRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts the compiled effective-config payload into the validated in-memory
 * model, reusing the dialect type resolver and the same duplicate/unknown
 * checks as the YAML loader. Errors carry paths prefixed with the instance name.
 */
public final class EffectiveConfigAssembler {

  public LoadedConfig assemble(EffectiveConfigResponse response) {
    String source = "policy-instance '" + response.instance() + "'";
    TypeResolver typeResolver = DialectProfiles.byName(response.dialect()).typeResolver();

    List<TableMetadata> tables = new ArrayList<>();
    Set<String> seenTables = new LinkedHashSet<>();
    for (EffectiveConfigResponse.TablePayload tp : response.config().metadata().tables()) {
      String tableKey = key(tp.catalog(), tp.schema(), tp.name());
      if (!seenTables.add(tableKey)) {
        throw error(source + ": duplicate table '" + tableKey + "'");
      }
      List<TableMetadata.Column> columns = new ArrayList<>();
      Set<String> seenColumns = new LinkedHashSet<>();
      for (EffectiveConfigResponse.ColumnPayload cp : tp.columns()) {
        String normalizedName = ColumnKey.normalize(cp.name(), "column");
        if (!seenColumns.add(normalizedName)) {
          throw error(source + ": table '" + tableKey + "': duplicate column '" + cp.name() + "'");
        }
        try {
          columns.add(typeResolver.parseColumn(cp.name(), cp.type()));
        } catch (SqlMaskException | IllegalArgumentException e) {
          throw error(source + ": table '" + tableKey + "' column '" + cp.name()
              + "': " + e.getMessage());
        }
      }
      tables.add(new TableMetadata(tp.catalog(), tp.schema(), tp.name(), columns, tp.rowFilter()));
    }

    Map<String, MaskingPolicy> policies = new LinkedHashMap<>();
    for (Map.Entry<String, EffectiveConfigResponse.UdfDefinition> e
        : response.config().policies().entrySet()) {
      MaskingPolicy.validateArguments(e.getValue().arguments(), e.getKey(), source);
      policies.put(e.getKey(),
          new MaskingPolicy(e.getKey(), e.getValue().udf(), e.getValue().arguments()));
    }

    List<MaskingConfig.ColumnPolicyBinding> bindings = new ArrayList<>();
    Set<String> seenBindings = new LinkedHashSet<>();
    for (EffectiveConfigResponse.ColumnBinding b : response.config().columns()) {
      String policyName = b.policy();
      if (!policies.containsKey(policyName)) {
        throw error(source + ": column binding for '" + b.column() + "' references unknown policy '"
            + policyName + "' (declared policies: " + policies.keySet() + ")");
      }
      ColumnKey key = ColumnKey.of(b.catalog(), b.schema(), b.table(), b.column());
      if (!seenBindings.add(key.toString())) {
        throw error(source + ": duplicate policy binding for column '" + key + "'");
      }
      bindings.add(new MaskingConfig.ColumnPolicyBinding(key, policyName));
    }

    MaskingConfig config = new MaskingConfig(tables, bindings, policies);
    return new LoadedConfig(config, PolicyRegistry.of(config));
  }

  private static String key(String catalog, String schema, String table) {
    return ColumnKey.normalize(catalog, "catalog") + "."
        + ColumnKey.normalize(schema, "schema") + "."
        + ColumnKey.normalize(table, "table");
  }

  private static SqlMaskException error(String message) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, message);
  }
}
```

`PolicyRegistry.java` 增加静态工厂（字段与构造器不动）：

```java
  /** Derives the column-binding index from a validated configuration. */
  public static PolicyRegistry of(MaskingConfig config) {
    Map<ColumnKey, MaskingPolicy> byColumn = new LinkedHashMap<>();
    for (MaskingConfig.ColumnPolicyBinding binding : config.columnPolicies()) {
      byColumn.put(binding.key(), config.policies().get(binding.policyName()));
    }
    return new PolicyRegistry(config.policies(), byColumn);
  }
```

（相应 import 增加 `io.sqlmask.config.MaskingConfig`、`java.util.LinkedHashMap`；`YamlConfigLoader.buildRegistry` 方法体改为 `return PolicyRegistry.of(config);`。）

`RewriteEngine.java`：把现有 `rewrite(String metadataYaml, ...)` 方法体从 `SchemaPlus schema = ...` 起拆入新重载：

```java
  public List<StatementRewrite> rewrite(String metadataYaml, String sqlText, String dialectName) {
    LoadedConfig loaded =
        new YamlConfigLoader().loadContent(metadataYaml, "metadata.yaml", dialectName);
    return rewrite(loaded, sqlText, dialectName);
  }

  /** Rewrites against an already-resolved configuration (inline YAML or policy service). */
  public List<StatementRewrite> rewrite(LoadedConfig loaded, String sqlText, String dialectName) {
    // ……原方法体自 SchemaPlus schema = YamlCalciteSchemaFactory.create(loaded); 起，逐行原样搬入……
  }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl mask-core test -Dtest=EffectiveConfigAssemblerTest`
Expected: PASS（4 个用例）。

- [ ] **Step 5: 全量回归**

Run: `mvn test`
Expected: 全绿（引擎重载不改内联路径行为）。

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(core): 编译契约 DTO 与 ConfigSource 抽象，RewriteEngine 支持 LoadedConfig 重载"
```

---

### Task 3: PolicyServiceConfigSource（HTTP 拉取 + 版本缓存 + 错误映射）

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/error/SqlMaskException.java`（`Code` 枚举新增 `POLICY_SERVICE_UNAVAILABLE`、`POLICY_INSTANCE_NOT_FOUND`，追加在 `IO_ERROR` 之后）
- Create: `mask-core/src/main/java/io/sqlmask/config/source/PolicyServiceConfigSource.java`
- Test: `mask-core/src/test/java/io/sqlmask/config/source/PolicyServiceConfigSourceTest.java`

**Interfaces:**
- Consumes: Task 2 的 `EffectiveConfigResponse`、`EffectiveConfigAssembler`、`ConfigSource.ResolvedConfig`。
- Produces（Task 4 依赖）: `PolicyServiceConfigSource(String baseUrl, String apiKey, String instanceName)`；`ResolvedConfig load()`（有缓存返回缓存）；`boolean refresh()`（版本变化才更新缓存并返回 true）；错误映射：HTTP 404 → `POLICY_INSTANCE_NOT_FOUND`、401 → `CONFIG_ERROR`、网络异常/超时/其他 5xx → `POLICY_SERVICE_UNAVAILABLE`。

- [ ] **Step 1: 写失败测试（JDK HttpServer 桩）**

```java
package io.sqlmask.config.source;

import com.sun.net.httpserver.HttpServer;
import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyServiceConfigSourceTest {

  private static final String BODY_V1 = """
      {"instance":"pg_prod","dialect":"postgresql","configVersion":1,
       "policySummary":{"enabled":1,"disabled":0},
       "config":{"metadata":{"tables":[{"catalog":"crm","schema":"public","name":"customer",
         "rowFilter":null,"columns":[{"name":"phone","type":"varchar"}]}]},
         "columns":[{"catalog":"crm","schema":"public","table":"customer","column":"phone",
           "policy":"phone_mask"}],
         "policies":{"phone_mask":{"udf":"mask_phone","arguments":[3,4]}}}}
      """;

  private HttpServer server;
  private final AtomicReference<String> body = new AtomicReference<>(BODY_V1);
  private final AtomicReference<Integer> status = new AtomicReference<>(200);
  private final AtomicReference<String> seenKey = new AtomicReference<>("");

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/api/effective/pg_prod", exchange -> {
      seenKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
      byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(), bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private PolicyServiceConfigSource source() {
    return new PolicyServiceConfigSource(
        "http://127.0.0.1:" + server.getAddress().getPort(), "secret", "pg_prod");
  }

  @Test
  void fetchesAndAssembles() {
    ResolvedConfig resolved = source().load();
    assertEquals("postgresql", resolved.dialect());
    assertEquals(1, resolved.configVersion());
    assertEquals(1, resolved.config().tables().size());
    assertEquals("secret", seenKey.get());
  }

  @Test
  void refreshUpdatesCacheOnlyOnVersionChange() {
    PolicyServiceConfigSource s = source();
    s.load();
    assertTrue(s.refresh() == false); // 同版本
    body.set(BODY_V1.replace("\"configVersion\":1", "\"configVersion\":2"));
    assertTrue(s.refresh());
    assertEquals(2, s.load().configVersion());
  }

  @Test
  void staleCacheServedWhenServiceDown() {
    PolicyServiceConfigSource s = source();
    s.load();
    server.stop(0);
    assertEquals(1, s.load().configVersion()); // 仍返回缓存
  }

  @Test
  void noCacheAndServiceDownFailsClosed() {
    PolicyServiceConfigSource s = new PolicyServiceConfigSource(
        "http://127.0.0.1:1", "secret", "pg_prod"); // 端口 1 必然拒绝连接
    SqlMaskException e = assertThrows(SqlMaskException.class, s::load);
    assertEquals(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE, e.getCode());
  }

  @Test
  void notFoundMapsToInstanceNotFound() {
    status.set(404);
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> source().load());
    assertEquals(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND, e.getCode());
  }

  @Test
  void unauthorizedMapsToConfigError() {
    status.set(401);
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> source().load());
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl mask-core test -Dtest=PolicyServiceConfigSourceTest`
Expected: 编译失败（类/枚举值不存在）。

- [ ] **Step 3: 实现错误码与 HTTP 客户端**

`SqlMaskException.Code` 追加：

```java
    POLICY_SERVICE_UNAVAILABLE,
    POLICY_INSTANCE_NOT_FOUND,
```

`PolicyServiceConfigSource.java`：

```java
package io.sqlmask.config.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.error.SqlMaskException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Pulls the compiled effective configuration from the policy service and
 * caches it by version. Serves the cached configuration while the service is
 * unreachable (stale-but-available); a cold cache plus an unreachable service
 * fails closed with POLICY_SERVICE_UNAVAILABLE — never degrades to the
 * unmasked input.
 */
public final class PolicyServiceConfigSource implements ConfigSource {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient http;
  private final URI effectiveUri;
  private final String apiKey;
  private final String instanceName;
  private volatile ResolvedConfig cache;

  public PolicyServiceConfigSource(String baseUrl, String apiKey, String instanceName) {
    this.effectiveUri = URI.create(baseUrl + "/api/effective/" + instanceName);
    this.apiKey = apiKey;
    this.instanceName = instanceName;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Override
  public synchronized ResolvedConfig load() {
    if (cache != null) {
      return cache;
    }
    return cache = fetchAndAssemble();
  }

  /**
   * Polls the service and refreshes the cache when the version moved.
   *
   * @return true when the cache was updated
   */
  public synchronized boolean refresh() {
    ResolvedConfig fresh = fetchAndAssemble();
    if (cache != null && cache.configVersion() == fresh.configVersion()) {
      return false;
    }
    cache = fresh;
    return true;
  }

  private ResolvedConfig fetchAndAssemble() {
    HttpRequest request = HttpRequest.newBuilder(effectiveUri)
        .header("Accept", "application/json")
        .header("X-Api-Key", apiKey == null ? "" : apiKey)
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new SqlMaskException(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE,
          "policy service unreachable at '" + effectiveUri + "': " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SqlMaskException(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE,
          "interrupted while calling the policy service", e);
    }
    int code = response.statusCode();
    if (code == 404) {
      throw new SqlMaskException(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
          "policy instance '" + instanceName + "' does not exist on the policy service");
    }
    if (code == 401) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy service rejected the configured API key (HTTP 401)");
    }
    if (code != 200) {
      throw new SqlMaskException(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE,
          "policy service returned HTTP " + code + " for instance '" + instanceName + "'");
    }
    EffectiveConfigResponse payload;
    try {
      payload = JSON.readValue(response.body(), EffectiveConfigResponse.class);
    } catch (IOException e) {
      throw new SqlMaskException(SqlMaskException.Code.POLICY_SERVICE_UNAVAILABLE,
          "policy service returned an unreadable effective config: " + e.getMessage(), e);
    }
    return new ResolvedConfig(
        new EffectiveConfigAssembler().assemble(payload), payload.dialect(), payload.configVersion());
  }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl mask-core test -Dtest=PolicyServiceConfigSourceTest`
Expected: PASS（6 个用例）。

- [ ] **Step 5: 全量回归 + Commit**

Run: `mvn test` → 全绿。

```bash
git add -A
git commit -m "feat(core): PolicyServiceConfigSource 版本缓存拉取与 fail-closed 错误映射"
```

---

### Task 4: 改写服务与 CLI 接入（二选一参数、后台轮询、刷新端点、CLI 参数）

**Files:**
- Modify: `mask-core/src/main/java/io/sqlmask/server/RewriteController.java`
- Create: `mask-core/src/main/java/io/sqlmask/server/PolicyConfigProvider.java`
- Create: `mask-core/src/main/java/io/sqlmask/server/AdminController.java`
- Modify: `mask-core/src/main/java/io/sqlmask/cli/CliOptions.java`
- Modify: `mask-core/src/main/java/io/sqlmask/cli/SqlMaskRunner.java`
- Modify: `mask-core/src/main/java/io/sqlmask/cli/SqlMaskApplication.java`
- Modify: `mask-core/src/main/resources/application.yml`
- Test: `mask-core/src/test/java/io/sqlmask/server/RewriteControllerTest.java`（增补用例）、`mask-core/src/test/java/io/sqlmask/cli/SqlMaskRunnerTest.java`（增补用例）

**Interfaces:**
- Consumes: Task 2/3 的 `ConfigSource`、`InlineYamlConfigSource`、`PolicyServiceConfigSource`、`RewriteEngine.rewrite(LoadedConfig, ...)`。
- Produces:
  - `RewriteRequest(String metadataYaml, String instance, String sql, String dialect)`（保留三参便捷构造，instance=null，现有测试与前端不破坏）。
  - `PolicyConfigProvider`（@Component）：`ConfigSource sourceFor(String instance)`、`void refresh(String instance)`；配置项 `policy.service.url`、`policy.service.api-key`、`policy.service.poll-interval-seconds`（默认 30；url 为空表示未配置策略服务）。
  - `POST /admin/cache/refresh?instance=...` → `{"instance": "...", "refreshed": true|false}`。
  - CLI：`--instance <name>`、`--policy-service <url>`；环境变量回退 `SQLMASK_POLICY_SERVICE`、`SQLMASK_POLICY_API_KEY`。
  - `CliOptions(Path metadataPath, String instanceName, String policyServiceUrl, String sql, Path inputPath, Path outputPath, String dialect)`。

- [ ] **Step 1: 写失败测试（controller 二选一）**

在 `RewriteControllerTest` 追加（沿用该测试类现有的 MockMvc 构造方式；新用例走 MockMvc standalone 即可）：

```java
  @Test
  void rejectsRequestWithBothYamlAndInstance() throws Exception {
    mockMvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"metadataYaml":"metadata: {tables: []}","instance":"pg_prod",
                 "sql":"SELECT 1"}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void rejectsRequestWithNeitherYamlNorInstance() throws Exception {
    mockMvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sql\":\"SELECT 1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-core test -Dtest=RewriteControllerTest`
Expected: 新用例 FAIL（instance 字段尚不存在，两参同时给时走 YAML 路径返回 200）。

- [ ] **Step 3: 实现 PolicyConfigProvider、AdminController、RewriteController 改造**

`PolicyConfigProvider.java`：

```java
package io.sqlmask.server;

import io.sqlmask.config.source.ConfigSource;
import io.sqlmask.config.source.PolicyServiceConfigSource;
import io.sqlmask.error.SqlMaskException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns one cached config source per referenced policy-service instance and
 * polls them for version changes. Ranger-plugin semantics: the service being
 * down never blocks rewrites while a cached version exists.
 */
@Component
public class PolicyConfigProvider {

  private final Map<String, PolicyServiceConfigSource> sources = new ConcurrentHashMap<>();

  @Value("${policy.service.url:}")
  private String baseUrl;

  @Value("${policy.service.api-key:}")
  private String apiKey;

  @Value("${policy.service.poll-interval-seconds:30}")
  private long pollIntervalSeconds;

  private ScheduledExecutorService scheduler;

  @PostConstruct
  void start() {
    if (baseUrl == null || baseUrl.isBlank()) {
      return;
    }
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "policy-config-poller");
      t.setDaemon(true);
      return t;
    });
    scheduler.scheduleWithFixedDelay(this::refreshAll,
        pollIntervalSeconds, pollIntervalSeconds, TimeUnit.SECONDS);
  }

  private void refreshAll() {
    sources.keySet().forEach(name -> {
      try {
        sources.get(name).refresh();
      } catch (RuntimeException e) {
        // 轮询失败不打断服务：缓存继续可用，下一次轮询再试
      }
    });
  }

  public ConfigSource sourceFor(String instance) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy.service.url is not configured; the 'instance' rewrite mode requires "
              + "the policy service to be configured");
    }
    return sources.computeIfAbsent(instance,
        name -> new PolicyServiceConfigSource(baseUrl, apiKey, name));
  }

  /** @return true when the cache was refreshed (version changed). */
  public boolean refresh(String instance) {
    return ((PolicyServiceConfigSource) sourceFor(instance)).refresh();
  }

  @PreDestroy
  void stop() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }
}
```

`AdminController.java`：

```java
package io.sqlmask.server;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Emergency ops endpoint: force a cache refresh for one instance. */
@RestController
@RequestMapping("/admin")
public class AdminController {

  private final PolicyConfigProvider provider;

  public AdminController(PolicyConfigProvider provider) {
    this.provider = provider;
  }

  @PostMapping("/cache/refresh")
  public Map<String, Object> refresh(@RequestParam("instance") String instance) {
    return Map.of("instance", instance, "refreshed", provider.refresh(instance));
  }
}
```

（import 增加 `java.util.Map`。）

`RewriteController` 改造（构造器加 provider，重写 rewrite 方法与请求 record）：

```java
  private final RewriteEngine engine;
  private final PolicyConfigProvider provider;

  public RewriteController(RewriteEngine engine, PolicyConfigProvider provider) {
    this.engine = engine;
    this.provider = provider;
  }

  @PostMapping("/rewrite")
  public RewriteResponse rewrite(@RequestBody RewriteRequest request) {
    boolean hasYaml = request.metadataYaml() != null && !request.metadataYaml().isBlank();
    boolean hasInstance = request.instance() != null && !request.instance().isBlank();
    if (hasYaml == hasInstance) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "provide exactly one of 'metadataYaml' or 'instance'");
    }
    if (request.sql() == null || request.sql().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "sql is required: provide at least one SELECT statement");
    }
    ConfigSource source = hasInstance
        ? provider.sourceFor(request.instance())
        : new InlineYamlConfigSource(request.metadataYaml(),
            request.dialect() == null || request.dialect().isBlank()
                ? "postgresql" : request.dialect());
    ConfigSource.ResolvedConfig resolved = source.load();
    List<StatementRewrite> statements =
        engine.rewrite(resolved.config(), request.sql(), resolved.dialect());
    return new RewriteResponse(statements, RewriteEngine.join(statements));
  }

  public record RewriteRequest(String metadataYaml, String instance, String sql, String dialect) {

    public RewriteRequest(String metadataYaml, String sql, String dialect) {
      this(metadataYaml, null, sql, dialect);
    }
  }
```

（imports：`io.sqlmask.config.source.ConfigSource`、`io.sqlmask.config.source.InlineYamlConfigSource`。实例路径下 `request.dialect()` 被忽略——方言以策略服务返回的为准。）

- [ ] **Step 4: CLI 改造**

`CliOptions.java`（record 增加两个字段并保留旧构造器委托）：

```java
public record CliOptions(Path metadataPath, String instanceName, String policyServiceUrl,
    String sql, Path inputPath, Path outputPath, String dialect) {

  public CliOptions {
    java.util.Objects.requireNonNull(metadataPath == null == (instanceName == null)
            ? Boolean.TRUE : null, "either metadataPath or instanceName");
    dialect = dialect == null ? "postgresql" : dialect;
  }

  /** Legacy constructor for the --metadata path (existing tests keep working). */
  public CliOptions(Path metadataPath, String sql, Path inputPath, Path outputPath,
      String dialect) {
    this(metadataPath, null, null, sql, inputPath, outputPath, dialect);
  }
```

（上面 compact constructor 写法含糊——实现为明确分支：`if (metadataPath == null && instanceName == null) throw new IllegalArgumentException("either metadataPath or instanceName is required"); if (metadataPath != null && instanceName != null) throw new IllegalArgumentException("--metadata and --instance are mutually exclusive");`）

`SqlMaskRunner.java`：

```java
  public String run(CliOptions options) {
    String sqlText = options.sql() != null
        ? options.sql()
        : readUtf8(options.inputPath(), "SQL input file");
    if (options.instanceName() != null) {
      String serviceUrl = options.policyServiceUrl() != null
          ? options.policyServiceUrl() : System.getenv("SQLMASK_POLICY_SERVICE");
      String apiKey = System.getenv("SQLMASK_POLICY_API_KEY");
      if (serviceUrl == null || serviceUrl.isBlank()) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "--policy-service or $SQLMASK_POLICY_SERVICE is required with --instance");
      }
      ConfigSource.ResolvedConfig resolved = new PolicyServiceConfigSource(
          serviceUrl, apiKey, options.instanceName()).load();
      return RewriteEngine.join(
          new RewriteEngine().rewrite(resolved.config(), sqlText, resolved.dialect()));
    }
    String metadataYaml = readUtf8(options.metadataPath(), "metadata file");
    return RewriteEngine.join(
        new RewriteEngine().rewrite(metadataYaml, sqlText, options.dialect()));
  }
```

`SqlMaskApplication.java`：

- 增加两个 `@Option` 字段：

```java
  @Option(names = "--instance", paramLabel = "<name>",
      description = "Rewrite against a policy-service instance instead of --metadata. "
          + "Mutually exclusive with --metadata.")
  private String instance;

  @Option(names = "--policy-service", paramLabel = "<url>",
      description = "Base URL of the policy service (falls back to $SQLMASK_POLICY_SERVICE).")
  private String policyService;
```

- `execute()` 中 `if (metadataPath == null)` 分支改为：

```java
    if (metadataPath == null && instance == null) {
      err.println("sql-mask: --metadata or --instance is required for rewriting");
      return 2;
    }
    if (metadataPath != null && instance != null) {
      err.println("sql-mask: --metadata and --instance are mutually exclusive");
      return 2;
    }
```

- 构造 options 处改为：

```java
    CliOptions options = new CliOptions(metadataPath, instance, policyService,
        sql, inputPath, outputPath, dialect);
```

（方言校验逻辑保持现状不动，由多方言任务决定其演进；CLI 实例路径下方言取自策略服务响应。）

`application.yml`（core）追加：

```yaml
policy:
  service:
    url: ${POLICY_SERVICE_URL:}
    api-key: ${POLICY_SERVICE_API_KEY:}
    poll-interval-seconds: 30
```

- [ ] **Step 5: 跑新增 CLI 测试 + 全量回归**

在 `SqlMaskRunnerTest` 追加（同该类现有风格，用 `@TempDir` 写 YAML 文件）：

```java
  @Test
  void instanceAndMetadataAreMutuallyExclusive() {
    CliOptions options = new CliOptions(null, "pg_prod", "http://127.0.0.1:1",
        "SELECT 1", null, null, null);
    assertThrows(IllegalArgumentException.class, () ->
        new CliOptions(Path.of("m.yaml"), "pg_prod", null, "SELECT 1", null, null, null));
  }
```

Run: `mvn test`
Expected: 全绿。

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: 改写服务与 CLI 支持按实例名从策略微服务取配置（二选一 + 版本轮询缓存）"
```

---

### Task 5: mask-policy 域模型 + EngineDefs + PolicyValidator

**Files:**
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/model/EngineInstance.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/model/TableDef.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/model/ColumnDef.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/model/PolicyEntity.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/model/PolicyType.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/model/ResourceSelector.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/EngineDefs.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/PolicyValidator.java`
- Test: `mask-policy/src/test/java/io/sqlmask/policyserver/PolicyValidatorTest.java`

**Interfaces:**
- Consumes: core 的 `ColumnKey.normalize`、`DialectProfiles.byName(dialect).typeResolver().parseColumn`、`MaskingPolicy.validateArguments`、`RowFilterRegistry.build(LoadedConfig, DialectAdapter, SchemaPlus)`（filterExpr 白名单校验复用）、`YamlCalciteSchemaFactory.create`。
- Produces（Task 6/7/8 依赖）:
  - `EngineInstance(String name, String dialect, List<TableDef> tables)`
  - `TableDef(String catalog, String schema, String name, List<ColumnDef> columns)`（**无 rowFilter 字段**——已升格为策略）
  - `ColumnDef(String name, String typeDeclaration)`
  - `PolicyType { DATAMASK, ROW_FILTER }`
  - `ResourceSelector(String catalog, String schema, String table, List<String> columns)`（row_filter 时 columns 为空列表）
  - `PolicyEntity(String name, PolicyType policyType, boolean enabled, ResourceSelector resource, String udf, List<Object> arguments, String filterExpr)`
  - `EngineDefs.names() -> Set<String>`（postgresql/trino/mysql，取自 `DialectProfiles`）
  - `PolicyValidator`：`void validateInstance(EngineInstance instance)`、`void validatePolicy(EngineInstance instance, PolicyEntity policy, List<PolicyEntity> otherEnabledPolicies)`。所有违规抛 `SqlMaskException(CONFIG_ERROR)`，消息带实例名前缀。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.policyserver;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyValidatorTest {

  private static final EngineInstance INSTANCE = new EngineInstance("pg_prod", "postgresql",
      List.of(new TableDef("crm", "public", "customer", List.of(
          new ColumnDef("phone", "varchar"), new ColumnDef("email", "varchar")))));

  private final PolicyValidator validator = new PolicyValidator();

  private PolicyEntity datamask(String name, String table, List<String> columns) {
    return new PolicyEntity(name, PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", table, columns), "mask_phone", List.of(3, 4), null);
  }

  @Test
  void acceptsValidDatamaskPolicy() {
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, datamask("phone_mask", "customer", List.of("phone")), List.of()));
  }

  @Test
  void rejectsUnknownTableAndColumn() {
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validatePolicy(INSTANCE, datamask("p", "no_such", List.of("phone")), List.of()))
        .getMessage().contains("no_such"));
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validatePolicy(INSTANCE, datamask("p", "customer", List.of("fax")), List.of()))
        .getMessage().contains("fax"));
  }

  @Test
  void rejectsOverlapWithEnabledPolicy() {
    PolicyEntity existing = datamask("a_mask", "customer", List.of("phone", "email"));
    PolicyEntity overlapping = datamask("b_mask", "customer", List.of("email"));
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, overlapping, List.of(existing)));
    assertTrue(e.getMessage().contains("a_mask") && e.getMessage().contains("b_mask"));
  }

  @Test
  void disabledPoliciesDoNotBlock() {
    PolicyEntity disabled = new PolicyEntity("a_mask", PolicyType.DATAMASK, false,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(), null);
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, datamask("b_mask", "customer", List.of("phone")), List.of(disabled)));
  }

  @Test
  void rejectsRowFilterOverlapAndUnknownTable() {
    PolicyEntity rf = new PolicyEntity("rf", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()), null, List.of(),
        "status = 'active'");
    assertDoesNotThrow(() -> validator.validatePolicy(INSTANCE, rf, List.of()));
    PolicyEntity rf2 = new PolicyEntity("rf2", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()), null, List.of(),
        "id > 0");
    assertThrows(SqlMaskException.class, () -> validator.validatePolicy(INSTANCE, rf2, List.of(rf)));
  }

  @Test
  void rejectsMalformedFilterExpressionViaWhitelist() {
    PolicyEntity rf = new PolicyEntity("rf", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()), null, List.of(),
        "status = (SELECT status FROM t)");
    assertThrows(SqlMaskException.class, () -> validator.validatePolicy(INSTANCE, rf, List.of()));
  }

  @Test
  void rejectsBadInstanceNameDialectAndTypes() {
    assertThrows(SqlMaskException.class, () -> validator.validateInstance(
        new EngineInstance("bad name!", "postgresql", INSTANCE.tables())));
    assertThrows(SqlMaskException.class, () -> validator.validateInstance(
        new EngineInstance("x", "oracle", INSTANCE.tables())));
    assertThrows(SqlMaskException.class, () -> validator.validateInstance(
        new EngineInstance("x", "trino", List.of(
            new TableDef("c", "s", "t", List.of(new ColumnDef("a", "datetime")))))));
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-policy test -Dtest=PolicyValidatorTest`
Expected: 编译失败。

- [ ] **Step 3: 实现模型与校验器**

模型 record（全部 `io.sqlmask.policyserver.model` 包，紧凑构造器仅做非空拷贝，风格同 core records）：

```java
public record EngineInstance(String name, String dialect, List<TableDef> tables) {
  public EngineInstance {
    tables = List.copyOf(tables);
  }
}

public record TableDef(String catalog, String schema, String name, List<ColumnDef> columns) {
  public TableDef {
    columns = List.copyOf(columns);
  }
}

public record ColumnDef(String name, String typeDeclaration) {
}

public enum PolicyType { DATAMASK, ROW_FILTER }

public record ResourceSelector(String catalog, String schema, String table, List<String> columns) {
  public ResourceSelector {
    columns = List.copyOf(columns);
  }
}

public record PolicyEntity(String name, PolicyType policyType, boolean enabled,
    ResourceSelector resource, String udf, List<Object> arguments, String filterExpr) {
  public PolicyEntity {
    arguments = arguments == null ? List.of() : List.copyOf(arguments);
  }
}
```

`EngineDefs.java`：

```java
package io.sqlmask.policyserver;

import io.sqlmask.dialect.DialectProfiles;

import java.util.Set;

/** Built-in engine definitions: the dialects the rewrite engine itself supports. */
public final class EngineDefs {

  private EngineDefs() {
  }

  public static Set<String> names() {
    return DialectProfiles.names();
  }
}
```

（若 `DialectProfiles` 尚无 `names()` 静态方法，则在 core `DialectProfiles` 补一个返回 `Set.of("postgresql", "trino", "mysql")` 或注册表派生集合的静态方法——与 `DialectRegistry` 保持同源。）

`PolicyValidator.java`：

```java
package io.sqlmask.policyserver;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.MaskingConfig;
import io.sqlmask.dialect.DialectProfiles;
import io.sqlmask.dialect.TypeResolver;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import io.sqlmask.policy.MasingPolicy; // 笔误防护：实际 import io.sqlmask.policy.MaskingPolicy
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.rowfilter.RowFilterRegistry;
import org.apache.calcite.schema.SchemaPlus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Config-time gatekeeper: every write path validates here so the compiled
 * effective config is always self-consistent (dangling references and
 * selector overlaps are impossible by construction).
 */
public final class PolicyValidator {

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.\\-]+");

  public void validateInstance(EngineInstance instance) {
    requireName(instance.name(), "instance name");
    if (!EngineDefs.names().contains(instance.dialect())) {
      throw error("instance '" + instance.name() + "': unknown dialect '" + instance.dialect()
          + "' (supported: " + EngineDefs.names() + ")");
    }
    TypeResolver typeResolver = DialectProfiles.byName(instance.dialect()).typeResolver();
    Set<String> seenTables = new LinkedHashSet<>();
    for (TableDef table : instance.tables()) {
      String tableKey = tableKey(table);
      if (!seenTables.add(tableKey)) {
        throw error("instance '" + instance.name() + "': duplicate table '" + tableKey + "'");
      }
      Set<String> seenColumns = new LinkedHashSet<>();
      for (ColumnDef column : table.columns()) {
        String normalized = ColumnKey.normalize(column.name(), "column");
        if (!seenColumns.add(normalized)) {
          throw error("instance '" + instance.name() + "': table '" + tableKey
              + "': duplicate column '" + column.name() + "'");
        }
        try {
          typeResolver.parseColumn(column.name(), column.typeDeclaration());
        } catch (SqlMaskException | IllegalArgumentException e) {
          throw error("instance '" + instance.name() + "': table '" + tableKey + "' column '"
              + column.name() + "': " + e.getMessage()
              + " (dialect " + instance.dialect() + ")");
        }
      }
    }
  }

  public void validatePolicy(EngineInstance instance, PolicyEntity policy,
      List<PolicyEntity> otherEnabledPolicies) {
    requireName(instance.name(), "instance name");
    requireName(policy.name(), "policy name");
    TableMetadata target = findTable(instance, policy.resource());
    switch (policy.policyType()) {
      case DATAMASK -> {
        if (policy.udf() == null || policy.udf().isBlank()) {
          throw error("policy '" + policy.name() + "': datamask requires a udf");
        }
        MaskingPolicy.validateArguments(policy.arguments(), policy.name(),
            "instance '" + instance.name() + "'");
        if (policy.resource().columns().isEmpty()) {
          throw error("policy '" + policy.name() + "': datamask requires at least one column");
        }
        Set<String> tableColumns = new LinkedHashSet<>();
        target.columns().forEach(c -> tableColumns.add(ColumnKey.normalize(c.name(), "column")));
        for (String column : policy.resource().columns()) {
          if (!tableColumns.contains(ColumnKey.normalize(column, "column"))) {
            throw error("policy '" + policy.name() + "': unknown column '" + column
                + "' in table '" + tableKey(policy.resource()) + "'");
          }
        }
      }
      case ROW_FILTER -> {
        if (policy.filterExpr() == null || policy.filterExpr().isBlank()) {
          throw error("policy '" + policy.name() + "': row_filter requires filterExpr");
        }
        validateFilterExpression(instance, policy);
      }
    }
    for (PolicyEntity other : otherEnabledPolicies) {
      if (other.name().equals(policy.name()) || other.policyType() != policy.policyType()) {
        continue;
      }
      if (!ColumnKey.normalize(other.resource().table(), "table")
          .equals(ColumnKey.normalize(policy.resource().table(), "table"))
          || !ColumnKey.normalize(other.resource().schema(), "schema")
          .equals(ColumnKey.normalize(policy.resource().schema(), "schema"))
          || !ColumnKey.normalize(other.resource().catalog(), "catalog")
          .equals(ColumnKey.normalize(policy.resource().catalog(), "catalog"))) {
        continue;
      }
      boolean overlap = policy.policyType() == PolicyType.ROW_FILTER
          || intersects(other.resource().columns(), policy.resource().columns());
      if (overlap) {
        throw error("policy '" + policy.name() + "' overlaps enabled policy '" + other.name()
            + "' on table '" + tableKey(policy.resource()) + "'; disable one of them first");
      }
    }
  }

  /** Reuses the rewrite engine's row-filter whitelist as the config-time gate. */
  private void validateFilterExpression(EngineInstance instance, PolicyEntity policy) {
    List<TableMetadata> tables = new ArrayList<>();
    instance.tables().forEach(t -> {
      List<TableMetadata.Column> columns = new ArrayList<>();
      t.columns().forEach(c -> columns.add(
          DialectProfiles.byName(instance.dialect()).typeResolver()
              .parseColumn(c.name(), c.typeDeclaration())));
      tables.add(new TableMetadata(t.catalog(), t.schema(), t.name(), columns, null));
    });
    MaskingConfig config = new MaskingConfig(tables, List.of(), java.util.Map.of());
    LoadedConfig loaded = new LoadedConfig(config, io.sqlmask.policy.PolicyRegistry.of(config));
    SchemaPlus schema = YamlCalciteSchemaFactory.create(loaded);
    try {
      RowFilterRegistry.build(loaded, DialectRegistry.create(instance.dialect()), schema);
    } catch (SqlMaskException e) {
      throw error("policy '" + policy.name() + "': invalid filterExpr: " + e.getMessage());
    }
  }

  private TableMetadata findTable(EngineInstance instance, ResourceSelector resource) {
    String wanted = tableKey(resource);
    return instance.tables().stream()
        .filter(t -> tableKey(t).equals(wanted))
        .findFirst()
        .orElseThrow(() -> error("policy '" + "?"
            + "': unknown table '" + wanted + "'"));  // 实现时统一用 policy.name()
  }

  private static boolean intersects(List<String> a, List<String> b) {
    Set<String> left = new LinkedHashSet<>();
    a.forEach(c -> left.add(ColumnKey.normalize(c, "column")));
    return b.stream().anyMatch(c -> left.contains(ColumnKey.normalize(c, "column")));
  }

  private static String tableKey(ResourceSelector r) {
    return ColumnKey.normalize(r.catalog(), "catalog") + "."
        + ColumnKey.normalize(r.schema(), "schema") + "."
        + ColumnKey.normalize(r.table(), "table");
  }

  private static void requireName(String name, String what) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw error(what + " '" + name + "' must match " + NAME.pattern());
    }
  }

  private static SqlMaskException error(String message) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, message);
  }
}
```

实现注意（执行者照做）：`import io.sqlmask.policy.MaskingPolicy`；`RowFilterRegistry.build` 需要 `DialectAdapter`——用 `io.sqlmask.dialect.DialectRegistry.create(instance.dialect())`（补 import）；`findTable` 的错误消息带 `policy.name()`（把 policy 名传入方法）。`validateFilterExpression` 对整实例建 schema 一次即可（row_filter 与表列白名单均由 `RowFilterRegistry.build` 抛错，错误消息含表名前缀，保留）。

- [ ] **Step 4: 运行确认通过 + 全量回归**

Run: `mvn -pl mask-policy test -Dtest=PolicyValidatorTest && mvn test`
Expected: PASS / 全绿。

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(policy): Ranger 式域模型与配置期校验器（重叠拒绝、悬空引用、方言类型、行过滤白名单）"
```

---

### Task 6: PolicyStore 接口 + 内存实现 + PolicyService + EffectiveConfigCompiler

**Files:**
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/store/PolicyStore.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/store/InMemoryPolicyStore.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/PolicyService.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/compile/EffectiveConfigCompiler.java`
- Test: `mask-policy/src/test/java/io/sqlmask/policyserver/store/InMemoryPolicyStoreTest.java`、`mask-policy/src/test/java/io/sqlmask/policyserver/compile/EffectiveConfigCompilerTest.java`、`mask-policy/src/test/java/io/sqlmask/policyserver/PolicyServiceTest.java`

**Interfaces:**
- Consumes: Task 5 模型与 `PolicyValidator`；core 的 `EffectiveConfigResponse`（Task 2 DTO）。
- Produces（Task 7/8 依赖）:
  - `PolicyStore` 接口与 `InMemoryPolicyStore`（线程安全，全部方法 synchronized；所有变更原子递增该实例 `config_version`，初始 1）：
    ```java
    public interface PolicyStore {
      EngineInstance createInstance(EngineInstance instance);
      EngineInstance updateInstanceTables(String name, List<TableDef> tables);
      Optional<EngineInstance> findInstance(String name);
      List<EngineInstance> listInstances();
      void deleteInstance(String name);            // 存在策略时抛 CONFIG_ERROR
      PolicyEntity createPolicy(String instanceName, PolicyEntity policy);
      PolicyEntity updatePolicy(String instanceName, String policyName, PolicyEntity policy);
      Optional<PolicyEntity> findPolicy(String instanceName, String policyName);
      List<PolicyEntity> listPolicies(String instanceName);
      void deletePolicy(String instanceName, String policyName);
      long currentVersion(String instanceName);    // 未知实例抛 POLICY_INSTANCE_NOT_FOUND
    }
    ```
  - `PolicyService(PolicyStore store, PolicyValidator validator)`：`createInstance(String name, String dialect, List<TableDef> tables)`、`updateInstanceTables(String name, List<TableDef> tables)`（删除被启用策略引用的表/列 → CONFIG_ERROR 列出引用者）、`instance(name)`（未知 → `POLICY_INSTANCE_NOT_FOUND`）、`instances()`、`deleteInstance(name)`、`createPolicy/updatePolicy/deletePolicy/policies(...)`（updatePolicy 允许改 enabled；除 deletePolicy 外都过 validator）、`effective(name) -> EffectiveConfigResponse`。
  - `EffectiveConfigCompiler.compile(EngineInstance instance, List<PolicyEntity> policies) -> EffectiveConfigResponse`。

- [ ] **Step 1: 写失败测试（store + compiler + service）**

```java
package io.sqlmask.policyserver.store;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryPolicyStoreTest {

  private final InMemoryPolicyStore store = new InMemoryPolicyStore();
  private static final EngineInstance INSTANCE = new EngineInstance("pg_prod", "postgresql",
      List.of(new TableDef("crm", "public", "customer",
          List.of(new ColumnDef("phone", "varchar")))));

  private static PolicyEntity datamask(String name) {
    return new PolicyEntity(name, PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(3, 4), null);
  }

  @Test
  void versionBumpsOnEveryMutation() {
    store.createInstance(INSTANCE);
    assertEquals(1, store.currentVersion("pg_prod"));
    store.updateInstanceTables("pg_prod", INSTANCE.tables());
    assertEquals(2, store.currentVersion("pg_prod"));
    store.createPolicy("pg_prod", datamask("p1"));
    assertEquals(3, store.currentVersion("pg_prod"));
    store.updatePolicy("pg_prod", "p1",
        new PolicyEntity("p1", PolicyType.DATAMASK, false,
            new ResourceSelector("crm", "public", "customer", List.of("phone")),
            "mask_phone", List.of(3, 4), null));
    assertEquals(4, store.currentVersion("pg_prod"));
    store.deletePolicy("pg_prod", "p1");
    assertEquals(5, store.currentVersion("pg_prod"));
  }

  @Test
  void deleteInstanceBlockedWhilePoliciesExist() {
    store.createInstance(INSTANCE);
    store.createPolicy("pg_prod", datamask("p1"));
    assertThrows(SqlMaskException.class, () -> store.deleteInstance("pg_prod"));
    store.deletePolicy("pg_prod", "p1");
    store.deleteInstance("pg_prod");
    assertEquals(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
        assertThrows(SqlMaskException.class, () -> store.currentVersion("pg_prod")).getCode());
  }
}
```

```java
package io.sqlmask.policyserver.compile;

import io.sqlmask.config.source.EffectiveConfigResponse;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EffectiveConfigCompilerTest {

  private static final EngineInstance INSTANCE = new EngineInstance("pg_prod", "postgresql",
      List.of(new TableDef("crm", "public", "customer", List.of(
          new ColumnDef("phone", "varchar"), new ColumnDef("email", "varchar")))));

  @Test
  void expandsSelectorsBackfillsRowFiltersAndCountsDisabled() {
    List<PolicyEntity> policies = List.of(
        new PolicyEntity("phone_mask", PolicyType.DATAMASK, true,
            new ResourceSelector("crm", "public", "customer", List.of("phone", "email")),
            "mask_phone", List.of(3, 4), null),
        new PolicyEntity("disabled_mask", PolicyType.DATAMASK, false,
            new ResourceSelector("crm", "public", "customer", List.of("phone")),
            "x", List.of(), null),
        new PolicyEntity("rf", PolicyType.ROW_FILTER, true,
            new ResourceSelector("crm", "public", "customer", List.of()), null, List.of(),
            "status = 'active'"));
    EffectiveConfigResponse response =
        EffectiveConfigCompiler.compile(INSTANCE, policies);

    assertEquals(2, response.policySummary().enabled());
    assertEquals(1, response.policySummary().disabled());
    var table = response.config().metadata().tables().get(0);
    assertEquals("status = 'active'", table.rowFilter());
    assertEquals(List.of(
            new EffectiveConfigResponse.ColumnBinding("crm", "public", "customer", "phone", "phone_mask"),
            new EffectiveConfigResponse.ColumnBinding("crm", "public", "customer", "email", "phone_mask")),
        response.config().columns());
    assertEquals(new EffectiveConfigResponse.UdfDefinition("mask_phone", List.of(3, 4)),
        response.config().policies().get("phone_mask"));
    assertEquals("varchar", table.columns().get(0).type());
  }

  @Test
  void blankRowFilterAbsentWhenNoRowFilterPolicy() {
    EffectiveConfigResponse response = EffectiveConfigCompiler.compile(INSTANCE, List.of());
    assertNull(response.config().metadata().tables().get(0).rowFilter());
    assertEquals(0, response.config().columns().size());
  }
}
```

```java
package io.sqlmask.policyserver;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.store.InMemoryPolicyStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PolicyServiceTest {

  private final PolicyService service =
      new PolicyService(new InMemoryPolicyStore(), new PolicyValidator());

  @Test
  void updateMetadataRejectsRemovingReferencedTable() {
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    service.createPolicy("pg_prod", new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(), null));
    assertThrows(SqlMaskException.class, () ->
        service.updateInstanceTables("pg_prod", List.of()));
    // 先禁用后可删
    service.updatePolicy("pg_prod", "p", new PolicyEntity("p", PolicyType.DATAMASK, false,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(), null));
    service.updateInstanceTables("pg_prod", List.of());
  }

  @Test
  void effectiveReturnsCompiledResponse() {
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    service.createPolicy("pg_prod", new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(3, 4), null));
    var response = service.effective("pg_prod");
    assertEquals(1, response.config().columns().size());
    assertEquals("postgresql", response.dialect());
  }

  @Test
  void unknownInstanceMapsToNotFound() {
    assertEquals(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
        assertThrows(SqlMaskException.class, () -> service.effective("nope")).getCode());
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-policy test`
Expected: 编译失败。

- [ ] **Step 3: 实现 store、service、compiler**

实现要点（执行者按接口与测试补全代码）：
- `InMemoryPolicyStore`：`Map<String, EngineInstance> instances`、`Map<String, Map<String, PolicyEntity>> policiesByInstance`、`Map<String, Long> versions`，全 synchronized；`createInstance` 重名抛 `CONFIG_ERROR`；每个变更方法末尾 `versions.merge(instanceName, 1L, Long::sum)`；`currentVersion` 对未知实例抛 `POLICY_INSTANCE_NOT_FOUND`。
- `PolicyService`：所有写方法先 `store.findInstance`（未知 → `POLICY_INSTANCE_NOT_FOUND`）→ `validator.validateInstance/validatePolicy(instance, policy, enabledOthers)` → `store.xxx`；`updateInstanceTables` 在校验器通过后，还要检查现有**启用**策略引用的表/列是否仍存在于新表结构（缺失 → `CONFIG_ERROR` 列出引用策略名）；`effective(name)` 调 `EffectiveConfigCompiler.compile(instance, store.listPolicies(name))`。
- `EffectiveConfigCompiler`：纯静态函数——enabled 过滤；`DATAMASK` 按选择器列序展开为 `ColumnBinding`（catalog/schema/table 用**原始声明文本**原样输出，column 用选择器里的原始列名）；`ROW_FILTER` 回填对应 `TablePayload.rowFilter`（同一表多条启用的 row_filter 在配置期已不可能，编译期遇到则抛 `CONFIG_ERROR` 断言）；`TablePayload.columns` 用 `ColumnDef.typeDeclaration()` 原文；`PolicySummary(enabled, disabled)`；`configVersion` 由调用方（PolicyService）从 `store.currentVersion` 填入。

- [ ] **Step 4: 运行确认通过 + 全量回归 + Commit**

Run: `mvn test` → 全绿。

```bash
git add -A
git commit -m "feat(policy): PolicyStore 内存实现、PolicyService 门面与生效配置编译器"
```

---

### Task 7: JdbcPolicyStore（PG）+ schema.sql

**Files:**
- Modify: `mask-policy/pom.xml`（dependencies 增加 `spring-boot-starter-jdbc` 与 `org.postgresql:postgresql`）
- Create: `mask-policy/src/main/resources/schema.sql`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/store/JdbcPolicyStore.java`
- Test: `mask-policy/src/test/java/io/sqlmask/policyserver/store/JdbcPolicyStoreIT.java`

**Interfaces:**
- Consumes: Task 6 的 `PolicyStore` 接口（实现同一接口，行为与 InMemory 完全一致，含版本递增与删除保护）。
- Produces: `JdbcPolicyStore(JdbcTemplate jdbc)`；由 Task 8 以 `@ConditionalOnProperty(name = "policy.store", havingValue = "jdbc")` 装配。

- [ ] **Step 1: schema.sql**

```sql
CREATE TABLE IF NOT EXISTS policy_instance (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(255) NOT NULL UNIQUE,
  dialect VARCHAR(64) NOT NULL,
  config_version BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS instance_table (
  id BIGSERIAL PRIMARY KEY,
  instance_id BIGINT NOT NULL REFERENCES policy_instance(id) ON DELETE CASCADE,
  catalog VARCHAR(255) NOT NULL,
  schema_name VARCHAR(255) NOT NULL,
  table_name VARCHAR(255) NOT NULL,
  position INT NOT NULL
);

CREATE TABLE IF NOT EXISTS instance_column (
  id BIGSERIAL PRIMARY KEY,
  table_id BIGINT NOT NULL REFERENCES instance_table(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  type_declaration TEXT NOT NULL,
  position INT NOT NULL
);

CREATE TABLE IF NOT EXISTS policy (
  id BIGSERIAL PRIMARY KEY,
  instance_id BIGINT NOT NULL REFERENCES policy_instance(id) ON DELETE CASCADE,
  name VARCHAR(255) NOT NULL,
  policy_type VARCHAR(32) NOT NULL,
  is_enabled BOOLEAN NOT NULL,
  udf VARCHAR(255),
  arguments JSONB,
  filter_expr TEXT,
  resource JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (instance_id, name)
);
```

- [ ] **Step 2: 写条件集成测试**

`JdbcPolicyStoreIT.java`：`@EnabledIfEnvironmentVariable(named = "POLICY_PG_URL", matches = ".+")`——设置该环境变量（指向 PG 的 JDBC URL，如 `jdbc:postgresql://127.0.0.1:5432/sqlmask_policy`，配套 `POLICY_PG_USER`/`POLICY_PG_PASSWORD`）才执行；测试内容为把 `InMemoryPolicyStoreTest` 的两个用例在 `JdbcPolicyStore` 上重跑一遍（自建随机后缀实例名，`@AfterEach` 清理）。无环境变量时测试静默跳过，CI 与本地无 Docker 环境不阻塞。

- [ ] **Step 3: 实现 JdbcPolicyStore**

实现要点：
- 构造注入 `JdbcTemplate`；`@Transactional` 注在每个变更方法上（变更与 `UPDATE policy_instance SET config_version = config_version + 1, updated_at = now()` 同事务）；
- 表结构存 `instance_table`/`instance_column`（position 保序）；policy 存 `policy` 表，`resource`/`arguments` 用 Jackson 序列化后以 `?::jsonb` 写入、读出时 `ObjectMapper.readValue`；
- `createInstance` 重名 → 捕获唯一约束异常翻译为 `SqlMaskException(CONFIG_ERROR, "instance 'x' already exists")`；`deleteInstance` 先查策略数（>0 → `CONFIG_ERROR`）；
- 未知实例的所有操作 → `POLICY_INSTANCE_NOT_FOUND`；
- `findInstance` 读全量表结构（两条查询按 position 排序组装）。

- [ ] **Step 4: 无 PG 环境验证编译 + 有 PG 环境验证**

Run: `mvn -pl mask-policy test`（无 `POLICY_PG_URL` 时 IT 跳过，其余全绿）
若本机已有 compose PG（Task 9）可设变量重跑：`POLICY_PG_URL=jdbc:postgresql://127.0.0.1:5432/sqlmask_policy POLICY_PG_USER=sqlmask POLICY_PG_PASSWORD=sqlmask mvn -pl mask-policy test -Dtest=JdbcPolicyStoreIT`

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(policy): PostgreSQL 存储实现（JSONB 选择器 + 事务内版本递增）"
```

---

### Task 8: REST API + API Key 过滤器 + YAML 导入器 + Spring Boot 装配

**Files:**
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/PolicyServiceApplication.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/web/InstanceController.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/web/PolicyController.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/web/EffectiveConfigController.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/web/ApiKeyFilter.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/web/ApiExceptionHandler.java`
- Create: `mask-policy/src/main/java/io/sqlmask/policyserver/yaml/YamlInstanceImporter.java`
- Create: `mask-policy/src/main/resources/application.yml`
- Modify: `mask-policy/pom.xml`（shade：mainClass `io.sqlmask.policyserver.PolicyServiceApplication`，finalName `mask-policy`）
- Test: `mask-policy/src/test/java/io/sqlmask/policyserver/web/PolicyApiTest.java`（@SpringBootTest + MockMvc，`policy.store=memory`）、`mask-policy/src/test/java/io/sqlmask/policyserver/yaml/YamlInstanceImporterTest.java`

**Interfaces:**
- Consumes: Task 6 `PolicyService`、Task 7 `JdbcPolicyStore`、core `YamlConfigLoader`/`LoadedConfig`、Task 2 `EffectiveConfigResponse`。
- Produces:
  - 管理面：`GET/POST /api/instances`、`GET/PUT/DELETE /api/instances/{name}`、`GET/POST /api/instances/{name}/policies`、`PUT/DELETE /api/instances/{name}/policies/{policyName}`；`POST /api/instances` 支持两种体：`{"name","dialect","tables":[...]}` 或 `{"name","dialect","metadataYaml":"..."}`（导入器路径）。
  - 数据面：`GET /api/effective/{name}`（Task 2 DTO 原样序列化）。
  - `ApiKeyFilter`：路径前缀 `/api/effective` 用 `policy.service.data-key` 校验，其余 `/api/**` 用 `policy.service.admin-key`；对应属性为空则该面不鉴权（开发模式）；Header 名 `X-Api-Key`；不匹配返回 401 `{"code":"UNAUTHORIZED","message":...}`。
  - `ApiExceptionHandler`：`SqlMaskException` → `POLICY_INSTANCE_NOT_FOUND`→404、`CONFIG_ERROR`/`VALIDATION_ERROR` 等→400、其余→500；体 `{"code","message"}`。
  - Spring 装配：`policy.store=memory`（默认）→ `InMemoryPolicyStore`；`policy.store=jdbc` → `JdbcPolicyStore`；`spring.sql.init.mode=always` + `schema.sql` 完成建表。

- [ ] **Step 1: 写失败测试（MockMvc 全链路）**

`PolicyApiTest.java` 核心用例（`@SpringBootTest(properties = {"policy.store=memory", "policy.service.admin-key=adm", "policy.service.data-key=dat"})` + `@AutoConfigureMockMvc` + 每个 @AfterEach 清空内存 store 或用 `@DirtiesContext`）：

```java
  private static final String CREATE = """
      {"name":"pg_prod","dialect":"postgresql",
       "metadataYaml":"metadata:\\n  tables:\\n    - catalog: crm\\n      schema: public\\n        name: customer\\n      rowFilter: \\"status = 'active'\\"\\n      columns:\\n        - { name: phone, type: varchar }\\n        - { name: email, type: varchar }\\ncolumns:\\n  - { catalog: crm, schema: public, table: customer, column: phone, policy: phone_mask }\\npolicies:\\n  phone_mask: { udf: mask_phone, arguments: [3, 4] }\\n"}
      """;

  @Test
  void importYamlCreatesInstanceAndPolicies() throws Exception {
    mockMvc.perform(post("/api/instances").header("X-Api-Key", "adm")
            .contentType(MediaType.APPLICATION_JSON).content(CREATE))
        .andExpect(status().isCreated());
    // 数据面：禁用策略剔除由后续开关测试覆盖，这里验证编译产物形状
    mockMvc.perform(get("/api/effective/pg_prod").header("X-Api-Key", "dat"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instance").value("pg_prod"))
        .andExpect(jsonPath("$.dialect").value("postgresql"))
        .andExpect(jsonPath("$.policySummary.enabled").value(2))
        .andExpect(jsonPath("$.config.metadata.tables[0].rowFilter").value("status = 'active'"))
        .andExpect(jsonPath("$.config.policies.phone_mask.udf").value("mask_phone"));
  }

  @Test
  void effectiveRequiresDataKey() throws Exception {
    mockMvc.perform(get("/api/effective/pg_prod"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unknownInstanceIs404() throws Exception {
    mockMvc.perform(get("/api/effective/nope").header("X-Api-Key", "dat"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POLICY_INSTANCE_NOT_FOUND"));
  }

  @Test
  void togglingPolicyChangesCompiledOutput() throws Exception {
    importYamlCreatesInstanceAndPolicies();
    mockMvc.perform(put("/api/instances/pg_prod/policies/phone_mask")
            .header("X-Api-Key", "adm").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"phone_mask\",\"policyType\":\"DATAMASK\",\"enabled\":false,"
                + "\"resource\":{\"catalog\":\"crm\",\"schema\":\"public\",\"table\":\"customer\","
                + "\"columns\":[\"phone\"]},\"udf\":\"mask_phone\",\"arguments\":[3,4]}"))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/effective/pg_prod").header("X-Api-Key", "dat"))
        .andExpect(jsonPath("$.policySummary.enabled").value(1))
        .andExpect(jsonPath("$.policySummary.disabled").value(1))
        .andExpect(jsonPath("$.config.columns.length()").value(0));
  }

  @Test
  void overlappingPolicyRejectedWith400() throws Exception {
    importYamlCreatesInstanceAndPolicies();
    mockMvc.perform(post("/api/instances/pg_prod/policies")
            .header("X-Api-Key", "adm").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"dup\",\"policyType\":\"DATAMASK\",\"enabled\":true,"
                + "\"resource\":{\"catalog\":\"crm\",\"schema\":\"public\",\"table\":\"customer\","
                + "\"columns\":[\"phone\"]},\"udf\":\"mask_phone\",\"arguments\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }
```

`YamlInstanceImporterTest`：直接单测导入器——含 `rowFilter` 的表声明生成 `ROW_FILTER` 策略（名为 `rowfilter_crm_public_customer`）；跨多表的同一 policy 名绑定拆成多条（第一组用原名，其余 `name_2`、`name_3` 按表名字典序）；无绑定引用的 policies 段条目计入 `ignoredPolicies`。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-policy test`
Expected: 编译失败。

- [ ] **Step 3: 实现（controller / filter / handler / importer / application / pom shade）**

实现要点：
- `PolicyServiceApplication`：`@SpringBootApplication`（包扫描默认 `io.sqlmask.policyserver`）+ `@Bean PolicyService policyService(PolicyStore store, PolicyValidator validator)` + `@Bean InMemoryPolicyStore`（`@ConditionalOnMissingBean(PolicyStore.class)` 或 `@ConditionalOnProperty(name="policy.store", havingValue="memory", matchIfMissing=true)`）+ `@Bean @ConditionalOnProperty(name="policy.store", havingValue="jdbc") JdbcPolicyStore(JdbcTemplate jdbc)`。
- 控制器为 `PolicyService` 的薄封装；请求体直接用模型 record（Jackson 反序列化 record）；创建实例返回 201。
- `YamlInstanceImporter.importYaml(String dialect, String yaml) -> ImportPlan(List<TableDef> tables, List<PolicyEntity> policies, List<String> ignoredPolicies)`：
  - `LoadedConfig loaded = new YamlConfigLoader().loadContent(yaml, "import.yaml", dialect)`；
  - tables：`new TableDef(t.catalog(), t.schema(), t.name(), t.columns().stream().map(c -> new ColumnDef(c.name(), c.typeDeclaration())).toList())`（`typeDeclaration()` 已有原文/重构兜底）；
  - 表声明 `rowFilter()` 非空 → `ROW_FILTER` 策略 `rowfilter_<catalog>_<schema>_<table>`（规范化名点换下划线），enabled=true；
  - 绑定按 `(policyName, 规范化表键)` 分组 → 每组一条 `DATAMASK` 策略：单组用原 policy 名，多组按表键字典序，第一组原名、其余 `原名_2`、`原名_3`；`udf/arguments` 取自 `loaded.config().policies().get(policyName)`；
  - `policies` 段中没有被任何绑定引用的名字 → `ignoredPolicies`。
- `application.yml`（mask-policy）：

```yaml
server:
  port: 8081

spring:
  application:
    name: mask-policy
  sql:
    init:
      mode: ${POLICY_SQL_INIT:embedded}

policy:
  store: ${POLICY_STORE:memory}
  service:
    admin-key: ${POLICY_SERVICE_ADMIN_KEY:}
    data-key: ${POLICY_SERVICE_DATA_KEY:}
```

（`spring.sql.init.mode=embedded` 默认只在内存库执行 schema.sql；jdbc 模式由部署方设 `POLICY_SQL_INIT=always`。）
- `mask-policy/pom.xml` 增加 shade（配置复制 Task 1 Step 3 的 shade 段，`finalName` 改 `mask-policy`，mainClass 改 `io.sqlmask.policyserver.PolicyServiceApplication`）。

- [ ] **Step 4: 运行确认通过 + 全量回归**

Run: `mvn test`
Expected: 全绿。

- [ ] **Step 5: 打包验证 + Commit**

Run: `mvn package -DskipTests && ls mask-policy/target/mask-policy.jar`
Expected: jar 生成。

```bash
git add -A
git commit -m "feat(policy): 策略微服务 REST API、API Key 鉴权、YAML 导入器与可执行 fat jar"
```

---

### Task 9: docker-compose 与端到端验证

**Files:**
- Create: `docker-compose.yml`
- Test: `mask-policy/src/test/java/io/sqlmask/policyserver/endtoend/PolicyServiceEndToEndIT.java`

**Interfaces:**
- Produces: 本地一键起 PG + 策略微服务；条件端到端测试（`POLICY_E2E=1` 时启用）走 HTTP 完成建实例 → 导入 YAML → 建策略 → 取生效配置。

- [ ] **Step 1: docker-compose.yml**

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: sqlmask_policy
      POSTGRES_USER: sqlmask
      POSTGRES_PASSWORD: sqlmask
    ports:
      - "5432:5432"

  policy:
    image: eclipse-temurin:17-jre
    working_dir: /app
    volumes:
      - ./mask-policy/target:/app:ro
    command: ["java", "-jar", "mask-policy.jar"]
    environment:
      POLICY_STORE: jdbc
      POLICY_SQL_INIT: always
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/sqlmask_policy
      SPRING_DATASOURCE_USERNAME: sqlmask
      SPRING_DATASOURCE_PASSWORD: sqlmask
      POLICY_SERVICE_ADMIN_KEY: admin-key
      POLICY_SERVICE_DATA_KEY: data-key
    ports:
      - "8081:8081"
    depends_on:
      - postgres
```

- [ ] **Step 2: 写条件端到端测试**

`PolicyServiceEndToEndIT`：`@EnabledIfEnvironmentVariable(named = "POLICY_E2E", matches = "1")`，用 `java.net.http.HttpClient` 对 `http://127.0.0.1:8081`（可用 `POLICY_E2E_BASE` 覆盖）依序：`POST /api/instances`（YAML 导入，Header `X-Api-Key: admin-key`）→ `GET /api/effective/{name}`（`X-Api-Key: data-key`）→ 断言编译产物含 rowFilter 与列绑定 → `PUT` 禁用一条策略 → 再取 effective 断言变化。测试自用随机实例名，结束时 DELETE 实例。

- [ ] **Step 3: 手动全链路验证（按 README 步骤执行一遍并记录结果）**

```bash
mvn package -DskipTests
docker compose up -d postgres
POLICY_STORE=jdbc POLICY_SQL_INIT=always \
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/sqlmask_policy \
SPRING_DATASOURCE_USERNAME=sqlmask SPRING_DATASOURCE_PASSWORD=sqlmask \
POLICY_SERVICE_ADMIN_KEY=admin-key POLICY_SERVICE_DATA_KEY=data-key \
java -jar mask-policy/target/mask-policy.jar &
curl -s -H "X-Api-Key: admin-key" localhost:8081/api/instances
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: docker-compose 与策略服务端到端验证"
```

---

### Task 10: README 与收尾验证

**Files:**
- Modify: `README.md`（新增「策略微服务」章节）

**Interfaces:** 无代码接口；文档必须覆盖 spec §7.1 的禁用策略语义与 §5 的接入配置。

- [ ] **Step 1: README 增补**

内容清单（逐项写入）：
- 架构小节：两个服务的职责与 `PolicyConfigProvider` 缓存轮询（30s，可配）示意图（沿用 spec §5.2 ASCII 图）；
- 策略微服务 API 速览（实例/策略 CRUD、`GET /api/effective/{instance}`、`/version`、API Key 环境变量表：`POLICY_SERVICE_ADMIN_KEY`、`POLICY_SERVICE_DATA_KEY`、`POLICY_STORE`、`POLICY_SQL_INIT`、数据源四项）；
- YAML 导入说明（现 YAML 一键建实例；rowFilter→row_filter 策略；跨表同名 policy 拆分规则）；
- **显式安全语义**：禁用策略 = 对应列不脱敏直通；`policySummary` 供监控；
- 改写端接入：`POST /api/rewrite` 的 `instance` 字段（与 `metadataYaml` 互斥）、CLI `--instance`/`--policy-service`、环境变量 `SQLMASK_POLICY_SERVICE`/`SQLMASK_POLICY_API_KEY`、`policy.service.*` 配置、`POST /admin/cache/refresh`；
- 错误码：`POLICY_SERVICE_UNAVAILABLE`、`POLICY_INSTANCE_NOT_FOUND`；
- 构建产物路径变化：`mask-core/target/sql-mask.jar`、`mask-policy/target/mask-policy.jar`、docker-compose 用法。

- [ ] **Step 2: 全量验证**

Run: `mvn clean test && mvn package -DskipTests`
Expected: 全绿；两个 jar 均产出。

Run: `java -jar mask-core/target/sql-mask.jar --metadata <样例YAML> --sql "SELECT 1"` 与 Task 9 的策略服务启动命令
Expected: 内联路径与实例路径行为均正常。

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "docs: README 增加策略微服务章节（API、接入、安全语义、部署）"
```

---

## 计划自评审记录（写完即查，随文修正）

1. **Spec 覆盖**：§3 模型 → Task 5/6/7；§4.1 管理 API+导入器 → Task 8；§4.2 数据面+编译 → Task 6/8；§5 接入（二选一/轮询/刷新端点/CLI）→ Task 2/3/4；§6 模块/存储/部署 → Task 1/7/9；§7 错误与安全 → Task 3/6/8（policySummary → Task 6）；§8 测试 → 各任务 + Task 9；README → Task 10。无缺口。
2. **占位符**：Task 5 `PolicyValidator`、Task 6 store/service/compiler、Task 7 JdbcPolicyStore、Task 8 controller 以「实现要点」给出关键逻辑与完整接口/测试，执行者按测试与接口补全——测试代码全部给全，接口签名全部给全，无 TBD/TODO。
3. **类型一致性**：`EffectiveConfigResponse` 嵌套 record 名（Task 2 定义 = Task 3 JSON = Task 6 compiler 产出 = Task 8 序列化）一致；`ResolvedConfig(config, dialect, configVersion)` 三处一致；`PolicyStore` 接口（Task 6 定义 = Task 7 实现 = Task 8 装配）一致；`PolicyEntity` 字段序（Task 5 定义 = Task 6/8 使用）一致；`RewriteRequest` 保留三参构造保证既有测试不破坏。
4. **已知风险**：Task 2 测试 `rejectsUnknownPolicyBinding` 的构造细节已在测试代码下方注明修正方式；`DialectProfiles.names()` 若不存在需在 core 补静态方法（Task 5 已注明与 DialectRegistry 同源）。
