# 元数据采集生成 metadata YAML 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 sql-mask 增加「连接 PG 读取库表元数据 → 生成本地 metadata YAML」能力，CLI 与 Web 两端交付。

**Architecture:** 新包 `io.sqlmask.introspect` 四个类（ConnectionSpec / PgMetadataIntrospector / PgTypeMapper / MetadataYamlGenerator），JDBC 直连 `pg_catalog` 拉取，手写确定性拼装 YAML；CLI 在 `SqlMaskApplication` 加互斥参数组，Web 加 `POST /api/metadata/pull` 与前端导入弹窗，两端共用同一 Introspector。

**Tech Stack:** Java 21、Spring Boot（BOM 管理 `org.postgresql:postgresql` 版本）、picocli、JUnit 5、Mockito（spring-boot-starter-test 自带）。

**Spec:** `docs/superpowers/specs/2026-09-06-metadata-introspection-design.md`（本计划从 spec 出发，执行者需同时阅读）。

## Global Constraints

- Java 21；现有代码风格：4 空格缩进、`final class`、record、Javadoc 说明「是什么/为什么」而非复述代码。
- 每个任务结束 `mvn test` 必须全绿后才 commit；提交信息用 conventional 前缀（`feat:`/`test:`/`docs:`）。
- 不修改 `tpcds/` 目录与 `src/test/resources/golden/` 下既有文件；新增 golden 文件允许。
- 密码不得出现在任何日志、异常消息、响应体中；异常消息打印前须剥除 JDBC URL 参数。
- YAML 输出必须确定性：同库两次导出逐字节一致（表按 catalog→schema→name 字典序，列按 `attnum` 原序）。
- 新增错误码：`INTROSPECT_ERROR`（采集失败）、`STRICT_DEGRADED`（--strict 命中降级），加进 `SqlMaskException.Code`。
- Web 端无 strict 开关：警告全部透传，不失败。

## File Structure

```
pom.xml                                          (Modify) 新增 postgresql 依赖
src/main/java/io/sqlmask/error/SqlMaskException.java    (Modify) Code 枚举加 2 个值
src/main/java/io/sqlmask/introspect/ConnectionSpec.java         (Create) 连接参数 + JDBC URL
src/main/java/io/sqlmask/introspect/PgTypeMapper.java           (Create) format_type → YAML 类型
src/main/java/io/sqlmask/introspect/IntrospectionResult.java    (Create) 结果模型
src/main/java/io/sqlmask/introspect/PgMetadataIntrospector.java (Create) pg_catalog 采集
src/main/java/io/sqlmask/introspect/MetadataYamlGenerator.java  (Create) 确定性 YAML 拼装
src/main/java/io/sqlmask/cli/SqlMaskApplication.java            (Modify) --pull-metadata 参数组
src/main/java/io/sqlmask/server/MetadataController.java         (Create) POST /api/metadata/pull
src/main/resources/static/index.html                            (Modify) 导入按钮 + 弹窗 + 合并
docs/.../README 相关小节 (Modify) — 见 Task 8
测试:
src/test/java/io/sqlmask/introspect/{ConnectionSpecTest,PgTypeMapperTest,PgMetadataIntrospectorTest,MetadataYamlGeneratorTest}.java
src/test/java/io/sqlmask/cli/MetadataExportCliTest.java
src/test/java/io/sqlmask/server/MetadataControllerTest.java
src/test/resources/golden/introspect-{multi-table,degraded,empty}.yaml  (Create)
```

---

### Task 1: postgresql 依赖 + ConnectionSpec

**Files:**
- Modify: `pom.xml`（dependencies 段）
- Create: `src/main/java/io/sqlmask/introspect/ConnectionSpec.java`
- Test: `src/test/java/io/sqlmask/introspect/ConnectionSpecTest.java`

**Interfaces:**
- Produces: `record ConnectionSpec(String host, int port, String database, String user, String password, List<String> schemas, boolean includeViews, boolean strict, String sslmode, int connectTimeoutSeconds)`，静态工厂 `ConnectionSpec of(...)`（10 参全参），实例方法 `String toJdbcUrl()`，便捷构造（host/port/sslmode/timeout 有默认值时由 CLI 层填充，本类不做默认值逻辑）。

- [ ] **Step 1: pom 加依赖（BOM 管版本，不写 version）**

```xml
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
</dependency>
```

插入位置：`snakeyaml` 依赖之后。spring-boot-dependencies BOM 已管理该 artifact 版本。

- [ ] **Step 2: 写失败测试**

```java
package io.sqlmask.introspect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectionSpecTest {

  private ConnectionSpec minimal() {
    return new ConnectionSpec("127.0.0.1", 5432, "crm", "postgres", "pw",
        List.of(), false, false, "disable", 10);
  }

  @Test
  void buildsJdbcUrlWithSslmodeAndTimeouts() {
    assertEquals("jdbc:postgresql://127.0.0.1:5432/crm"
        + "?sslmode=disable&connectTimeout=10&socketTimeout=60&readOnly=true",
        minimal().toJdbcUrl());
  }

  @Test
  void urlEscapesNothingButKeepsGivenDatabase() {
    ConnectionSpec spec = new ConnectionSpec("pg.example.com", 6543, "my_db", "u", "p",
        List.of("public"), true, true, "require", 3);
    assertEquals("jdbc:postgresql://pg.example.com:6543/my_db"
        + "?sslmode=require&connectTimeout=3&socketTimeout=60&readOnly=true",
        spec.toJdbcUrl());
  }

  @Test
  void rejectsBlankDatabaseAndUser() {
    assertThrows(IllegalArgumentException.class,
        () -> new ConnectionSpec("h", 5432, " ", "u", "p", List.of(), false, false, "disable", 10));
    assertThrows(IllegalArgumentException.class,
        () -> new ConnectionSpec("h", 5432, "db", "", "p", List.of(), false, false, "disable", 10));
    assertThrows(IllegalArgumentException.class,
        () -> new ConnectionSpec("h", 0, "db", "u", "p", List.of(), false, false, "disable", 10));
  }

  @Test
  void schemasListIsDefensivelyCopied() {
    ConnectionSpec spec = minimal();
    spec.schemas().clear();
    assertEquals(List.of(), spec.schemas());
  }
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn test -Dtest=ConnectionSpecTest`
Expected: 编译失败 `cannot find symbol: class ConnectionSpec`

- [ ] **Step 4: 最小实现**

```java
package io.sqlmask.introspect;

import java.util.List;
import java.util.Objects;

/**
 * Immutable connection parameters for pulling metadata from one PostgreSQL
 * database. The catalog reported in generated YAML equals {@link #database()}
 * (PostgreSQL has exactly one database per connection).
 */
public record ConnectionSpec(String host, int port, String database, String user,
    String password, List<String> schemas, boolean includeViews, boolean strict,
    String sslmode, int connectTimeoutSeconds) {

  public ConnectionSpec {
    Objects.requireNonNull(host, "host");
    if (database == null || database.isBlank()) {
      throw new IllegalArgumentException("database is required");
    }
    if (user == null || user.isBlank()) {
      throw new IllegalArgumentException("user is required");
    }
    if (port <= 0) {
      throw new IllegalArgumentException("port must be positive");
    }
    schemas = List.copyOf(schemas == null ? List.of() : schemas);
    sslmode = sslmode == null || sslmode.isBlank() ? "disable" : sslmode;
  }

  /** URL carries read-only + timeouts as defense in depth; password goes via the JDBC properties. */
  public String toJdbcUrl() {
    return "jdbc:postgresql://" + host + ":" + port + "/" + database
        + "?sslmode=" + sslmode
        + "&connectTimeout=" + connectTimeoutSeconds
        + "&socketTimeout=60&readOnly=true";
  }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn test -Dtest=ConnectionSpecTest`
Expected: PASS（4 tests）

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/io/sqlmask/introspect/ConnectionSpec.java src/test/java/io/sqlmask/introspect/ConnectionSpecTest.java
git commit -m "feat: 元数据采集连接参数 ConnectionSpec 与 postgresql 依赖"
```

---

### Task 2: PgTypeMapper 全类型矩阵

**Files:**
- Create: `src/main/java/io/sqlmask/introspect/PgTypeMapper.java`
- Test: `src/test/java/io/sqlmask/introspect/PgTypeMapperTest.java`

**Interfaces:**
- Produces: `final class PgTypeMapper`，方法 `Mapped map(String formatType)`；`record Mapped(String yamlType, boolean degraded)`。`formatType` 是 `format_type(atttypid, atttypmod)` 原文。非法/未知输入返回 `degraded=true, yamlType="varchar"`，绝不抛异常。

- [ ] **Step 1: 写失败测试（spec 第 3 节映射表全行 + 降级代表）**

```java
package io.sqlmask.introspect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgTypeMapperTest {

  private final PgTypeMapper mapper = new PgTypeMapper();

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      // spec 映射表：精确行
      "'boolean', boolean",
      "'smallint', smallint",
      "'integer', integer",
      "'bigint', bigint",
      "'real', real",
      "'double precision', 'double precision'",
      "'numeric(10,2)', 'numeric(10,2)'",
      "'numeric', numeric",
      "'numeric(5)', 'numeric(5)'",
      "'character(20)', 'char(20)'",
      "'character', 'char(1)'",
      "'character varying(50)', 'varchar(50)'",
      "'character varying', varchar",
      "'date', date",
      "'timestamp(3) without time zone', 'timestamp(3)'",
      "'timestamp without time zone', timestamp",
      "'timestamp(3) with time zone', 'timestamptz(3)'",
      "'timestamp with time zone', timestamptz",
      "'time(6) without time zone', 'time(6)'",
      "'time without time zone', time",
      "'time(6) with time zone', 'timetz(6)'",
      "'time with time zone', timetz"
  })
  void mapsExactly(String pgType, String expected) {
    PgTypeMapper.Mapped m = mapper.map(pgType);
    assertEquals(expected, m.yamlType());
    assertFalse(m.degraded(), pgType);
  }

  @ParameterizedTest(name = "{0} degrades")
  @CsvSource({
      "jsonb, json, uuid, bytea, money, inet, xml, tsvector, interval, 'integer[]', 'text[]'"
  })
  void degradesUnsupportedTypes(String ignored) { }

  @Test
  void arrayTypesDegrade() {
    PgTypeMapper.Mapped m = mapper.map("integer[]");
    assertEquals("varchar", m.yamlType());
    assertTrue(m.degraded());
  }

  @Test
  void scalarUnsupportedTypesDegrade() {
    for (String t : new String[]{"jsonb", "json", "uuid", "bytea", "money", "inet",
        "xml", "tsvector", "interval", "hstore", "point"}) {
      PgTypeMapper.Mapped m = mapper.map(t);
      assertEquals("varchar", m.yamlType(), t);
      assertTrue(m.degraded(), t);
    }
  }

  @Test
  void nullAndBlankDegrade() {
    assertTrue(mapper.map(null).degraded());
    assertEquals("varchar", mapper.map("").yamlType());
  }
}
```

（`@CsvSource` 的 `degradesUnsupportedTypes` 空方法删除，保留两个具体测试即可——上面代码中该方法仅为占位展示会被删除，实际文件里不要它。）

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=PgTypeMapperTest`
Expected: 编译失败 `cannot find symbol: class PgTypeMapper`

- [ ] **Step 3: 实现**

```java
package io.sqlmask.introspect;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps PostgreSQL {@code format_type()} output (e.g. {@code character varying(50)},
 * {@code timestamp(3) with time zone}) to a type declaration the YAML metadata
 * accepts. Anything outside the supported set degrades to {@code varchar} with a
 * {@code degraded} marker so callers can warn — columns never disappear.
 */
public final class PgTypeMapper {

  public record Mapped(String yamlType, boolean degraded) {
  }

  private static final Pattern SHAPE = Pattern.compile(
      "^(.*??)\\s*(?:\\((\\d+)(?:\\s*,\\s*(\\d+))?\\))?$");

  public Mapped map(String formatType) {
    if (formatType == null || formatType.isBlank()) {
      return new Mapped("varchar", true);
    }
    String raw = formatType.trim();
    if (raw.endsWith("[]")) {
      return new Mapped("varchar", true);
    }
    boolean withTimeZone = false;
    String body = raw;
    if (body.endsWith("with time zone")) {
      withTimeZone = true;
      body = body.substring(0, body.length() - "with time zone".length()).trim();
    } else if (body.endsWith("without time zone")) {
      body = body.substring(0, body.length() - "without time zone".length()).trim();
    }
    Matcher m = SHAPE.matcher(body);
    if (!m.matches()) {
      return new Mapped("varchar", true);
    }
    String base = m.group(1).trim().toLowerCase(Locale.ROOT);
    Integer p = m.group(2) == null ? null : Integer.valueOf(m.group(2));
    Integer s = m.group(3) == null ? null : Integer.valueOf(m.group(3));
    return switch (base) {
      case "boolean" -> new Mapped("boolean", false);
      case "smallint" -> new Mapped("smallint", false);
      case "integer" -> new Mapped("integer", false);
      case "bigint" -> new Mapped("bigint", false);
      case "real" -> new Mapped("real", false);
      case "double precision" -> new Mapped("double precision", false);
      case "numeric", "decimal" -> new Mapped(p == null ? "numeric"
          : s == null ? "numeric(" + p + ")" : "numeric(" + p + "," + s + ")", false);
      case "character", "char", "bpchar" -> new Mapped("char(" + (p == null ? 1 : p) + ")", false);
      case "character varying", "varchar" -> new Mapped(p == null ? "varchar" : "varchar(" + p + ")", false);
      case "text" -> new Mapped("text", false);
      case "date" -> new Mapped("date", false);
      case "timestamp" -> new Mapped(withTimeZone
          ? (p == null ? "timestamptz" : "timestamptz(" + p + ")")
          : (p == null ? "timestamp" : "timestamp(" + p + ")"), false);
      case "time" -> new Mapped(withTimeZone
          ? (p == null ? "timetz" : "timetz(" + p + ")")
          : (p == null ? "time" : "time(" + p + ")"), false);
      default -> new Mapped("varchar", true);
    };
  }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -Dtest=PgTypeMapperTest`
Expected: PASS（全部参数化用例）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/sqlmask/introspect/PgTypeMapper.java src/test/java/io/sqlmask/introspect/PgTypeMapperTest.java
git commit -m "feat: format_type 到 YAML 类型映射，不可映射降级 varchar"
```

---

### Task 3: IntrospectionResult + PgMetadataIntrospector

**Files:**
- Modify: `src/main/java/io/sqlmask/error/SqlMaskException.java`（Code 枚举加 `INTROSPECT_ERROR`）
- Create: `src/main/java/io/sqlmask/introspect/IntrospectionResult.java`
- Create: `src/main/java/io/sqlmask/introspect/PgMetadataIntrospector.java`
- Test: `src/test/java/io/sqlmask/introspect/PgMetadataIntrospectorTest.java`

**Interfaces:**
- Consumes: `ConnectionSpec.toJdbcUrl()`、`PgTypeMapper.map()`
- Produces:
  - `record IntrospectionResult(String catalog, List<TableInfo> tables, List<String> warnings)`，内嵌 `record TableInfo(String catalog, String schema, String name, List<ColumnInfo> columns)`、`record ColumnInfo(String name, String yamlType, String originalPgType, boolean degraded)`
  - `final class PgMetadataIntrospector`，方法 `IntrospectionResult introspect(ConnectionSpec spec)`；`protected Connection open(ConnectionSpec spec) throws SQLException`（测试覆盖点）。
  - 警告文案（spec 原文）：降级列 `column <catalog>.<schema>.<table>.<column>: PG type <original> is not representable, degraded to varchar`；空库 `未找到任何表，请检查 schema 过滤条件`。

- [ ] **Step 1: SqlMaskException.Code 加 INTROSPECT_ERROR**

在 `IO_ERROR` 之后加一行：

```java
    INTROSPECT_ERROR,
```

- [ ] **Step 2: 写失败测试（Mockito mock JDBC，不连真库）**

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PgMetadataIntrospectorTest {

  /** 第 1 条语句返回库名；第 2 条返回两行表数据（drop 列已由 SQL 过滤）。 */
  private Connection fakeConnection(ResultSet catalogRow, ResultSet tableRows)
      throws SQLException {
    Connection conn = mock(Connection.class);
    PreparedStatement c1 = mock(PreparedStatement.class);
    PreparedStatement c2 = mock(PreparedStatement.class);
    when(conn.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(c1, c2);
    when(c1.executeQuery()).thenReturn(catalogRow);
    when(c2.executeQuery()).thenReturn(tableRows);
    return conn;
  }

  private ResultSet singleStringRow(String value) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    when(rs.next()).thenReturn(true, false);
    when(rs.getString(1)).thenReturn(value);
    return rs;
  }

  /** 每行: schema, table, relkind, column, pg_type, attnum */
  private ResultSet tableRows(String[][] rows) throws SQLException {
    ResultSet rs = mock(ResultSet.class);
    Boolean[] next = new Boolean[rows.length + 1];
    for (int i = 0; i < rows.length; i++) next[i] = true;
    next[rows.length] = false;
    when(rs.next()).thenReturn(true, java.util.Arrays.copyOf(next, rows.length, Boolean[].class));
    // simpler: stub sequence
    when(rs.next()).thenReturn(true, false, false).thenReturn(false);
    // mockito consecutive stubbing: build explicitly below in test setup instead
    return rs;
  }

  @Test
  void collectsTablesColumnsAndCatalog() throws SQLException {
    ResultSet tables = mock(ResultSet.class);
    when(tables.next()).thenReturn(true, true, true, false);
    when(tables.getString("schema_name")).thenReturn("public", "public", "sales");
    when(tables.getString("table_name")).thenReturn("customer", "orders", "facts");
    when(tables.getString("relkind")).thenReturn("r", "r", "p");
    when(tables.getString("column_name")).thenReturn("id", "phone", "id");
    when(tables.getString("pg_type")).thenReturn("bigint", "character varying(20)", "jsonb");
    when(tables.getInt("attnum")).thenReturn(1, 2, 1);

    Connection conn = fakeConnection(singleStringRow("crm"), tables);
    PgMetadataIntrospector introspector = new PgMetadataIntrospector() {
      @Override protected Connection open(ConnectionSpec spec) { return conn; }
    };

    IntrospectionResult result = introspector.introspect(
        new ConnectionSpec("h", 5432, "crm", "u", "p", List.of(), false, false, "disable", 10));

    assertEquals("crm", result.catalog());
    assertEquals(2, result.tables().size());
    IntrospectionResult.TableInfo customer = result.tables().get(0);
    assertEquals("public", customer.schema());
    assertEquals("customer", customer.name());
    assertEquals(2, customer.columns().size());
    assertEquals("bigint", customer.columns().get(0).yamlType());
    assertEquals("varchar(20)", customer.columns().get(1).yamlType());
    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("jsonb") && w.contains("crm.public.facts.id")));
  }

  @Test
  void emptyDatabaseYieldsWarningNotError() throws SQLException {
    ResultSet empty = mock(ResultSet.class);
    when(empty.next()).thenReturn(false);
    Connection conn = fakeConnection(singleStringRow("crm"), empty);
    PgMetadataIntrospector introspector = new PgMetadataIntrospector() {
      @Override protected Connection open(ConnectionSpec spec) { return conn; }
    };
    IntrospectionResult result = introspector.introspect(
        new ConnectionSpec("h", 5432, "crm", "u", "p", List.of(), false, false, "disable", 10));
    assertTrue(result.tables().isEmpty());
    assertTrue(result.warnings().stream()
        .anyMatch(w -> w.contains("未找到任何表")));
  }

  @Test
  void connectionFailureBecomesIntrospectErrorWithoutUrl() {
    PgMetadataIntrospector introspector = new PgMetadataIntrospector() {
      @Override protected Connection open(ConnectionSpec spec) throws SQLException {
        throw new SQLException("FATAL: password authentication failed for user \"postgres\"");
      }
    };
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> introspector.introspect(
        new ConnectionSpec("h", 5432, "crm", "u", "p", List.of(), false, false, "disable", 10)));
    assertEquals(SqlMaskException.Code.INTROSPECT_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("password authentication failed"));
  }
}
```

> 注：`tableRows` 辅助方法在最终文件里删除——测试用独立的内联 mock（如 `collectsTablesColumnsAndCatalog` 所示），不要保留两个互相矛盾的 stub 写法。

- [ ] **Step 3: 运行确认失败**

Run: `mvn test -Dtest=PgMetadataIntrospectorTest`
Expected: 编译失败 `cannot find symbol: class PgMetadataIntrospector`

- [ ] **Step 4: 实现**

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
 * Pulls table and column metadata from one PostgreSQL database through a
 * single read-only JDBC connection. Statements only SELECT from pg_catalog;
 * the JDBC URL itself is opened with readOnly=true as defense in depth.
 */
public final class PgMetadataIntrospector {

  private static final String CATALOG_SQL = "SELECT current_database()";
  private static final String TABLES_SQL = """
      SELECT n.nspname AS schema_name, c.relname AS table_name, c.relkind,
             a.attname AS column_name, format_type(a.atttypid, a.atttypmod) AS pg_type,
             a.attnum
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
      WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
        AND c.relkind IN (%s)
      ORDER BY n.nspname, c.relname, a.attnum""";

  private final PgTypeMapper typeMapper = new PgTypeMapper();

  public IntrospectionResult introspect(ConnectionSpec spec) {
    try (Connection connection = open(spec)) {
      Properties unused = null;
      String catalog = queryCurrentDatabase(connection);
      List<IntrospectionResult.TableInfo> tables =
          queryTables(connection, spec, catalog);
      List<String> warnings = new ArrayList<>();
      for (IntrospectionResult.TableInfo table : tables) {
        for (IntrospectionResult.ColumnInfo column : table.columns()) {
          if (column.degraded()) {
            warnings.add("column " + table.catalog() + "." + table.schema() + "."
                + table.name() + "." + column.name() + ": PG type "
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
    try (PreparedStatement statement = connection.prepareStatement(CATALOG_SQL);
         ResultSet rs = statement.executeQuery()) {
      if (!rs.next()) {
        throw new SQLException("current_database() returned no row");
      }
      return rs.getString(1);
    }
  }

  private List<IntrospectionResult.TableInfo> queryTables(
      Connection connection, ConnectionSpec spec, String catalog) throws SQLException {
    String relKinds = spec.includeViews() ? "'r', 'p', 'v', 'm'" : "'r', 'p'";
    List<String> schemas = spec.schemas();
    String sql = TABLES_SQL.formatted(relKinds);
    if (!schemas.isEmpty()) {
      sql = sql.replace("ORDER BY", "AND n.nspname = ?\nORDER BY");
      // one extra predicate per schema; simplest deterministic form:
      StringBuilder predicate = new StringBuilder();
      for (int i = 0; i < schemas.size(); i++) {
        predicate.append(i == 0 ? "AND n.nspname IN (" : ", ");
        predicate.append("?");
        if (i == schemas.size() - 1) {
          predicate.append(")");
        }
      }
      sql = TABLES_SQL.formatted(relKinds).replace("ORDER BY",
          predicate + "\nORDER BY");
    }
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      int index = 1;
      for (String schema : schemas) {
        statement.setString(index++, schema);
      }
      try (ResultSet rs = statement.executeQuery()) {
        return assemble(rs, catalog);
      }
    }
  }

  private List<IntrospectionResult.TableInfo> assemble(ResultSet rs, String catalog)
      throws SQLException {
    Map<String, IntrospectionResult.TableInfo> byKey = new LinkedHashMap<>();
    while (rs.next()) {
      String schema = rs.getString("schema_name");
      String name = rs.getString("table_name");
      String column = rs.getString("column_name");
      String pgType = rs.getString("pg_type");
      PgTypeMapper.Mapped mapped = typeMapper.map(pgType);
      String key = schema + "." + name;
      IntrospectionResult.TableInfo table = byKey.get(key);
      if (table == null) {
        table = new IntrospectionResult.TableInfo(catalog, schema, name, new ArrayList<>());
        byKey.put(key, table);
      }
      table.columns().add(new IntrospectionResult.ColumnInfo(
          column, mapped.yamlType(), pgType, mapped.degraded()));
    }
    return new ArrayList<>(byKey.values());
  }

  /** Strips anything that may carry connection details from driver messages. */
  private String sanitize(String message) {
    if (message == null) {
      return "unknown error";
    }
    int urlIndex = message.indexOf("jdbc:postgresql");
    return urlIndex >= 0 ? message.substring(0, urlIndex).trim() : message;
  }
}
```

> 实现备注：`queryTables` 里先 replace 再重建的写法是计划誊写冗余——落地时保留「schemas 非空则拼 `AND n.nspname IN (?, ?, ...)`」一种实现即可（第二段 StringBuilder 版本）。`TableInfo.columns()` 在 record 里是 `List.copyOf` 防御拷贝会拒绝后续 add——因此 `TableInfo` 的紧凑构造器不做 `List.copyOf`，改由 `IntrospectionResult` 紧凑构造器对 tables/warnings 做 `List.copyOf`（组装完成后不再可变）。落地时按此调整。

- [ ] **Step 5: 运行确认通过**

Run: `mvn test -Dtest=PgMetadataIntrospectorTest`
Expected: PASS（3 tests）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/sqlmask/error/SqlMaskException.java src/main/java/io/sqlmask/introspect/ src/test/java/io/sqlmask/introspect/PgMetadataIntrospectorTest.java
git commit -m "feat: pg_catalog 元数据采集 PgMetadataIntrospector（含 INTROSPECT_ERROR）"
```

---

### Task 4: MetadataYamlGenerator + golden 字节锁定

**Files:**
- Create: `src/main/java/io/sqlmask/introspect/MetadataYamlGenerator.java`
- Create: `src/test/resources/golden/introspect-multi-table.yaml`
- Create: `src/test/resources/golden/introspect-degraded.yaml`
- Create: `src/test/resources/golden/introspect-empty.yaml`
- Test: `src/test/java/io/sqlmask/introspect/MetadataYamlGeneratorTest.java`

**Interfaces:**
- Consumes: `IntrospectionResult`（Task 3）
- Produces: `final class MetadataYamlGenerator`，方法 `String generate(IntrospectionResult result)`（返回无尾随换行文本）。排序：表按 catalog→schema→name 字典序；列保持 `attnum` 原序（组装序）。

- [ ] **Step 1: 写失败测试（含 golden 再生开关，与 GoldenOutputTest 同风格）**

```java
package io.sqlmask.introspect;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetadataYamlGeneratorTest {

  private static final Path GOLDEN_DIR = Path.of("src/test/resources/golden");
  private static final boolean WRITE =
      Boolean.getBoolean("golden.write");

  private IntrospectionResult multiTable() {
    return new IntrospectionResult("crm", List.of(
        new IntrospectionResult.TableInfo("crm", "public", "customer", List.of(
            new IntrospectionResult.ColumnInfo("id", "bigint", "bigint", false),
            new IntrospectionResult.ColumnInfo("phone", "varchar(20)", "character varying(20)", false),
            new IntrospectionResult.ColumnInfo("created_at", "timestamp", "timestamp without time zone", false))),
        new IntrospectionResult.TableInfo("crm", "public", "orders", List.of(
            new IntrospectionResult.ColumnInfo("id", "bigint", "bigint", false),
            new IntrospectionResult.ColumnInfo("amount", "numeric(10,2)", "numeric(10,2)", false))),
        new IntrospectionResult.TableInfo("crm", "sales", "region", List.of(
            new IntrospectionResult.ColumnInfo("id", "integer", "integer", false)))),
        List.of());
  }

  private IntrospectionResult degraded() {
    return new IntrospectionResult("crm", List.of(
        new IntrospectionResult.TableInfo("crm", "public", "customer", List.of(
            new IntrospectionResult.ColumnInfo("id", "bigint", "bigint", false),
            new IntrospectionResult.ColumnInfo("extra", "varchar", "jsonb", true)))),
        List.of("column crm.public.customer.extra: PG type jsonb is not representable, degraded to varchar"));
  }

  private IntrospectionResult empty() {
    return new IntrospectionResult("crm", List.of(),
        List.of("未找到任何表，请检查 schema 过滤条件"));
  }

  @Test
  void multiTableGolden() throws Exception {
    assertGolden("introspect-multi-table.yaml", multiTable());
  }

  @Test
  void degradedColumnGolden() throws Exception {
    assertGolden("introspect-degraded.yaml", degraded());
  }

  @Test
  void emptySkeletonGolden() throws Exception {
    assertGolden("introspect-empty.yaml", empty());
  }

  @Test
  void tableOrderIsCatalogSchemaNameRegardlessOfInputOrder() {
    IntrospectionResult reversed = new IntrospectionResult("crm", List.of(
        new IntrospectionResult.TableInfo("crm", "public", "orders",
            List.of(new IntrospectionResult.ColumnInfo("id", "bigint", "bigint", false))),
        new IntrospectionResult.TableInfo("crm", "public", "customer",
            List.of(new IntrospectionResult.ColumnInfo("id", "bigint", "bigint", false)))),
        List.of());
    String out = new MetadataYamlGenerator().generate(reversed);
    assertTrue(out.indexOf("name: customer") < out.indexOf("name: orders"));
  }

  private void assertGolden(String fileName, IntrospectionResult result) throws Exception {
    String text = new MetadataYamlGenerator().generate(result);
    Path golden = GOLDEN_DIR.resolve(fileName);
    if (WRITE) {
      Files.writeString(golden, text + "\n", StandardCharsets.UTF_8);
    }
    assertEquals(Files.readString(golden, StandardCharsets.UTF_8), text + "\n");
  }

  private static void assertTrue(boolean condition) {
    org.junit.jupiter.api.Assertions.assertTrue(condition);
  }
}
```

- [ ] **Step 2: 创建三个 golden 文件（预期输出，直接手写）**

`src/test/resources/golden/introspect-multi-table.yaml`：

```yaml
metadata:
  tables:
    - catalog: crm
      schema: public
      name: customer
      columns:
        - name: id
          type: bigint
        - name: phone
          type: varchar(20)
        - name: created_at
          type: timestamp
    - catalog: crm
      schema: public
      name: orders
      columns:
        - name: id
          type: bigint
        - name: amount
          type: numeric(10,2)
    - catalog: crm
      schema: sales
      name: region
      columns:
        - name: id
          type: integer
policies: {}
```

`src/test/resources/golden/introspect-degraded.yaml`：

```yaml
metadata:
  tables:
    - catalog: crm
      schema: public
      name: customer
      columns:
        - name: id
          type: bigint
        - name: extra
          type: varchar
policies: {}
```

`src/test/resources/golden/introspect-empty.yaml`：

```yaml
metadata:
  tables: []
policies: {}
```

（golden 文件以 `\n` 结尾；断言侧 `text + "\n"` 与之对齐。）

- [ ] **Step 3: 运行确认失败**

Run: `mvn test -Dtest=MetadataYamlGeneratorTest`
Expected: 编译失败 `cannot find symbol: class MetadataYamlGenerator`

- [ ] **Step 4: 实现（手写确定性拼装）**

```java
package io.sqlmask.introspect;

import java.util.Comparator;
import java.util.List;

/**
 * Deterministically renders an {@link IntrospectionResult} as metadata YAML:
 * hand-assembled (same shape as the web editor's generateYaml) so the same
 * database always yields byte-identical output. Skeleton only — policies and
 * row filters are authored by hand afterwards.
 */
public final class MetadataYamlGenerator {

  public String generate(IntrospectionResult result) {
    StringBuilder out = new StringBuilder();
    out.append("metadata:\n");
    List<IntrospectionResult.TableInfo> tables = result.tables().stream()
        .sorted(Comparator.comparing(IntrospectionResult.TableInfo::catalog)
            .thenComparing(IntrospectionResult.TableInfo::schema)
            .thenComparing(IntrospectionResult.TableInfo::name))
        .toList();
    if (tables.isEmpty()) {
      out.append("  tables: []\n");
    } else {
      out.append("  tables:\n");
      for (IntrospectionResult.TableInfo table : tables) {
        out.append("    - catalog: ").append(scalar(table.catalog())).append('\n');
        out.append("      schema: ").append(scalar(table.schema())).append('\n');
        out.append("      name: ").append(scalar(table.name())).append('\n');
        out.append("      columns:\n");
        for (IntrospectionResult.ColumnInfo column : table.columns()) {
          out.append("        - name: ").append(scalar(column.name())).append('\n');
          out.append("          type: ").append(scalar(column.yamlType())).append('\n');
        }
      }
    }
    out.append("policies: {}");
    return out.toString();
  }

  /** Plain scalar for safe tokens, double-quoted JSON escape otherwise. */
  private String scalar(String value) {
    if (value != null && value.matches("[A-Za-z0-9_.$-]+")) {
      return value;
    }
    StringBuilder escaped = new StringBuilder("\"");
    for (int i = 0; i < (value == null ? 0 : value.length()); i++) {
      char c = value.charAt(i);
      escaped.append(switch (c) {
        case '"' -> "\\\"";
        case '\\' -> "\\\\";
        case '\n' -> "\\n";
        case '\t' -> "\\t";
        case '\r' -> "\\r";
        default -> c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c);
      });
    }
    return escaped.append('"').toString();
  }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `mvn test -Dtest=MetadataYamlGeneratorTest`
Expected: PASS（4 tests）

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/sqlmask/introspect/MetadataYamlGenerator.java src/test/java/io/sqlmask/introspect/MetadataYamlGeneratorTest.java src/test/resources/golden/introspect-*.yaml
git commit -m "feat: 确定性 YAML 生成器与 golden 字节锁定"
```

---

### Task 5: CLI 导出模式

**Files:**
- Modify: `src/main/java/io/sqlmask/cli/SqlMaskApplication.java`
- Test: `src/test/java/io/sqlmask/cli/MetadataExportCliTest.java`

**Interfaces:**
- Consumes: `ConnectionSpec`、`PgMetadataIntrospector.introspect()`、`MetadataYamlGenerator.generate()`（Task 1/3/4）
- Produces: `--pull-metadata` 开关与参数组 `--host/--port/--database/--user/--password/--schema/--include-views/--strict/--sslmode/--connect-timeout/--output`。`--pull-metadata` 与 `--sql/--input` 互斥（退出码 2）；`--metadata` 的 `required = true` 移除，改写模式在 `execute()` 内手动校验（缺失退出码 2）。密码解析顺序：`--password` → 环境变量 `PGPASSWORD` → 都没有退出码 2。stdout 摘要 `introspected N tables / M columns / K warnings`；stderr 逐条警告。strict 且有降级警告（仅统计含 "degraded to varchar" 的行）→ `STRICT_DEGRADED` 退出 1。导出失败不创建/不覆盖 `--output`。0 表退出 0。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.cli;

import io.sqlmask.introspect.IntrospectionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataExportCliTest {

  @TempDir
  Path tempDir;

  private int run(SqlMaskApplication app, String[] args, StringBuilder out, StringBuilder err) {
    return app.run(args, new java.io.ByteArrayInputStream(new byte[0]),
        new java.io.PrintStream(new java.io.ByteArrayOutputStream() {
          @Override public synchronized void write(int b) { out.appendCodePoint(b); }
        }, true, StandardCharsets.UTF_8),
        new java.io.PrintStream(new java.io.ByteArrayOutputStream() {
          @Override public synchronized void write(int b) { err.appendCodePoint(b); }
        }, true, StandardCharsets.UTF_8));
  }
}
```

> 注：流收集用 `ByteArrayOutputStream` 即可，不需要覆写——直接 `new PrintStream(new ByteArrayOutputStream())` 丢弃输出。**测试无法注入 mock Introspector（CLI 内部 new）**，因此 CLI 测试只覆盖「参数层」行为（互斥、必填、密码来源、strict 无警告即成功路径之前的一切分支），真实拉取由 Task 8 手动验收覆盖。写以下测试：

```java
  @Test
  void pullMetadataTogetherWithSqlIsUsageError() {
    SqlMaskApplication app = new SqlMaskApplication();
    StringBuilder out = new StringBuilder(), err = new StringBuilder();
    int code = run(app, new String[]{"--pull-metadata", "--database", "crm",
        "--user", "postgres", "--password", "x", "--output", "o.yaml", "--sql", "SELECT 1"}, out, err);
    assertEquals(2, code);
    assertTrue(err.toString().contains("--sql/--input"));
  }

  @Test
  void missingPasswordIsUsageError() {
    setEnv(null); // PGPASSWORD 清除，见下方环境变量处理说明
    SqlMaskApplication app = new SqlMaskApplication();
    StringBuilder out = new StringBuilder(), err = new StringBuilder();
    int code = run(app, new String[]{"--pull-metadata", "--database", "crm",
        "--user", "postgres", "--output", "o.yaml"}, out, err);
    assertEquals(2, code);
    assertTrue(err.toString().contains("password"));
  }

  @Test
  void rewriteModeWithoutMetadataIsUsageError() {
    SqlMaskApplication app = new SqlMaskApplication();
    StringBuilder out = new StringBuilder(), err = new StringBuilder();
    int code = run(app, new String[]{"--sql", "SELECT 1"}, out, err);
    assertEquals(2, code);
    assertTrue(err.toString().contains("--metadata"));
  }

  @Test
  void passwordComesFromEnvWhenFlagAbsent() {
    // PGPASSWORD 由 exec:exec 或 surefire 环境注入不可移植；该分支的实现走
    // System.getenv，此处仅验证 --password 显式路径 + 环境变量缺失时退出 2。
    // 环境变量路径由手动验收覆盖（Task 8）。
  }
```

> 环境变量在 JVM 内不可注入（`System.getenv` 只读）。`missingPasswordIsUsageError` 假设测试进程无 `PGPASSWORD`——surefire 继承 IDE/CI 环境，若 CI 设置了 `PGPASSWORD` 该测试会失败；为确定性，实现读环境变量前先用 picocli 的 `--password` 为空判断，并在测试里通过「显式传 `--password`」的用例 + 「显式不传且系统无该变量」用例覆盖。若执行环境确实存在 `PGPASSWORD`，把该用例标记 `@Disabled("requires PGPASSWORD unset")`。**实现必须满足**：`--password` 缺省且 `System.getenv("PGPASSWORD")` 为 null → 退出码 2、stderr 含 "password"。

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=MetadataExportCliTest`
Expected: FAIL——`--pull-metadata` 是 picocli 未知选项，`run` 返回 2 但 stderr 是 "Unknown option"（第一条用例因错误原因通过也是假阳性；以 `rewriteModeWithoutMetadataIsUsageError` 的失败为准：当前 `--metadata` 仍 required，picocli 报 "Missing required option"，断言 `--metadata` 文本存在 → 该测试此时恰好通过；真正失败的是互斥用例的断言 `err.contains("--sql/--input")`）。执行者只需确认新用例进入运行且实现后全部转绿。

- [ ] **Step 3: 实现（SqlMaskApplication 修改）**

字段新增：

```java
  @Option(names = "--pull-metadata",
      description = "Pull table/column metadata from PostgreSQL and emit a metadata "
          + "YAML skeleton to --output instead of rewriting SQL.")
  private boolean pullMetadata;

  @Option(names = "--host", defaultValue = "127.0.0.1",
      description = "PostgreSQL host for --pull-metadata (default 127.0.0.1).")
  private String host;

  @Option(names = "--port", defaultValue = "5432",
      description = "PostgreSQL port for --pull-metadata (default 5432).")
  private int port;

  @Option(names = "--database", paramLabel = "<db>",
      description = "Database to introspect (catalog in the generated YAML).")
  private String database;

  @Option(names = "--user", paramLabel = "<user>",
      description = "PostgreSQL user for --pull-metadata.")
  private String user;

  @Option(names = "--password", paramLabel = "<pw>",
      description = "Password for --pull-metadata; falls back to $PGPASSWORD.")
  private String password;

  @Option(names = "--schema", arity = "1..*", paramLabel = "<schema>",
      description = "Schema filter; repeatable. Absent means all non-system schemas.")
  private List<String> schemas;

  @Option(names = "--include-views",
      description = "Also include views and materialized views.")
  private boolean includeViews;

  @Option(names = "--strict",
      description = "Fail when any column type degrades to varchar.")
  private boolean strict;

  @Option(names = "--sslmode", defaultValue = "disable",
      description = "JDBC sslmode: disable|require|prefer|verify-full (default disable).")
  private String sslmode;

  @Option(names = "--connect-timeout", defaultValue = "10",
      description = "Connection timeout in seconds (default 10).")
  private int connectTimeout;
```

同时把 `--metadata` 的 `required = true` 删除。`import java.util.List;`。

`execute()` 重构为分派：

```java
  private int execute(PrintStream out, PrintStream err) throws IOException {
    if (pullMetadata) {
      if (sql != null || inputPath != null) {
        err.println("sql-mask: --pull-metadata cannot be combined with --sql/--input");
        return 2;
      }
      return executePullMetadata(out, err);
    }
    if (metadataPath == null) {
      err.println("sql-mask: --metadata is required for rewriting");
      return 2;
    }
    // …… 原有改写逻辑不变
  }

  private int executePullMetadata(PrintStream out, PrintStream err) throws IOException {
    if (sql != null || inputPath != null) {
      return 2; // unreachable; guarded in execute()
    }
    if (database == null || user == null) {
      err.println("sql-mask: --pull-metadata requires --database and --user");
      return 2;
    }
    if (outputPath == null) {
      err.println("sql-mask: --pull-metadata requires --output");
      return 2;
    }
    String resolvedPassword = password != null ? password : System.getenv("PGPASSWORD");
    if (resolvedPassword == null || resolvedPassword.isBlank()) {
      err.println("sql-mask: provide --password or set PGPASSWORD");
      return 2;
    }
    ConnectionSpec spec = new ConnectionSpec(host, port, database, user, resolvedPassword,
        schemas == null ? List.of() : schemas, includeViews, strict, sslmode, connectTimeout);
    IntrospectionResult result;
    try {
      result = new PgMetadataIntrospector().introspect(spec);
    } catch (SqlMaskException e) {
      errStream.println("sql-mask: [" + e.getCode() + "] " + e.getMessage());
      return 1;
    }
    if (strict && result.warnings().stream().anyMatch(w -> w.endsWith("degraded to varchar"))) {
      err.println("sql-mask: [STRICT_DEGRADED] " + result.warnings().size()
          + " column(s) degraded; rerun without --strict to export anyway");
      result.warnings().forEach(err::println);
      return 1;
    }
    result.warnings().forEach(err::println);
    String yaml = new MetadataYamlGenerator().generate(result);
    Files.writeString(outputPath, yaml + "\n", StandardCharsets.UTF_8);
    int columnCount = result.tables().stream().mapToInt(t -> t.columns().size()).sum();
    out.println("introspected " + result.tables().size() + " tables / "
        + columnCount + " columns / " + result.warnings().size() + " warnings");
    return 0;
  }
```

写文件放在 introspect 与 strict 校验全部通过之后——失败路径不创建/不覆盖输出。类头 `@Command description` 更新为提及两种模式。import 增加 `io.sqlmask.introspect.*`、`java.util.List`。

- [ ] **Step 4: 运行确认通过 + 全量回归**

Run: `mvn test -Dtest=MetadataExportCliTest && mvn test`
Expected: PASS（CLI 用例 + 既有全量）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/sqlmask/cli/SqlMaskApplication.java src/test/java/io/sqlmask/cli/MetadataExportCliTest.java
git commit -m "feat: CLI --pull-metadata 导出参数组与退出码约定"
```

---

### Task 6: Web API（POST /api/metadata/pull）

**Files:**
- Create: `src/main/java/io/sqlmask/server/MetadataController.java`
- Test: `src/test/java/io/sqlmask/server/MetadataControllerTest.java`

**Interfaces:**
- Consumes: Task 1/3/4 全部
- Produces: `POST /api/metadata/pull`。请求 `MetadataPullRequest(String host, Integer port, String database, String user, String password, List<String> schemas, boolean includeViews)`（host 缺省 `127.0.0.1`，port 缺省 `5432`，schemas null→空）。响应 `MetadataPullResponse(String yaml, int tableCount, int columnCount, List<String> warnings, String catalog)`。失败抛 `SqlMaskException(INTROSPECT_ERROR)` 由既有 `ApiExceptionHandler` 转 400——无需改 handler。参数校验失败（database/user/password 空白）抛 `CONFIG_ERROR`。

- [ ] **Step 1: 写失败测试（MockMvc standalone + 覆盖 open() 的 stub introspector）**

```java
package io.sqlmask.server;

import io.sqlmask.introspect.IntrospectionResult;
import io.sqlmask.introspect.PgMetadataIntrospector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.sql.Connection;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MetadataControllerTest {

  private MockMvc mvc;
  private PgMetadataIntrospector introspector;

  @BeforeEach
  void setUp() {
    introspector = new PgMetadataIntrospector() {
      @Override protected Connection open(io.sqlmask.introspect.ConnectionSpec spec) {
        throw new IllegalStateException("not expected in web test");
      }
    };
    // 用可编程 stub 替换：见 introspector() 工厂
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(introspector))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  private static IntrospectionResult sample() {
    return new IntrospectionResult("crm", List.of(
        new IntrospectionResult.TableInfo("crm", "public", "customer", List.of(
            new IntrospectionResult.ColumnInfo("id", "bigint", "bigint", false),
            new IntrospectionResult.ColumnInfo("extra", "varchar", "jsonb", true)))),
        List.of("column crm.public.customer.extra: PG type jsonb is not representable, degraded to varchar"));
  }

  @Test
  void returnsYamlCountsAndWarnings() throws Exception {
    introspector = stubReturning(sample());
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(introspector))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    mvc.perform(post("/api/metadata/pull").contentType("application/json").content("""
        {"host":"127.0.0.1","port":5432,"database":"crm","user":"postgres","password":"pw"}
        """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.catalog").value("crm"))
        .andExpect(jsonPath("$.tableCount").value(1))
        .andExpect(jsonPath("$.columnCount").value(2))
        .andExpect(jsonPath("$.warnings.length()").value(1))
        .andExpect(jsonPath("$.yaml").value(org.mockito.ArgumentMatchers.containsString("policies: {}")));
  }

  @Test
  void blankDatabaseIsConfigError() throws Exception {
    introspector = stubReturning(sample());
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(introspector))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    mvc.perform(post("/api/metadata/pull").contentType("application/json").content("""
        {"database":" ","user":"postgres","password":"pw"}
        """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void connectionFailureIsIntrospectError400() throws Exception {
    introspector = new PgMetadataIntrospector() {
      @Override protected Connection open(io.sqlmask.introspect.ConnectionSpec spec) throws java.sql.SQLException {
        throw new java.sql.SQLException("FATAL: password authentication failed");
      }
    };
    mvc = MockMvcBuilders.standaloneSetup(new MetadataController(introspector))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    mvc.perform(post("/api/metadata/pull").contentType("application/json").content("""
        {"database":"crm","user":"postgres","password":"bad"}
        """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INTROSPECT_ERROR"));
  }

  private PgMetadataIntrospector stubReturning(IntrospectionResult result) {
    return new PgMetadataIntrospector() {
      @Override protected Connection open(io.sqlmask.introspect.ConnectionSpec spec) {
        throw new IllegalStateException("open() must not be called; introspect() is stubbed");
      }
      @Override public IntrospectionResult introspect(io.sqlmask.introspect.ConnectionSpec spec) {
        return result;
      }
    };
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=MetadataControllerTest`
Expected: 编译失败 `cannot find symbol: class MetadataController`

- [ ] **Step 3: 实现**

```java
package io.sqlmask.server;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.introspect.ConnectionSpec;
import io.sqlmask.introspect.IntrospectionResult;
import io.sqlmask.introspect.MetadataYamlGenerator;
import io.sqlmask.introspect.PgMetadataIntrospector;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Pulls table/column metadata from a live PostgreSQL database and returns a
 * skeleton YAML for the editor. The password lives only inside this request;
 * nothing is logged and nothing is echoed back.
 */
@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

  private final PgMetadataIntrospector introspector;

  public MetadataController(PgMetadataIntrospector introspector) {
    this.introspector = introspector;
  }

  @PostMapping("/pull")
  public MetadataPullResponse pull(@RequestBody MetadataPullRequest request) {
    if (request == null || request.database() == null || request.database().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "database is required");
    }
    if (request.user() == null || request.user().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "user is required");
    }
    if (request.password() == null || request.password().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "password is required");
    }
    ConnectionSpec spec = new ConnectionSpec(
        request.host() == null || request.host().isBlank() ? "127.0.0.1" : request.host(),
        request.port() == null ? 5432 : request.port(),
        request.database(), request.user(), request.password(),
        request.schemas() == null ? List.of() : request.schemas(),
        request.includeViews(), false, "disable", 10);
    IntrospectionResult result = introspector.introspect(spec);
    int columnCount = result.tables().stream().mapToInt(t -> t.columns().size()).sum();
    return new MetadataPullResponse(new MetadataYamlGenerator().generate(result),
        result.tables().size(), columnCount, result.warnings(), result.catalog());
  }

  public record MetadataPullRequest(String host, Integer port, String database, String user,
      String password, List<String> schemas, boolean includeViews) {
  }

  public record MetadataPullResponse(String yaml, int tableCount, int columnCount,
      List<String> warnings, String catalog) {
  }
}
```

生产装配（`SqlMaskServiceApplication` 或等价配置类）补一个 `@Bean PgMetadataIntrospector`（默认构造）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn test -Dtest=MetadataControllerTest`
Expected: PASS（3 tests）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/sqlmask/server/MetadataController.java src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java src/test/java/io/sqlmask/server/MetadataControllerTest.java
git commit -m "feat: POST /api/metadata/pull 元数据拉取 API"
```

---

### Task 7: 前端「从数据库导入」

**Files:**
- Modify: `src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `POST /api/metadata/pull`（Task 6）、既有 `/api/config/parse`、`fromServerConfig()`、`renderAll()`
- Produces: 「从数据库导入」按钮 + `<dialog id="import-dialog">` 连接表单 + 合并函数 `mergeTables(incomingTables)` + 警告列表容器 `#import-warnings`。合并键 `(catalog|schema|name).toLowerCase()`，命中即整体覆盖，其余保留原序。

- [ ] **Step 1: HTML 增量**

`panel-tables` 的 add-row 改为：

```html
<div class="add-row">
  <button class="btn" data-action="addTable">＋ 添加表</button>
  <button class="btn" data-action="openImport">从数据库导入</button>
</div>
<div id="import-warnings"></div>
```

`</main>` 之后加弹窗：

```html
<dialog id="import-dialog" style="border:none;border-radius:10px;padding:0;max-width:420px;width:92%">
  <form method="dialog" style="padding:18px">
    <h3 style="margin:0 0 12px;font-size:15px">连接 PostgreSQL 拉取元数据</h3>
    <div class="row"><input id="imp-host" placeholder="host，默认 127.0.0.1">
      <input id="imp-port" placeholder="port，默认 5432" style="flex:0 0 110px"></div>
    <div class="row"><input id="imp-database" placeholder="database（必填）">
      <input id="imp-user" placeholder="user（必填）"></div>
    <div class="row"><input id="imp-password" type="password" placeholder="password（必填）"></div>
    <div class="row"><input id="imp-schemas" placeholder="schema 过滤，逗号分隔，留空=全部">
      <label class="fixed" style="font-size:12px"><input type="checkbox" id="imp-views" style="margin-right:4px">含视图</label></div>
    <div class="actions">
      <button class="btn primary" type="button" id="imp-submit">连接并导入</button>
      <button class="btn" type="button" id="imp-cancel">取消</button>
      <span id="imp-status" style="font-size:12.5px;color:var(--muted)"></span>
    </div>
    <div id="imp-error" class="error" style="margin-top:10px" hidden></div>
  </form>
</dialog>
```

- [ ] **Step 2: JS 增量（放在 `// ---------- rewrite ----------` 之前）**

```js
// ---------- database metadata import ----------
async function importFromDatabase() {
  const database = $("imp-database").value.trim();
  const user = $("imp-user").value.trim();
  const password = $("imp-password").value;
  const problems = [];
  if (!database) problems.push("database 必填");
  if (!user) problems.push("user 必填");
  if (!password) problems.push("password 必填");
  $("imp-error").hidden = true;
  if (problems.length) {
    $("imp-error").hidden = false;
    $("imp-error").textContent = problems.join("；");
    return;
  }
  const schemas = $("imp-schemas").value.split(",").map((s) => s.trim()).filter(Boolean);
  $("imp-status").textContent = "连接中…";
  $("imp-submit").disabled = true;
  try {
    const res = await fetch("/api/metadata/pull", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        host: $("imp-host").value.trim() || "127.0.0.1",
        port: parseInt($("imp-port").value, 10) || 5432,
        database, user, password, schemas,
        includeViews: $("imp-views").checked
      })
    });
    const payload = await res.json().catch(() => null);
    if (!res.ok) {
      $("imp-error").hidden = false;
      $("imp-error").innerHTML = "<b>[" + esc(payload && payload.code || "ERROR") + "]</b> "
          + esc(payload && payload.message || "HTTP " + res.status);
      $("imp-status").textContent = "";
      return;
    }
    const parsed = await fetch("/api/config/parse", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ metadataYaml: payload.yaml })
    });
    const config = await parsed.json().catch(() => null);
    if (!parsed.ok) {
      $("imp-error").hidden = false;
      $("imp-error").innerHTML = "<b>[CONFIG_ERROR]</b> 拉取成功但生成的 YAML 未通过校验："
          + esc(config && config.message || "HTTP " + parsed.status);
      $("imp-status").textContent = "";
      return;
    }
    mergeTables(fromServerConfig(config).tables);
    renderAll();
    renderImportWarnings(payload.warnings || []);
    $("imp-status").textContent = "导入 " + payload.tableCount + " 张表";
    $("import-dialog").close();
  } catch (err) {
    $("imp-error").hidden = false;
    $("imp-error").textContent = "无法访问服务：" + err.message;
    $("imp-status").textContent = "";
  } finally {
    $("imp-submit").disabled = false;
  }
}

function mergeTables(incoming) {
  const key = (t) => String(t.catalog).toLowerCase() + "|"
      + String(t.schema).toLowerCase() + "|" + String(t.name).toLowerCase();
  const map = new Map(state.tables.map((t) => [key(t), t]));
  for (const t of incoming) map.set(key(t), t);
  state.tables = [...map.values()];
}

function renderImportWarnings(warnings) {
  const host = $("import-warnings");
  if (!warnings.length) {
    host.innerHTML = "";
    return;
  }
  host.innerHTML = `<div class="card" style="border-color: var(--warn)">
    <div class="card-head" style="color: var(--warn)">
      <span>导入警告（${warnings.length}）</span><span class="spacer"></span>
      <button class="btn danger sm" data-action="clearImportWarnings">关闭</button>
    </div>
    ${warnings.map((w) => `<div style="font-size:12.5px;word-break:break-all">${esc(w)}</div>`).join("")}
  </div>`;
}
```

事件绑定：`document.addEventListener("click", ...)` 的分支链里加：

```js
  else if (a === "openImport") { $("import-dialog").showModal(); }
  else if (a === "clearImportWarnings") { $("import-warnings").innerHTML = ""; return; }
```

弹窗按钮绑定（脚本尾部、`state = SAMPLE_STATE();` 之前）：

```js
$("imp-submit").addEventListener("click", importFromDatabase);
$("imp-cancel").addEventListener("click", () => $("import-dialog").close());
```

- [ ] **Step 3: 文案更新**

`<header>` 副标题中 `不连接数据库、不执行 SQL` 改为 `元数据只读采集、不执行业务 SQL`；`<footer>` API 列表加 `· <code>POST /api/metadata/pull</code>（元数据拉取）`。

- [ ] **Step 4: 手动验证**

Run: `mvn package -DskipTests && java -jar target/sql-mask.jar --server.port=9090`（另一终端先开隧道 `ssh -N -L 5432:127.0.0.1:5432 root@47.100.166.158`）
浏览器 `http://localhost:9090`：表结构页签点「从数据库导入」→ 填 `crm/postgres/PgTest2026` → 连接并导入 → 编辑器出现 crm 4 张表、警告列表显示（如有）→ YAML 源码视图同步 → 再次导入 `tpcds` → 两个 catalog 的表共存。错误路径：故意填错密码 → 表单内显示 `[INTROSPECT_ERROR]`。

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat: 页面从数据库导入元数据（弹窗 + catalog 合并 + 警告展示）"
```

---

### Task 8: README 更新 + 手动端到端验收

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: 全部前序任务
- Produces: 文档与验收记录（验收输出贴进提交说明）

- [ ] **Step 1: README 增量**

1. 开头两种使用方式列表后新增小节 `## 元数据采集（--pull-metadata）`，内容包含：一句话定位（从 PG 拉库表结构生成骨架 YAML，策略/rowFilter 人工后补）；CLI 用法代码块（Task 5 命令 + `--schema`/`--include-views`/`--strict` 说明）；密码来源说明（`--password` 优先于 `PGPASSWORD`）；行为要点（类型映射清单引用 spec 第 3 节、不可映射降级 varchar + 警告、0 表空骨架、输出确定性逐字节可 diff）。
2. `POST /api/rewrite` 小节之后加 `### POST /api/metadata/pull`：请求/响应 JSON（Task 6 形态）、失败码 `INTROSPECT_ERROR`。
3. 错误码列表追加 `INTROSPECT_ERROR`（元数据采集失败）、`STRICT_DEGRADED`（--strict 命中降级）。
4. 「改写语义」之前的工具定位句「工具只做解析、校验、血缘分析和 SQL 输出，不连接查询引擎、不执行 SQL」改为「工具只做解析、校验、血缘分析和 SQL 输出，从不执行业务 SQL；唯一的数据库访问是 `--pull-metadata` 的只读元数据采集」。
5. Web 服务小节的页面功能列表加一条「从数据库导入」。

- [ ] **Step 2: 手动端到端验收（先开隧道，远程 PG 为 47.100.166.158 的 pg-mask 容器，密码 PgTest2026）**

```bash
ssh -N -L 5432:127.0.0.1:5432 root@47.100.166.158 &   # 隧道
mvn package -DskipTests
java -jar target/sql-mask.jar --pull-metadata --host 127.0.0.1 \
  --database crm --user postgres --password 'PgTest2026' --output crm.yaml
java -jar target/sql-mask.jar --pull-metadata --host 127.0.0.1 \
  --database tpcds --user postgres --password 'PgTest2026' --output tpcds.yaml
```

验收断言（逐条核对）：

1. `crm.yaml` 含 customer/orders/other/archive 四表，列名、列序与
   `/root/pg-init/02_crm.sql` 一致（如 customer: id,name,phone,email,id_card,
   status,region,manager_id,created_at,address；phone → `varchar(20)`）；
2. 重跑 crm 导出，`diff` 无输出（逐字节一致）；
3. `docker exec pg-mask psql -U postgres -d crm -c "ALTER TABLE customer ADD COLUMN tags jsonb;"`
   后重跑：YAML 出现 `tags` 且类型 `varchar`，stderr 出现 `... PG type jsonb is not
   representable, degraded to varchar`；加 `--strict` 重跑退出码 1 且 stderr 有
   `STRICT_DEGRADED`；验收后删列还原
   `ALTER TABLE customer DROP COLUMN tags;`；
4. 用 `crm.yaml` 作 `--metadata` 跑 `--sql "SELECT id, phone FROM customer WHERE status = 'active';"`
   改写成功（骨架即合法 metadata）；
5. `tpcds.yaml` 含 9 表，与 `tpcds/metadata.yaml` 表清单一致；
6. `mvn test` 全绿。

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: 元数据采集用法与验收记录"
```

---

## Self-Review 记录

1. **Spec 覆盖**：spec §1 架构→Task 1/3/4；§2 抓取 SQL→Task 3；§3 类型映射→Task 2；§4 YAML 生成→Task 4；§5 CLI→Task 5；§6 Web→Task 6/7；§7 错误处理→Task 3（INTROSPECT_ERROR）+ Task 5（STRICT_DEGRADED、0 表）+ Task 6（400 语义）；§8 测试→各任务 + Task 8 手动验收。无缺口。
2. **占位符**：Task 3 Step 4 与 Task 5 Step 3 内的「实现备注」是对誊写歧义的显式裁决，不是 TODO；Task 5 的 PGPASSWORD 测试限制已给出确定性执行约定。
3. **类型一致性**：`ConnectionSpec` 10 参构造在 Task 3/5/6 使用处一致；`IntrospectionResult` 三层 record 与 Task 4/5/6 使用一致；`PgTypeMapper.Mapped(yamlType, degraded)` 与 Task 3 调用一致；警告文案（"degraded to varchar" 结尾）在 Task 3 生成、Task 5 strict 判定 `endsWith` 依赖，一致。
