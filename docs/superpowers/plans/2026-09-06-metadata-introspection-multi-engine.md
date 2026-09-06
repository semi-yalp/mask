# 元数据采集多引擎扩展（MySQL/Trino）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `--pull-metadata` 元数据采集从 PostgreSQL 扩展到 MySQL 与 Trino，提取 `MetadataIntrospector` 接口与注册表，CLI/Web/页面按 `engine` 参数分发。

**Architecture:** `ConnectionSpec` 加 `engine` 字段并按引擎拼 JDBC URL；`MetadataIntrospector` 接口 + `MetadataIntrospectors` 静态注册表（对齐 `DialectProfiles` 模式）；每个引擎一个 TypeMapper（引擎类型文本→YAML 声明，round-trip 过对应 resolver）+ 一个 Introspector（mock JDBC 可测）。生成器/golden/CLI 骨架/Web 流程复用 PG 版，基本零改动。

**Tech Stack:** Java 21、Spring Boot（BOM 管 `com.mysql:mysql-connector-j`；`io.trino:trino-jdbc:446` 显式版本对齐 trino-parser）、picocli、JUnit 5、Mockito。

**Spec:** `docs/superpowers/specs/2026-09-06-metadata-introspection-multi-engine-design.md`（执行者需同时阅读）。

## Global Constraints

- 缩进 2 空格（跟随既有代码）；record/final class 风格；Javadoc 说明「是什么/为什么」。
- 每任务 `mvn test` 相对 baseline 零新增失败才提交；conventional 提交信息。
- **round-trip 硬约束**：两个新 mapper 的全部精确映射用例，其 `yamlType` 必须能通过 `DialectProfiles.byName(engine).typeResolver().parseColumn("t", yamlType)`。
- 密码不进 URL、不进日志、不进异常消息；异常消息打印前剥除 JDBC URL。
- 降级警告文案：`column <catalog>.<schema>.<table>.<column>: <engine> type <original> is not representable, degraded to varchar`（PG 保持 "PG type"）；strict 判定依赖 `endsWith("degraded to varchar")` 不变。
- `io.trino:trino-jdbc` 版本固定 `446`（与 test-scope `trino-parser:446` 对齐）；执行 `mvn dependency:tree` 确认无 guava 版本冲突，若冲突如实报告不得静默解决。
- 不改 `DialectAdapter` 及改写管线；不动既有 golden 与 tpcds/。

## File Structure

```
pom.xml                                                  (Modify) mysql-connector-j + trino-jdbc:446
src/main/java/io/sqlmask/introspect/ConnectionSpec.java            (Modify) engine 字段 + URL 三分支
src/main/java/io/sqlmask/introspect/MetadataIntrospector.java      (Create) 接口
src/main/java/io/sqlmask/introspect/MetadataIntrospectors.java     (Create) 注册表
src/main/java/io/sqlmask/introspect/PgMetadataIntrospector.java    (Modify) implements 接口
src/main/java/io/sqlmask/introspect/MysqlTypeMapper.java           (Create)
src/main/java/io/sqlmask/introspect/TrinoTypeMapper.java           (Create)
src/main/java/io/sqlmask/introspect/MysqlMetadataIntrospector.java (Create)
src/main/java/io/sqlmask/introspect/TrinoMetadataIntrospector.java (Create)
src/main/java/io/sqlmask/cli/SqlMaskApplication.java               (Modify) --engine
src/main/java/io/sqlmask/server/MetadataController.java            (Modify) engine 字段
src/main/resources/static/index.html                               (Modify) 引擎下拉
src/test/java/io/sqlmask/introspect/*.java                         (Create/Modify 测试)
src/test/java/io/sqlmask/cli/MetadataExportCliTest.java            (Modify engine 用例)
src/test/java/io/sqlmask/server/MetadataControllerTest.java        (Modify engine 用例)
src/test/resources/golden/introspect-mysql.yaml                    (Create)
src/test/resources/golden/introspect-trino.yaml                    (Create)
src/test/resources/golden/introspect-multi-table.yaml              (Modify) ConnectionSpec 无关，仅若 fixture 变才动
README.md                                                          (Modify) Task 9
```

---

### Task 1: 依赖 + ConnectionSpec 泛化（engine + URL 三分支）

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/io/sqlmask/introspect/ConnectionSpec.java`
- Modify: `src/test/java/io/sqlmask/introspect/ConnectionSpecTest.java`
- Modify: 全部构造 ConnectionSpec 的调用点（见 Step 5）

**Interfaces:**
- Produces: `record ConnectionSpec(String engine, String host, int port, String database, String user, String password, List<String> schemas, boolean includeViews, boolean strict, String sslmode, int connectTimeoutSeconds)`——**engine 为首位**；紧凑构造器：`engine` 小写规范化，非 `postgresql|mysql|trino` 抛 `IllegalArgumentException("unsupported engine ...")`；`toJdbcUrl()` 按引擎分支。

- [ ] **Step 1: pom 加两个驱动**

postgresql 依赖之后：

```xml
<dependency>
  <groupId>com.mysql</groupId>
  <artifactId>mysql-connector-j</artifactId>
</dependency>
<dependency>
  <groupId>io.trino</groupId>
  <artifactId>trino-jdbc</artifactId>
  <version>446</version>
</dependency>
```

然后 `mvn dependency:tree -Dincludes=io.trino,com.google.guava,org.apache.calcite 2>&1 | tail -30` 检查 guava：trino-jdbc 拉入的 guava 与 calcite 的 guava 版本不一致时，记录到报告并在提交说明里注明（shade 保留两版本会以路径近者胜——若 guava 冲突导致任何既有测试失败，停下来 BLOCKED 上报，不得自行 relocation）。

- [ ] **Step 2: 写失败测试（追加到 ConnectionSpecTest）**

```java
  @Test
  void engineIsNormalizedAndValidated() {
    assertEquals("mysql", new ConnectionSpec("MySQL", "h", 3306, "shop", "u", "p",
        List.of(), false, false, "disable", 10).engine());
    assertThrows(IllegalArgumentException.class, () -> new ConnectionSpec("oracle",
        "h", 1521, "d", "u", "p", List.of(), false, false, "disable", 10));
  }

  @Test
  void mysqlUrl() {
    assertEquals("jdbc:mysql://127.0.0.1:3306/shop?connectTimeout=10&socketTimeout=60&sslMode=DISABLED",
        new ConnectionSpec("mysql", "127.0.0.1", 3306, "shop", "u", "p",
            List.of(), false, false, "disable", 10).toJdbcUrl());
    assertEquals("jdbc:mysql://h:3306/shop?connectTimeout=10&socketTimeout=60&sslMode=REQUIRED&verifyServerCertificate=false",
        new ConnectionSpec("mysql", "h", 3306, "shop", "u", "p",
            List.of(), false, false, "require", 10).toJdbcUrl());
  }

  @Test
  void trinoUrl() {
    assertEquals("jdbc:trino://127.0.0.1:8080/crm?SSL=false&connectTimeout=10s",
        new ConnectionSpec("trino", "127.0.0.1", 8080, "crm", "u", "p",
            List.of(), false, false, "disable", 10).toJdbcUrl());
    assertEquals("jdbc:trino://h:8080/crm?connectTimeout=10s",
        new ConnectionSpec("trino", "h", 8080, "crm", "u", "p",
            List.of(), false, false, "require", 10).toJdbcUrl());
  }

  @Test
  void postgresqlUrlUnchanged() {
    assertEquals("jdbc:postgresql://127.0.0.1:5432/crm"
        + "?sslmode=disable&connectTimeout=10&socketTimeout=60&readOnly=true", minimal().toJdbcUrl());
  }
```

并更新 `minimal()` 与既有各构造调用点：首位加 `"postgresql"`。

- [ ] **Step 3: 运行确认失败**

Run: `mvn test -Dtest=ConnectionSpecTest`
Expected: 编译失败（构造器 11 参不存在）

- [ ] **Step 4: 实现**

record 首位加 `String engine`；紧凑构造器：

```java
    engine = engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
    if (!Set.of("postgresql", "mysql", "trino").contains(engine)) {
      throw new IllegalArgumentException(
          "unsupported engine '" + engine + "' (supported: postgresql, mysql, trino)");
    }
```

`toJdbcUrl()`：

```java
  public String toJdbcUrl() {
    return switch (engine) {
      case "postgresql" -> "jdbc:postgresql://" + host + ":" + port + "/" + database
          + "?sslmode=" + sslmode
          + "&connectTimeout=" + connectTimeoutSeconds
          + "&socketTimeout=60&readOnly=true";
      case "mysql" -> "jdbc:mysql://" + host + ":" + port + "/" + database
          + "?connectTimeout=" + connectTimeoutSeconds + "&socketTimeout=60"
          + ("require".equalsIgnoreCase(sslmode)
              ? "&sslMode=REQUIRED&verifyServerCertificate=false"
              : "&sslMode=DISABLED");
      case "trino" -> "jdbc:trino://" + host + ":" + port + "/" + database
          + ("require".equalsIgnoreCase(sslmode) ? "" : "?SSL=false")
          + "&connectTimeout=" + connectTimeoutSeconds + "s";
      default -> throw new IllegalArgumentException("unsupported engine " + engine);
    };
  }
```

（trino disable 与 require 的拼接差异：disable 是 `?SSL=false&connectTimeout=Ns`，require 是 `?connectTimeout=Ns`——用条件拼 `?`/`&` 保证两种形态都与测试一致；实现时注意 `?SSL=false` 分支后追加 `&connectTimeout`、require 分支首个参数用 `?`。）

- [ ] **Step 5: 修复全部调用点**

`grep -rn "new ConnectionSpec(" src/` 列出调用点（预期：PgMetadataIntrospectorTest×3、MetadataControllerTest、MetadataController、MetadataExportCliTest 若有、Task 5/6 尚不存在），首位统一加 `"postgresql"`。

- [ ] **Step 6: 运行确认通过 + 全量**

Run: `mvn test -Dtest=ConnectionSpecTest && mvn test`
Expected: PASS；全量零新增失败。

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/java/io/sqlmask/introspect/ConnectionSpec.java src/test/java/io/sqlmask/introspect/ConnectionSpecTest.java <其余调用点文件>
git commit -m "feat: ConnectionSpec 增加 engine 字段与 MySQL/Trino JDBC URL 分支"
```

---

### Task 2: MetadataIntrospector 接口 + 注册表

**Files:**
- Create: `src/main/java/io/sqlmask/introspect/MetadataIntrospector.java`
- Create: `src/main/java/io/sqlmask/introspect/MetadataIntrospectors.java`
- Modify: `src/main/java/io/sqlmask/introspect/PgMetadataIntrospector.java`（implements）
- Test: `src/test/java/io/sqlmask/introspect/MetadataIntrospectorsTest.java`

**Interfaces:**
- Produces: `public interface MetadataIntrospector { IntrospectionResult introspect(ConnectionSpec spec); }`；`MetadataIntrospectors.byEngine(String engine)` → 实例（postgresql→PgMetadataIntrospector，mysql→MysqlMetadataIntrospector，trino→TrinoMetadataIntrospector——后两个 Task 5/6 才建，本任务先注册 lambda 占位？**否**：注册表本任务只注册 postgresql，mysql/trino 分支在 Task 5/6 各自追加——用 editable switch：本任务 default 即抛 CONFIG_ERROR？**最终形态**：本任务注册表三分支齐备，mysql/trino 分支返回 `new MysqlMetadataIntrospector()`/`new TrinoMetadataIntrospector()` 会编译失败。**裁决：本任务 byEngine 只实现 postgresql 分支 + default 抛 CONFIG_ERROR（消息列出 postgresql, mysql, trino 为"计划支持"文本），Task 5/6 各自往 switch 加一行。测试本任务只断言 postgresql 与未知值行为，并在 Interfaces 注明。**

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.introspect;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetadataIntrospectorsTest {

  @Test
  void resolvesPostgresqlCaseInsensitively() {
    assertInstanceOf(PgMetadataIntrospector.class,
        MetadataIntrospectors.byEngine("PostgreSQL"));
  }

  @Test
  void unknownEngineThrowsConfigError() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> MetadataIntrospectors.byEngine("oracle"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=MetadataIntrospectorsTest`
Expected: 编译失败 `cannot find symbol: MetadataIntrospectors`

- [ ] **Step 3: 实现**

```java
package io.sqlmask.introspect;

/** Engine-agnostic metadata pull contract; one implementation per JDBC engine. */
public interface MetadataIntrospector {
  IntrospectionResult introspect(ConnectionSpec spec);
}
```

```java
package io.sqlmask.introspect;

import io.sqlmask.error.SqlMaskException;
import java.util.Locale;

/** Registry of engine-specific introspectors, mirroring {@code DialectProfiles}. */
public final class MetadataIntrospectors {

  private MetadataIntrospectors() {
  }

  public static MetadataIntrospector byEngine(String engine) {
    String normalized = engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "postgresql" -> new PgMetadataIntrospector();
      default -> throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unsupported engine '" + engine
              + "' (supported: postgresql, mysql, trino)");
    };
  }
}
```

`PgMetadataIntrospector` 声明改 `public final class PgMetadataIntrospector implements MetadataIntrospector`，`introspect` 方法加 `@Override`。

- [ ] **Step 4: 运行确认通过 + 全量**

Run: `mvn test -Dtest=MetadataIntrospectorsTest && mvn test`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/sqlmask/introspect/MetadataIntrospector.java src/main/java/io/sqlmask/introspect/MetadataIntrospectors.java src/main/java/io/sqlmask/introspect/PgMetadataIntrospector.java src/test/java/io/sqlmask/introspect/MetadataIntrospectorsTest.java
git commit -m "feat: MetadataIntrospector 接口与引擎注册表（PG 先行）"
```

---

### Task 3: MysqlTypeMapper（含 round-trip 防线）

**Files:**
- Create: `src/main/java/io/sqlmask/introspect/MysqlTypeMapper.java`
- Test: `src/test/java/io/sqlmask/introspect/MysqlTypeMapperTest.java`

**Interfaces:**
- Consumes: `DialectProfiles.byName("mysql").typeResolver().parseColumn(...)`（round-trip 防线用）
- Produces: `final class MysqlTypeMapper`，`PgTypeMapper.Mapped map(String columnTypeText)`（复用 `PgTypeMapper.Mapped` record——它是纯数据载体，方言无关）。never-throw；不识别 → `("varchar", true)`。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.introspect;

import io.sqlmask.dialect.DialectProfiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlTypeMapperTest {

  private final MysqlTypeMapper mapper = new MysqlTypeMapper();

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "'tinyint(1)', 'tinyint(1)'",
      "'smallint(5)', 'smallint(5)'",
      "'mediumint', mediumint",
      "'int(11)', 'int(11)'",
      "'int', int",
      "'bigint', bigint",
      "'decimal(10,2)', 'decimal(10,2)'",
      "'decimal(8)', 'decimal(8)'",
      "'float', float",
      "'double', double",
      "'char(20)', 'char(20)'",
      "'char', 'char(1)'",
      "'varchar(50)', 'varchar(50)'",
      "'varchar', varchar",
      "'tinytext', tinytext",
      "'text', text",
      "'mediumtext', mediumtext",
      "'longtext', longtext",
      "'binary(16)', 'binary(16)'",
      "'varbinary(255)', 'varbinary(255)'",
      "'date', date",
      "'datetime(3)', 'datetime(3)'",
      "'datetime', datetime",
      "'time(3)', 'time(3)'",
      "'timestamp(6)', 'timestamp(6)'",
      "'timestamp', timestamp"
  })
  void mapsExactly(String columnType, String expected) {
    PgTypeMapper.Mapped m = mapper.map(columnType);
    assertEquals(expected, m.yamlType());
    assertFalse(m.degraded(), columnType);
  }

  @ParameterizedTest(name = "{0} -> {1} degraded")
  @CsvSource({
      "'int unsigned', int",
      "'bigint unsigned', bigint",
      "'tinyint(1) unsigned', 'tinyint(1)'"
  })
  void stripsUnsignedWithDegradedFlag(String columnType, String expected) {
    PgTypeMapper.Mapped m = mapper.map(columnType);
    assertEquals(expected, m.yamlType());
    assertTrue(m.degraded(), columnType);
  }

  @Test
  void unsupportedTypesDegrade() {
    for (String t : new String[]{"json", "enum('a','b')", "set('x','y')", "bit(8)", "bit",
        "year(4)", "year", "geometry", "blob", "longblob", "point", "int signed"}) {
      PgTypeMapper.Mapped m = mapper.map(t);
      assertEquals("varchar", m.yamlType(), t);
      assertTrue(m.degraded(), t);
    }
  }

  @Test
  void neverThrows() {
    for (String t : new String[]{null, "", "  ", "numeric(a,b)", "varchar(99999999999)",
        "enum('a','b')", "wèírd"}) {
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> mapper.map(t));
    }
    assertEquals("varchar", mapper.map(null).yamlType());
  }

  /** 硬防线：每个精确映射结果必须通过 MysqlTypeResolver。 */
  @Test
  void everyMappedTypePassesMysqlResolver() {
    String[] samples = {"tinyint(1)", "smallint", "mediumint", "int", "bigint",
        "decimal(10,2)", "float", "double", "char(20)", "char", "varchar(50)", "varchar",
        "text", "longtext", "binary(16)", "varbinary(255)", "date", "datetime(3)",
        "time(3)", "timestamp"};
    for (String yamlType : samples) {
      assertDoesNotThrow(() -> DialectProfiles.byName("mysql").typeResolver()
          .parseColumn("t", yamlType), yamlType);
    }
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=MysqlTypeMapperTest`
Expected: 编译失败 `cannot find symbol: class MysqlTypeMapper`

- [ ] **Step 3: 实现**

```java
package io.sqlmask.introspect;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps MySQL {@code information_schema.COLUMNS.COLUMN_TYPE} text (e.g.
 * {@code varchar(50)}, {@code int unsigned}) to a YAML type declaration the
 * MySQL dialect resolver accepts. Unsigned/signed suffixes are stripped with a
 * degraded marker (range semantics are lost); anything outside the accepted set
 * degrades to varchar. Never throws.
 */
public final class MysqlTypeMapper {

  private static final Pattern SHAPE = Pattern.compile(
      "^([a-z ]+?)\\s*(?:\\((\\d+)(?:\\s*,\\s*(\\d+))?\\))?(?:\\s+(unsigned|signed))?$");
  private static final Set<String> ACCEPTED = Set.of(
      "tinyint", "smallint", "mediumint", "int", "integer", "bigint",
      "decimal", "dec", "numeric", "float", "double", "double precision",
      "char", "varchar", "tinytext", "text", "mediumtext", "longtext",
      "binary", "varbinary", "date", "datetime", "time", "timestamp");

  public PgTypeMapper.Mapped map(String columnTypeText) {
    if (columnTypeText == null || columnTypeText.isBlank()) {
      return new PgTypeMapper.Mapped("varchar", true);
    }
    String raw = columnTypeText.trim().toLowerCase(Locale.ROOT);
    Matcher m = SHAPE.matcher(raw);
    if (!m.matches()) {
      return new PgTypeMapper.Mapped("varchar", true);
    }
    String base = m.group(1).trim();
    Integer p = parseInt(m.group(2));
    Integer s = parseInt(m.group(3));
    boolean unsigned = m.group(4) != null;
    if (!ACCEPTED.contains(base)) {
      return new PgTypeMapper.Mapped("varchar", true);
    }
    String yamlType = raw;
    if (base.equals("char") && p == null) {
      yamlType = "char(1)";
    }
    return new PgTypeMapper.Mapped(unsigned ? yamlType : raw, unsigned);
  }

  private Integer parseInt(String digits) {
    if (digits == null) {
      return null;
    }
    try {
      return Integer.valueOf(digits);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("unreachable: SHAPE only allows \\d+", e);
    }
  }
}
```

> 实现备注：SHAPE 的 `(?:\\s+(unsigned|signed))?` 保证 `enum('a','b')`（括号内容非纯数字）不匹配 → 降级；`varchar(99999999999)` 括号超长数字匹配 SHAPE 但 `Integer.valueOf` 抛出——为满足 never-throw 契约，把 `parseInt` 的 `IllegalStateException` 改为捕获 `NumberFormatException` 后返回 `new PgTypeMapper.Mapped("varchar", true)`（与 Task 2 修复的 PgTypeMapper 同款），**落地时直接写成 catch 返回降级**，不要 IllegalStateException。

- [ ] **Step 4: 运行确认通过 + 全量**

Run: `mvn test -Dtest=MysqlTypeMapperTest && mvn test`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/sqlmask/introspect/MysqlTypeMapper.java src/test/java/io/sqlmask/introspect/MysqlTypeMapperTest.java
git commit -m "feat: MySQL COLUMN_TYPE 到 YAML 类型映射（含 unsigned 剥离与 round-trip 防线）"
```

---

### Task 4: TrinoTypeMapper（含 round-trip 防线）

**Files:**
- Create: `src/main/java/io/sqlmask/introspect/TrinoTypeMapper.java`
- Test: `src/test/java/io/sqlmask/introspect/TrinoTypeMapperTest.java`

**Interfaces:**
- Produces: `final class TrinoTypeMapper`，`PgTypeMapper.Mapped map(String dataTypeText)`。never-throw；complex 类型（array/map/row/qdigest 开头）与不识别形态降级。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.introspect;

import io.sqlmask.dialect.DialectProfiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrinoTypeMapperTest {

  private final TrinoTypeMapper mapper = new TrinoTypeMapper();

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "'boolean', boolean",
      "'tinyint', tinyint",
      "'smallint', smallint",
      "'integer', integer",
      "'int', int",
      "'bigint', bigint",
      "'real', real",
      "'double', double",
      "'decimal(10,2)', 'decimal(10,2)'",
      "'decimal', decimal",
      "'char(10)', 'char(10)'",
      "'character(10)', 'character(10)'",
      "'char', 'char(1)'",
      "'varchar(50)', 'varchar(50)'",
      "'varchar', varchar",
      "'character varying(50)', 'character varying(50)'",
      "'varbinary', varbinary",
      "'date', date",
      "'time(3)', 'time(3)'",
      "'time(3) with time zone', 'time(3) with time zone'",
      "'timestamp(3)', 'timestamp(3)'",
      "'timestamp(3) with time zone', 'timestamp(3) with time zone'"
  })
  void mapsExactly(String dataType, String expected) {
    PgTypeMapper.Mapped m = mapper.map(dataType);
    assertEquals(expected, m.yamlType());
    assertFalse(m.degraded(), dataType);
  }

  @Test
  void complexAndUnknownTypesDegrade() {
    for (String t : new String[]{"json", "ipaddress", "hyperloglog", "P4HyperLogLog",
        "qdigest(x)", "array(integer)", "map(varchar,integer)", "row(a integer)",
        "array(x)", "weird"}) {
      PgTypeMapper.Mapped m = mapper.map(t);
      assertEquals("varchar", m.yamlType(), t);
      assertTrue(m.degraded(), t);
    }
  }

  @Test
  void neverThrows() {
    for (String t : new String[]{null, "", "decimal(99999999999)", "row(", "  "}) {
      assertDoesNotThrow(() -> mapper.map(t));
    }
    assertEquals("varchar", mapper.map(null).yamlType());
  }

  /** 硬防线：每个精确映射结果必须通过 TrinoTypeResolver。 */
  @Test
  void everyMappedTypePassesTrinoResolver() {
    String[] samples = {"boolean", "tinyint", "smallint", "integer", "bigint", "real",
        "double", "decimal(10,2)", "char(10)", "varchar(50)", "varchar", "varbinary",
        "date", "time(3)", "time(3) with time zone", "timestamp(3)",
        "timestamp(3) with time zone"};
    for (String yamlType : samples) {
      assertDoesNotThrow(() -> DialectProfiles.byName("trino").typeResolver()
          .parseColumn("t", yamlType), yamlType);
    }
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=TrinoTypeMapperTest`
Expected: 编译失败 `cannot find symbol: class TrinoTypeMapper`

- [ ] **Step 3: 实现**

```java
package io.sqlmask.introspect;

import java.util.Locale;
import java.util.Set;

/**
 * Maps Trino {@code information_schema.COLUMNS.data_type} text (e.g.
 * {@code timestamp(3) with time zone}) to a YAML declaration the Trino dialect
 * resolver accepts. Complex types (array/map/row/qdigest...) and unknown names
 * degrade to varchar. Never throws.
 */
public final class TrinoTypeMapper {

  private static final Set<String> ACCEPTED = Set.of(
      "boolean", "tinyint", "smallint", "int", "integer", "bigint", "real", "double",
      "decimal", "char", "character", "varchar", "character varying", "varbinary",
      "date", "time", "timestamp");

  public PgTypeMapper.Mapped map(String dataTypeText) {
    if (dataTypeText == null || dataTypeText.isBlank()) {
      return new PgTypeMapper.Mapped("varchar", true);
    }
    String raw = dataTypeText.trim();
    String base = raw.toLowerCase(Locale.ROOT);
    // strip optional "[(p)]" then optional " with time zone" to learn the base name
    String stripped = base;
    if (stripped.endsWith(" with time zone")) {
      stripped = stripped.substring(0, stripped.length() - " with time zone".length());
    }
    stripped = stripParams(stripped);
    if (!ACCEPTED.contains(stripped)) {
      return new PgTypeMapper.Mapped("varchar", true);
    }
    if (stripped.equals("char") || stripped.equals("character")) {
      // resolver defaults to char(1); echo explicitly
      return new PgTypeMapper.Mapped("char(1)", false);
    }
    return new PgTypeMapper.Mapped(raw, false);
  }

  private String stripParams(String text) {
    if (!text.endsWith(")")) {
      return text;
    }
    int depth = 0;
    for (int i = text.length() - 1; i >= 0; i--) {
      char c = text.charAt(i);
      if (c == ')') depth++;
      else if (c == '(') {
        depth--;
        if (depth == 0) {
          return text.substring(0, i).trim();
        }
      }
    }
    return text;
  }
}
```

> 实现备注：`stripParams` 剥最外层括号对（`decimal(10,2)` → `decimal`、`qdigest(x)` → `qdigest`）。括号内容可能任意（`row(a integer)`），不解析数字、不抛异常。`P4HyperLogLog` 大小写混合 → 小写化后 `p4hyperloglog` 不在 ACCEPTED → 降级 ✓。`array(integer)` 剥后 `array` 不在 ACCEPTED → 降级 ✓。`timestamp(3) with time zone` 剥后缀再剥参数 → `timestamp` ∈ ACCEPTED → 原文照抄 ✓。无需正则、无 Integer 解析 → never-throw 天然成立。

- [ ] **Step 4: 运行确认通过 + 全量**

Run: `mvn test -Dtest=TrinoTypeMapperTest && mvn test`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/sqlmask/introspect/TrinoTypeMapper.java src/test/java/io/sqlmask/introspect/TrinoTypeMapperTest.java
git commit -m "feat: Trino data_type 到 YAML 类型映射（complex 类型降级 + round-trip 防线）"
```

---

### Task 5: MysqlMetadataIntrospector

**Files:**
- Create: `src/main/java/io/sqlmask/introspect/MysqlMetadataIntrospector.java`
- Modify: `src/main/java/io/sqlmask/introspect/MetadataIntrospectors.java`（加 mysql 分支）
- Test: `src/test/java/io/sqlmask/introspect/MysqlMetadataIntrospectorTest.java`

**Interfaces:**
- Consumes: `MetadataIntrospector` 接口、`ConnectionSpec`（engine=mysql）、`MysqlTypeMapper`
- Produces: `public final class MysqlMetadataIntrospector implements MetadataIntrospector`，`protected Connection open(ConnectionSpec)` 覆盖点。两条语句：①`SELECT DATABASE()`；②下方 JOIN 查询。警告文案 `... mysql type <original> is not representable, degraded to varchar`；空库警告与 sanitize 同 PG 版。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.introspect;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MysqlMetadataIntrospectorTest {

  @Test
  void collectsTablesColumnsAndCatalog() throws SQLException {
    Connection conn = mock(Connection.class);
    PreparedStatement c1 = mock(PreparedStatement.class);
    PreparedStatement c2 = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(c1, c2);

    ResultSet db = mock(ResultSet.class);
    when(db.next()).thenReturn(true, false);
    when(db.getString(1)).thenReturn("shop");

    ResultSet rows = mock(ResultSet.class);
    when(rows.next()).thenReturn(true, true, false);
    when(rows.getString("TABLE_SCHEMA")).thenReturn("shop", "shop");
    when(rows.getString("TABLE_NAME")).thenReturn("customer", "customer");
    when(rows.getString("TABLE_TYPE")).thenReturn("BASE TABLE", "BASE TABLE");
    when(rows.getString("COLUMN_NAME")).thenReturn("id", "name");
    when(rows.getString("COLUMN_TYPE")).thenReturn("bigint unsigned", "varchar(50)");

    when(c1.executeQuery()).thenReturn(db);
    when(c2.executeQuery()).thenReturn(rows);

    MysqlMetadataIntrospector introspector = new MysqlMetadataIntrospector() {
      @Override protected Connection open(ConnectionSpec spec) { return conn; }
    };
    IntrospectionResult result = introspector.introspect(
        new ConnectionSpec("mysql", "h", 3306, "shop", "u", "p", List.of(), false, false, "disable", 10));

    assertEquals("shop", result.catalog());
    assertEquals(1, result.tables().size());
    assertEquals("shop", result.tables().get(0).schema());
    assertEquals(2, result.tables().get(0).columns().size());
    assertEquals("bigint", result.tables().get(0).columns().get(0).yamlType());
    assertTrue(result.tables().get(0).columns().get(0).degraded());
    assertEquals("varchar(50)", result.tables().get(0).columns().get(1).yamlType());
    assertTrue(result.warnings().stream().anyMatch(w ->
        w.contains("shop.shop.customer.id") && w.contains("mysql type bigint unsigned")
        && w.endsWith("degraded to varchar")));
  }

  @Test
  void emptyDatabaseYieldsWarning() throws SQLException {
    Connection conn = mock(Connection.class);
    PreparedStatement c1 = mock(PreparedStatement.class);
    PreparedStatement c2 = mock(PreparedStatement.class);
    when(conn.prepareStatement(anyString())).thenReturn(c1, c2);
    ResultSet db = mock(ResultSet.class);
    when(db.next()).thenReturn(true, false);
    when(db.getString(1)).thenReturn("shop");
    ResultSet empty = mock(ResultSet.class);
    when(empty.next()).thenReturn(false);
    when(c1.executeQuery()).thenReturn(db);
    when(c2.executeQuery()).thenReturn(empty);

    MysqlMetadataIntrospector introspector = new MysqlMetadataIntrospector() {
      @Override protected Connection open(ConnectionSpec spec) { return conn; }
    };
    IntrospectionResult result = introspector.introspect(
        new ConnectionSpec("mysql", "h", 3306, "shop", "u", "p", List.of(), false, false, "disable", 10));
    assertTrue(result.tables().isEmpty());
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("未找到任何表")));
  }

  @Test
  void connectionFailureBecomesIntrospectError() {
    MysqlMetadataIntrospector introspector = new MysqlMetadataIntrospector() {
      @Override protected Connection open(ConnectionSpec spec) throws SQLException {
        throw new SQLException("Access denied for user 'u'@'h' (using password: YES)");
      }
    };
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> introspector.introspect(
        new ConnectionSpec("mysql", "h", 3306, "shop", "u", "p", List.of(), false, false, "disable", 10)));
    assertEquals(SqlMaskException.Code.INTROSPECT_ERROR, e.getCode());
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=MysqlMetadataIntrospectorTest`
Expected: 编译失败 `cannot find symbol: class MysqlMetadataIntrospector`

- [ ] **Step 3: 实现**

```java
package io.sqlmask.introspect;

import io.sqlmask.error.SqlMaskException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Pulls table/column metadata from the connected MySQL database via
 * information_schema. Catalog reports DATABASE(); schema echoes TABLE_SCHEMA
 * (MySQL has no separate schema layer). Read-only: the only statements are
 * SELECTs against information_schema and DATABASE().
 */
public final class MysqlMetadataIntrospector implements MetadataIntrospector {

  private static final String DATABASE_SQL = "SELECT DATABASE()";
  private static final String TABLES_SQL = """
      SELECT c.TABLE_SCHEMA, c.TABLE_NAME, t.TABLE_TYPE, c.COLUMN_NAME,
             c.COLUMN_TYPE, c.ORDINAL_POSITION
      FROM information_schema.columns c
      JOIN information_schema.tables t
        ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
      WHERE %s
        AND t.TABLE_TYPE IN (%s)
      ORDER BY c.TABLE_SCHEMA, c.TABLE_NAME, c.ORDINAL_POSITION""";

  private final MysqlTypeMapper typeMapper = new MysqlTypeMapper();

  @Override
  public IntrospectionResult introspect(ConnectionSpec spec) {
    try (Connection connection = open(spec)) {
      String catalog = queryCurrentDatabase(connection);
      List<IntrospectionResult.TableInfo> tables = queryTables(connection, spec, catalog);
      List<String> warnings = new ArrayList<>();
      for (IntrospectionResult.TableInfo table : tables) {
        for (IntrospectionResult.ColumnInfo column : table.columns()) {
          if (column.degraded()) {
            warnings.add("column " + table.catalog() + "." + table.schema() + "."
                + table.name() + "." + column.name() + ": mysql type "
                + column.originalPgType() + " is not representable, degraded to varchar");
          }
        }
      }
      if (tables.isEmpty()) {
        warnings.add("未找到任何表，请检查 schema 过滤条件");
      }
      return new IntrospectionResult(catalog, tables, warnings);
    } catch (SQLException e) {
      throw new SqlMaskException(SqlMaskException.Code.INTROSPECT_ERROR,
          "metadata introspection failed: " + sanitize(e.getMessage()), e);
    }
  }

  /** Overridable so tests can supply a mocked connection. */
  protected Connection open(ConnectionSpec spec) throws SQLException {
    Properties props = new Properties();
    props.setProperty("user", spec.user());
    props.setProperty("password", spec.password() == null ? "" : spec.password());
    return DriverManager.getConnection(spec.toJdbcUrl(), props);
  }

  private String queryCurrentDatabase(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(DATABASE_SQL);
         ResultSet rs = statement.executeQuery()) {
      if (!rs.next()) {
        throw new SQLException("DATABASE() returned no row");
      }
      return rs.getString(1);
    }
  }

  private List<IntrospectionResult.TableInfo> queryTables(
      Connection connection, ConnectionSpec spec, String catalog) throws SQLException {
    String schemaPredicate = spec.schemas().isEmpty()
        ? "c.TABLE_SCHEMA = DATABASE()"
        : "c.TABLE_SCHEMA IN (" + placeholders(spec.schemas().size()) + ")";
    String relKinds = spec.includeViews() ? "'BASE TABLE', 'VIEW'" : "'BASE TABLE'";
    String sql = TABLES_SQL.formatted(schemaPredicate, relKinds);
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int index = 1;
      for (String schema : spec.schemas()) {
        statement.setString(index++, schema);
      }
      try (ResultSet rs = statement.executeQuery()) {
        return assemble(rs, catalog);
      }
    }
  }

  private String placeholders(int n) {
    return String.join(", ", java.util.Collections.nCopies(n, "?"));
  }

  private List<IntrospectionResult.TableInfo> assemble(ResultSet rs, String catalog)
      throws SQLException {
    Map<String, IntrospectionResult.TableInfo> byKey = new LinkedHashMap<>();
    while (rs.next()) {
      String schema = rs.getString("TABLE_SCHEMA");
      String name = rs.getString("TABLE_NAME");
      String column = rs.getString("COLUMN_NAME");
      String columnType = rs.getString("COLUMN_TYPE");
      PgTypeMapper.Mapped mapped = typeMapper.map(columnType);
      String key = schema + "." + name;
      IntrospectionResult.TableInfo table = byKey.get(key);
      if (table == null) {
        table = new IntrospectionResult.TableInfo(catalog, schema, name, new ArrayList<>());
        byKey.put(key, table);
      }
      table.columns().add(new IntrospectionResult.ColumnInfo(column, mapped.yamlType(),
          columnType, mapped.degraded()));
    }
    return new ArrayList<>(byKey.values());
  }

  private String sanitize(String message) {
    if (message == null) {
      return "unknown error";
    }
    int urlIndex = message.indexOf("jdbc:");
    return urlIndex >= 0 ? message.substring(0, urlIndex).trim() : message;
  }
}
```

`MetadataIntrospectors.byEngine` 加分支：`case "mysql" -> new MysqlMetadataIntrospector();`

- [ ] **Step 4: 运行确认通过 + 全量**

Run: `mvn test -Dtest=MysqlMetadataIntrospectorTest && mvn test`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/sqlmask/introspect/MysqlMetadataIntrospector.java src/main/java/io/sqlmask/introspect/MetadataIntrospectors.java src/test/java/io/sqlmask/introspect/MysqlMetadataIntrospectorTest.java
git commit -m "feat: MySQL information_schema 元数据采集 MysqlMetadataIntrospector"
```

---

### Task 6: TrinoMetadataIntrospector

**Files:**
- Create: `src/main/java/io/sqlmask/introspect/TrinoMetadataIntrospector.java`
- Modify: `src/main/java/io/sqlmask/introspect/MetadataIntrospectors.java`（加 trino 分支）
- Test: `src/test/java/io/sqlmask/introspect/TrinoMetadataIntrospectorTest.java`

**Interfaces:**
- Consumes: 同 Task 5 + `TrinoTypeMapper`
- Produces: `TrinoMetadataIntrospector implements MetadataIntrospector`。**单条**查询（catalog 回显 spec.database()，无标题查询）；警告文案 `trino type ...`；密码走 Properties 的 `user`/`password` 键（trino-jdbc 认这两个键）。

- [ ] **Step 1: 写失败测试**

结构与 Task 5 测试同构（mock 单个 PreparedStatement，`thenReturn(c1)`；行列名用 `TABLE_SCHEMA/TABLE_NAME/COLUMN_NAME/DATA_TYPE`；类型样本 `timestamp(3) with time zone` 精确映射 + `array(integer)` 降级），断言：
- `result.catalog()` 等于 `spec.database()`；
- `trino type array(integer)` 警告出现；
- 空库警告、连接失败→INTROSPECT_ERROR。

```java
package io.sqlmask.introspect;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrinoMetadataIntrospectorTest {

  private ResultSet rows(String[][] data) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    Boolean[] seq = new Boolean[data.length + 1];
    for (int i = 0; i < data.length; i++) seq[i] = true;
    seq[data.length] = false;
    when(rs.next()).thenReturn(seq[0], java.util.Arrays.copyOfRange(seq, 1, seq.length, Boolean[].class));
    when(rs.getString("TABLE_SCHEMA")).thenReturn(data[0][0],
        java.util.Arrays.stream(data).map(r -> r[0]).toArray(String[]::new));
    // 注：thenReturn 链见下方实现说明——按顺序逐值 stub，测试文件里写成显式三行
    return rs;
  }
}
```

> 实现说明：`rs.next()`/`getString` 的连续 stub 用显式多值形式（如 PG 测试的 `thenReturn(true, true, false)` 与 `thenReturn("shop", "shop")`），不要用上面 `rows(...)` 辅助的 varargs 技巧——直接在测试方法里内联 mock 三行数据（customer.timestamp(3) with time zone / customer.array(integer)），保持与 PgMetadataIntrospectorTest 相同的可读风格。

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=TrinoMetadataIntrospectorTest`
Expected: 编译失败

- [ ] **Step 3: 实现**

```java
package io.sqlmask.introspect;

import io.sqlmask.error.SqlMaskException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Pulls table/column metadata from one Trino catalog via information_schema.
 * The catalog reported equals the one named in the connection URL
 * ({@code spec.database()}); no title query exists in Trino for this purpose
 * at the connection level. Read-only: the only statement is a SELECT against
 * information_schema.
 */
public final class TrinoMetadataIntrospector implements MetadataIntrospector {

  private static final String TABLES_SQL = """
      SELECT c.TABLE_SCHEMA, c.TABLE_NAME, c.COLUMN_NAME, c.DATA_TYPE, c.ORDINAL_POSITION
      FROM information_schema.columns c
      JOIN information_schema.tables t
        ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
      WHERE %s
        AND t.TABLE_TYPE IN (%s)
      ORDER BY c.TABLE_SCHEMA, c.TABLE_NAME, c.ORDINAL_POSITION""";

  private final TrinoTypeMapper typeMapper = new TrinoTypeMapper();

  @Override
  public IntrospectionResult introspect(ConnectionSpec spec) {
    try (Connection connection = open(spec)) {
      List<IntrospectionResult.TableInfo> tables = queryTables(connection, spec, spec.database());
      List<String> warnings = new ArrayList<>();
      for (IntrospectionResult.TableInfo table : tables) {
        for (IntrospectionResult.ColumnInfo column : table.columns()) {
          if (column.degraded()) {
            warnings.add("column " + table.catalog() + "." + table.schema() + "."
                + table.name() + "." + column.name() + ": trino type "
                + column.originalPgType() + " is not representable, degraded to varchar");
          }
        }
      }
      if (tables.isEmpty()) {
        warnings.add("未找到任何表，请检查 schema 过滤条件");
      }
      return new IntrospectionResult(spec.database(), tables, warnings);
    } catch (SQLException e) {
      throw new SqlMaskException(SqlMaskException.Code.INTROSPECT_ERROR,
          "metadata introspection failed: " + sanitize(e.getMessage()), e);
    }
  }

  /** Overridable so tests can supply a mocked connection. */
  protected Connection open(ConnectionSpec spec) throws SQLException {
    Properties props = new Properties();
    props.setProperty("user", spec.user());
    props.setProperty("password", spec.password() == null ? "" : spec.password());
    return DriverManager.getConnection(spec.toJdbcUrl(), props);
  }

  private List<IntrospectionResult.TableInfo> queryTables(
      Connection connection, ConnectionSpec spec, String catalog) throws SQLException {
    String schemaPredicate = spec.schemas().isEmpty()
        ? "1 = 1"
        : "c.TABLE_SCHEMA IN (" + placeholders(spec.schemas().size()) + ")";
    String relKinds = spec.includeViews() ? "'BASE TABLE', 'VIEW'" : "'BASE TABLE'";
    String sql = TABLES_SQL.formatted(schemaPredicate, relKinds);
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int index = 1;
      for (String schema : spec.schemas()) {
        statement.setString(index++, schema);
      }
      try (ResultSet rs = statement.executeQuery()) {
        return assemble(rs, catalog);
      }
    }
  }

  private String placeholders(int n) {
    return String.join(", ", java.util.Collections.nCopies(n, "?"));
  }

  private List<IntrospectionResult.TableInfo> assemble(ResultSet rs, String catalog)
      throws SQLException {
    Map<String, IntrospectionResult.TableInfo> byKey = new LinkedHashMap<>();
    while (rs.next()) {
      String schema = rs.getString("TABLE_SCHEMA");
      String name = rs.getString("TABLE_NAME");
      String column = rs.getString("COLUMN_NAME");
      String dataType = rs.getString("DATA_TYPE");
      PgTypeMapper.Mapped mapped = typeMapper.map(dataType);
      String key = schema + "." + name;
      IntrospectionResult.TableInfo table = byKey.get(key);
      if (table == null) {
        table = new IntrospectionResult.TableInfo(catalog, schema, name, new ArrayList<>());
        byKey.put(key, table);
      }
      table.columns().add(new IntrospectionResult.ColumnInfo(column, mapped.yamlType(),
          dataType, mapped.degraded()));
    }
    return new ArrayList<>(byKey.values());
  }

  private String sanitize(String message) {
    if (message == null) {
      return "unknown error";
    }
    int urlIndex = message.indexOf("jdbc:");
    return urlIndex >= 0 ? message.substring(0, urlIndex).trim() : message;
  }
}
```

`MetadataIntrospectors.byEngine` 加分支：`case "trino" -> new TrinoMetadataIntrospector();`

- [ ] **Step 4: 运行确认通过 + 全量**

Run: `mvn test -Dtest=TrinoMetadataIntrospectorTest && mvn test`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/sqlmask/introspect/TrinoMetadataIntrospector.java src/main/java/io/sqlmask/introspect/MetadataIntrospectors.java src/test/java/io/sqlmask/introspect/TrinoMetadataIntrospectorTest.java
git commit -m "feat: Trino information_schema 元数据采集 TrinoMetadataIntrospector"
```

---

### Task 7: mysql/trino golden 字节锁定

**Files:**
- Create: `src/test/resources/golden/introspect-mysql.yaml`
- Create: `src/test/resources/golden/introspect-trino.yaml`
- Modify: `src/test/java/io/sqlmask/introspect/MetadataYamlGeneratorTest.java`（两个 golden 用例）

**Interfaces:**
- Consumes: `MetadataYamlGenerator.generate(IntrospectionResult)`（不变）、`IntrospectionResult` 手工 fixture（不经 JDBC）
- Produces: 两个新 golden 用例（同 assertGolden 模式）

- [ ] **Step 1: 写失败测试（MetadataYamlGeneratorTest 追加）**

```java
  private IntrospectionResult mysql() {
    return new IntrospectionResult("shop", List.of(
        new IntrospectionResult.TableInfo("shop", "shop", "customer", List.of(
            new IntrospectionResult.ColumnInfo("id", "bigint", "bigint unsigned", true),
            new IntrospectionResult.ColumnInfo("name", "varchar(50)", "varchar(50)", false),
            new IntrospectionResult.ColumnInfo("created_at", "datetime(3)", "datetime(3)", false))),
        List.of("column shop.shop.customer.id: mysql type bigint unsigned is not representable, degraded to varchar"));
  }

  private IntrospectionResult trino() {
    return new IntrospectionResult("crm", List.of(
        new IntrospectionResult.TableInfo("crm", "public", "customer", List.of(
            new IntrospectionResult.ColumnInfo("id", "bigint", "bigint", false),
            new IntrospectionResult.ColumnInfo("tags", "varchar", "json", true))),
        List.of("column crm.public.customer.tags: trino type json is not representable, degraded to varchar")));
  }

  @Test
  void mysqlGolden() throws Exception {
    assertGolden("introspect-mysql.yaml", mysql());
  }

  @Test
  void trinoGolden() throws Exception {
    assertGolden("introspect-trino.yaml", trino());
  }
```

- [ ] **Step 2: 手写 golden 文件**

`src/test/resources/golden/introspect-mysql.yaml`：

```yaml
metadata:
  tables:
    - catalog: shop
      schema: shop
      name: customer
      columns:
        - name: id
          type: bigint
        - name: name
          type: varchar(50)
        - name: created_at
          type: datetime(3)
policies: {}
```

`src/test/resources/golden/introspect-trino.yaml`：

```yaml
metadata:
  tables:
    - catalog: crm
      schema: public
      name: customer
      columns:
        - name: id
          type: bigint
        - name: tags
          type: varchar
policies: {}
```

- [ ] **Step 3: 运行确认通过（fixture 与 generator 均已存在，此任务验证的是方言形态走同一确定性拼装）**

Run: `mvn test -Dtest=MetadataYamlGeneratorTest && mvn test`
Expected: PASS（含两个新 golden）

- [ ] **Step 4: Commit**

```bash
git add src/test/resources/golden/introspect-mysql.yaml src/test/resources/golden/introspect-trino.yaml src/test/java/io/sqlmask/introspect/MetadataYamlGeneratorTest.java
git commit -m "test: mysql/trino 生成形态 golden 字节锁定"
```

---

### Task 8: CLI --engine + Web engine 字段 + 页面引擎下拉

**Files:**
- Modify: `src/main/java/io/sqlmask/cli/SqlMaskApplication.java`
- Modify: `src/main/java/io/sqlmask/server/MetadataController.java`
- Modify: `src/main/resources/static/index.html`
- Modify: `src/test/java/io/sqlmask/cli/MetadataExportCliTest.java`
- Modify: `src/test/java/io/sqlmask/server/MetadataControllerTest.java`

**Interfaces:**
- Consumes: `MetadataIntrospectors.byEngine(...)`、`ConnectionSpec` 11 参构造
- Produces: CLI `--engine`（默认 `postgresql`；非法值退出 2，stderr 含 "engine"）；Web 请求体可选 `engine`（缺省 postgresql；非法值 400 CONFIG_ERROR）；页面导入弹窗加引擎下拉。

- [ ] **Step 1: 写失败测试**

MetadataExportCliTest 追加：

```java
  @Test
  void unknownEngineIsUsageError() {
    SqlMaskApplication app = new SqlMaskApplication();
    StringBuilder out = new StringBuilder(), err = new StringBuilder();
    int code = run(app, new String[]{"--pull-metadata", "--engine", "oracle",
        "--database", "d", "--user", "u", "--password", "x", "--output", "o.yaml"}, out, err);
    assertEquals(2, code);
    assertTrue(err.toString().contains("engine"));
  }
```

MetadataControllerTest 追加：

```java
  @Test
  void unknownEngineIsConfigError() throws Exception {
    introspector = stubReturning(sample());
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(introspector))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    mvc.perform(post("/api/metadata/pull").contentType("application/json").content("""
        {"engine":"oracle","database":"d","user":"u","password":"p"}
        """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=MetadataExportCliTest -Dtest=MetadataControllerTest`
（两条 -Dtest 由 surefire 逗号语法：`-Dtest=MetadataExportCliTest,MetadataControllerTest`）
Expected: 未知选项 picocli 报错但断言不匹配（真 RED 以 `unknownEngineIsUsageError` 的 stderr 断言为准）

- [ ] **Step 3: 实现**

`SqlMaskApplication`：新增

```java
  @Option(names = "--engine", defaultValue = "postgresql",
      description = "Engine for --pull-metadata: postgresql|mysql|trino (default postgresql).")
  private String engine;
```

`executePullMetadata` 开头加：

```java
    if (!Set.of("postgresql", "mysql", "trino")
        .contains(engine == null ? "" : engine.toLowerCase(java.util.Locale.ROOT))) {
      err.println("sql-mask: unsupported engine '" + engine
          + "' (supported: postgresql, mysql, trino)");
      return 2;
    }
```

并把 introspect 调用改为：

```java
    ConnectionSpec spec = new ConnectionSpec(engine, host, port, database, user,
        resolvedPassword, schemas == null ? List.of() : schemas, includeViews, strict,
        sslmode, connectTimeout);
    IntrospectionResult result;
    try {
      result = MetadataIntrospectors.byEngine(engine).introspect(spec);
    } catch (SqlMaskException e) { ... }
```

`MetadataController.pull`：`MetadataPullRequest` 加 `String engine`；开头校验：

```java
    String resolvedEngine = request.engine() == null || request.engine().isBlank()
        ? "postgresql" : request.engine();
    if (!Set.of("postgresql", "mysql", "trino").contains(resolvedEngine)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unsupported engine '" + request.engine()
              + "' (supported: postgresql, mysql, trino)");
    }
```

ConnectionSpec 构造首位传 `resolvedEngine`；introspect 经 `MetadataIntrospectors.byEngine(resolvedEngine)`。

页面：弹窗 host 行前加一行：

```html
<div class="row"><select id="imp-engine">
  <option value="postgresql">postgresql（默认）</option>
  <option value="mysql">mysql</option>
  <option value="trino">trino</option>
</select></div>
```

`importFromDatabase` 的 body 加 `engine: $("imp-engine").value`。

- [ ] **Step 4: 运行确认通过 + 全量**

Run: `mvn test -Dtest=MetadataExportCliTest,MetadataControllerTest && mvn test`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/sqlmask/cli/SqlMaskApplication.java src/main/java/io/sqlmask/server/MetadataController.java src/main/resources/static/index.html src/test/java/io/sqlmask/cli/MetadataExportCliTest.java src/test/java/io/sqlmask/server/MetadataControllerTest.java
git commit -m "feat: --pull-metadata 支持 --engine mysql|trino（CLI/Web/页面）"
```

---

### Task 9: README + 手动端到端验收（分时容器）

**Files:**
- Modify: `README.md`
- 验收环境：远程 47.100.166.158（SSH 免密已配），现有 pg-mask 容器常驻

**Interfaces:**
- Consumes: 全部前序任务

- [ ] **Step 1: README 增量**

`## 元数据采集（--pull-metadata）` 小节更新：支持引擎列表（postgresql/mysql/trino）、`--engine` 用法代码块、`--database` 语义按引擎（库名/catalog 名）、MySQL 的 unsigned 剥离降级与 Trino complex 类型降级说明、Web 请求体 `engine` 字段、页面引擎下拉。错误码表维持现状（INTROSPECT_ERROR 已在 pull 小节）。

- [ ] **Step 2: MySQL 真实验收（先 MySQL 后 Trino 分时，避免内存超限）**

```bash
# 远端起 MySQL（daocloud 镜像；端口 3307 避免与潜在本地 MySQL 冲突——映射远端 3306）
ssh root@47.100.166.158 "docker run -d --name mysql-mask -e MYSQL_ROOT_PASSWORD=MyTest2026 -e MYSQL_DATABASE=shop -p 3306:3306 docker.m.daocloud.io/library/mysql:8.0"
# 等待就绪（约 30s）：docker exec mysql-mask mysqladmin ping -uroot -pMyTest2026
# 建表灌数（含降级列）：
ssh root@47.100.166.158 "docker exec -i mysql-mask mysql -uroot -pMyTest2026 shop" <<'SQL'
CREATE TABLE customer (id BIGINT PRIMARY KEY, name VARCHAR(50), balance DECIMAL(10,2),
  created_at DATETIME(3), tags JSON, level ENUM('a','b'));
INSERT INTO customer VALUES (1,'张伟',123.45,'2024-01-01 08:00:00.123','{"k":1}','a');
SQL
# 本机导出（隧道 3306）：ssh -N -L 3306:127.0.0.1:3306 root@47.100.166.158 &
java -jar target/sql-mask.jar --pull-metadata --engine mysql --host 127.0.0.1 --port 3306 \
  --database shop --user root --password MyTest2026 --output mysql-shop.yaml
```

断言：YAML 含 catalog/schema=shop、类型 `bigint`/`varchar(50)`/`decimal(10,2)`/`datetime(3)`，tags 与 level 降级 varchar 且 stderr 两警告；重跑 `cmp` 无差异；每个类型可用 mysql 方言 YAML 校验（把生成的 YAML 配 `--dialect mysql` 跑一条 `SELECT * FROM \`shop\`.\`customer\`` 改写——若 --dialect 改写模式仍仅 postgresql，则用 round-trip 单测证据替代并注明）。验完：`ssh root@47.100.166.158 "docker rm -f mysql-mask"`。

- [ ] **Step 3: Trino 真实验收（MySQL 容器停止后）**

```bash
ssh root@47.100.166.158 "docker run -d --name trino-mask -p 8080:8080 docker.m.daocloud.io/trinodb/trino:446"
# 等待就绪：docker exec trino-mask trino --execute "SELECT 1"（约 30-60s）
ssh root@47.100.166.158 "docker exec trino-mask trino --execute \"CREATE TABLE memory.crm.customer (id BIGINT, tags JSON)\""
# memory catalog 无显式 schema 需先 CREATE SCHEMA memory.crm
java -jar target/sql-mask.jar --pull-metadata --engine trino --host 127.0.0.1 --port 8080 \
  --database crm --user trino --password '' --output trino-crm.yaml
```

（trino-jdbc Properties 传空密码：CLI `--password ''` 走"给了空串"路径——`resolvedPassword` 非空判断会拒绝空串；实现上 Trino 无密码认证时传任意占位值 `--password x` 即可，报告注明。）断言：catalog=crm、`bigint` 照抄、`json` 降级 varchar + 警告；重跑逐字节一致。若容器因内存（1.6G 主机）启动失败或 OOM：如实记录并 BLOCKED 上报验收项，代码层 mock 测试已覆盖、不阻塞合并。验完 `docker rm -f trino-mask`。

- [ ] **Step 4: 全量回归 + 提交**

`mvn test` 全量；commit：`docs: 元数据采集多引擎用法与验收记录`（git add README.md）。

---

## Self-Review 记录

1. **Spec 覆盖**：§1 接口/注册→T2；§2 ConnectionSpec/URL→T1；§3 三段名→T5/T6 组装逻辑（MySQL catalog=schema=库名见 T5 测试断言 `shop.shop.customer.id`）+T7 golden；§4 mapper→T3/T4；§5 查询→T5/T6；§6 CLI/Web/页面→T8；§7 依赖→T1；§8 错误处理→T8（engine 用法错误）+既有矩阵；§9 测试→T3/T4/T5/T6/T7 + T9 手动验收。无缺口。
2. **占位符**：Task 5/6 测试骨架中的"实现说明"是显式裁决（内联 mock 风格），非 TODO；Task 9 的 Trino 空密码问题已给出确定做法（占位值+报告注明）。
3. **类型一致性**：`ConnectionSpec` 11 参（engine 首位）在 T1 定义、T5/T6/T8 调用一致；`MetadataIntrospectors.byEngine` 分支逐步扩展（T2→T5→T6）在各自任务里明确；`PgTypeMapper.Mapped` 作为共享载体在 T3/T4 复用；警告后缀 `degraded to varchar` 与 strict `endsWith` 兼容。
