# 多引擎方言扩展（SPI 重构 + Trino/MySQL）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把方言能力从 PostgreSQL 扩展到 Trino 与 MySQL：引入声明式 `DialectProfile` SPI，重构 PostgreSQL 适配到该 SPI（行为零变化），新增 Trino / MySQL 两个方言，并补齐跨方言测试与 API/前端入口。

**Architecture:** 单条 Calcite 解析→校验→血缘→改写→渲染管线不变；每个引擎是一个 `DialectProfile`（parser 配置、校验 conformance、函数表、类型解析器、SqlDialect、标识符策略、搜索路径风格、能力声明）。`AbstractCalciteDialectAdapter` 承载通用流程，方言子类只声明 profile 与少量钩子。包装层仍以「原文快照作内层 + 字符串组装投影」输出，投影项的标识符/UDF 名/字面量渲染走方言。

**Tech Stack:** Java 17、Apache Calcite 1.42（内置 `TrinoSqlDialect`/`MysqlSqlDialect`/`SqlLibrary.MYSQL`/`SqlConformanceEnum.MYSQL_5`，均已用本地 jar 核实存在）、`io.trino:trino-parser`（仅 test 作用域）、JUnit 5、Spring Boot 3.3、picocli。

**Spec:** `docs/superpowers/specs/2026-09-06-multi-dialect-design.md`（含 §10 自评审待办项——本计划的验证任务直接对应它）

## Global Constraints

- Java 17；Calcite 版本钉死 1.42.0，不得升级；主代码不得新增依赖（唯一新增依赖是 Task 7 的 `io.trino:trino-parser`，scope=test）。
- PostgreSQL 行为零变化：`src/test/resources/golden/` 下全部 golden 文件（含 TPC-DS）在所有任务中保持 byte 级不变（Task 9 新增的 trino/mysql golden 除外）。`normalizeLineEndings` 之外的任何 golden diff 都意味着重构错误。
- 每个任务结束 `mvn test` 必须全绿后才能 commit；提交信息沿用仓库惯例（中文 + conventional commits 前缀）。
- 方言名固定小写：`postgresql`、`trino`、`mysql`；查表大小写不敏感。
- 安全策略跨方言不变：血缘 `UNKNOWN` 失败、重复输出列名需包装时失败（`canWrapDuplicateOutputNames=false`）、写入语句语义、整体失败无部分结果。
- 错误诊断信息必须带方言名（解析错误等），未知方言报 `CONFIG_ERROR` 并列出全部支持名。

---

### Task 1: DialectProfile 基础设施 + PostgreSQL 适配重构（行为零变化）

**Files:**
- Create: `src/main/java/io/sqlmask/dialect/DialectProfile.java`
- Create: `src/main/java/io/sqlmask/dialect/IdentifierPolicy.java`
- Create: `src/main/java/io/sqlmask/dialect/PostgresqlIdentifierPolicy.java`
- Create: `src/main/java/io/sqlmask/dialect/TypeResolver.java`
- Create: `src/main/java/io/sqlmask/dialect/PostgresqlTypeResolver.java`
- Create: `src/main/java/io/sqlmask/dialect/AbstractCalciteDialectAdapter.java`
- Create: `src/main/java/io/sqlmask/dialect/DialectRegistry.java`
- Create: `src/main/java/io/sqlmask/dialect/DialectProfiles.java`
- Modify: `src/main/java/io/sqlmask/dialect/DialectAdapter.java`
- Modify: `src/main/java/io/sqlmask/dialect/PostgresqlDialectAdapter.java`
- Modify: `src/main/java/io/sqlmask/metadata/TableMetadata.java`
- Modify: `src/main/java/io/sqlmask/sql/SqlValidatorFactory.java`
- Modify: `src/main/java/io/sqlmask/rewrite/RewriteEngine.java`
- Delete: `src/main/java/io/sqlmask/metadata/YamlCalciteSchemaFactory.java` 中的 `schemaPaths` 静态方法（全仓库无调用方，grep 已核实；路径生成统一收口到适配器）
- Test: `src/test/java/io/sqlmask/dialect/DialectRegistryTest.java`（新建）；全量既有测试（回归锁）

**Interfaces:**
- Produces: `DialectProfile`（record，字段见下）、`DialectAdapter.profile()`、`DialectRegistry.create(String name) -> DialectAdapter`、`DialectProfiles.byName(String) -> DialectProfile`、`TypeResolver.parseColumn(String name, String typeDeclaration) -> TableMetadata.Column`、`IdentifierPolicy.render(String)/renderQualified(String,String)`、`AbstractCalciteDialectAdapter`（子类只需构造 profile + 可覆写 `checkCreateTableVariant`）。
- 后续任务依赖：Task 2 用 `dialect.profile().identifierPolicy()/sqlDialect()`；Task 4/5 新增 profile 实现；Task 3 的 loader 用 `DialectProfiles.byName(...).typeResolver()`。

- [ ] **Step 1: 写失败测试（注册表）**

```java
package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialectRegistryTest {

  @Test
  void createsPostgresqlAdapterByNameCaseInsensitively() {
    assertEquals("postgresql", DialectRegistry.create("PostgreSQL").name());
  }

  @Test
  void unknownDialectFailsWithSupportedList() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> DialectRegistry.create("oracle"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("postgresql"), () -> e.getMessage());
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=DialectRegistryTest`
Expected: FAIL（`DialectRegistry` 不存在，编译错误）

- [ ] **Step 3: 实现 DialectProfile / IdentifierPolicy / TypeResolver / 注册表 / 抽象基类**

`DialectProfile.java`：

```java
package io.sqlmask.dialect;

import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformance;

/**
 * Declarative description of one query engine's SQL dialect. All engine
 * differences live here; the rewrite pipeline stays dialect-agnostic.
 */
public record DialectProfile(
    String name,
    SqlParser.Config parserConfig,
    SqlConformance validatorConformance,
    boolean caseSensitiveNameMatching,
    SqlOperatorTable functionTable,
    TypeResolver typeResolver,
    SqlDialect sqlDialect,
    IdentifierPolicy identifierPolicy,
    SchemaPathStyle schemaPathStyle,
    DialectCapabilities capabilities) {

  /**
   * Search paths for unqualified table references: {@code CATALOG_SCHEMA}
   * yields {@code [catalog, schema]} pairs (PostgreSQL/Trino);
   * {@code CATALOG_SCHEMA_AND_SCHEMA} additionally yields one-element
   * {@code [schema]} paths so MySQL two-part names ({@code db.table}) resolve.
   */
  public enum SchemaPathStyle { CATALOG_SCHEMA, CATALOG_SCHEMA_AND_SCHEMA }
}
```

`IdentifierPolicy.java`：

```java
package io.sqlmask.dialect;

/**
 * Renders identifiers of the generated outer projection in one dialect's
 * rules (quoting style, reserved words). The inner query is the user's
 * original text and is never re-rendered.
 */
public interface IdentifierPolicy {

  /** Renders a single identifier. */
  String render(String name);

  /** Renders {@code alias.name}. */
  default String renderQualified(String alias, String name) {
    return render(alias) + "." + render(name);
  }
}
```

`TypeResolver.java`：

```java
package io.sqlmask.dialect;

import io.sqlmask.metadata.TableMetadata;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses one engine's type declaration into a Calcite type descriptor. */
public interface TypeResolver {

  TableMetadata.Column parseColumn(String name, String typeDeclaration);

  /** Base type name plus optional {@code (precision[, scale])} parameters. */
  record ParsedType(String base, Integer precision, Integer scale) {
  }

  Pattern PARAMS =
      Pattern.compile("^(.+?)\\s*\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\)$");

  /**
   * Splits an already-lowercased declaration: strips the optional trailing
   * {@code suffix} (e.g. {@code " with time zone"}), then matches
   * {@code base(p[, s])}. Returns null when the suffix is required but absent.
   */
  static ParsedType split(String lowered, String suffix) {
    String rest = lowered;
    if (suffix != null) {
      if (!lowered.endsWith(suffix)) {
        return null;
      }
      rest = lowered.substring(0, lowered.length() - suffix.length()).trim();
    }
    Matcher matcher = PARAMS.matcher(rest);
    if (matcher.matches()) {
      Integer precision = Integer.valueOf(matcher.group(2));
      Integer scale = matcher.group(3) == null ? null : Integer.valueOf(matcher.group(3));
      return new ParsedType(matcher.group(1).trim(), precision, scale);
    }
    return new ParsedType(rest, null, null);
  }
}
```

`PostgresqlIdentifierPolicy.java`（把 `SqlIdentifierRenderer` 的逻辑原样搬来；Task 2 会删除旧类）：

```java
package io.sqlmask.dialect;

import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;

import java.util.Locale;
import java.util.Set;

/**
 * PostgreSQL quoting rules: plain lower-case identifiers stay unquoted,
 * everything else (mixed case, special characters, reserved words) is
 * double-quoted.
 */
public final class PostgresqlIdentifierPolicy implements IdentifierPolicy {

  /** PostgreSQL fully reserved keywords that cannot appear unquoted. */
  private static final Set<String> RESERVED = Set.of(
      "ALL", "ANALYSE", "ANALYZE", "AND", "ANY", "ARRAY", "AS", "ASC",
      "ASYMMETRIC", "BOTH", "CASE", "CAST", "CHECK", "COLLATE", "COLUMN",
      "CONSTRAINT", "CREATE", "CURRENT_CATALOG", "CURRENT_DATE",
      "CURRENT_ROLE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER",
      "DEFAULT", "DEFERRABLE", "DESC", "DISTINCT", "DO", "ELSE", "END",
      "EXCEPT", "FALSE", "FETCH", "FOR", "FOREIGN", "FROM", "GRANT", "GROUP",
      "HAVING", "IN", "INITIALLY", "INTERSECT", "INTO", "LATERAL", "LEADING",
      "LIMIT", "LOCALTIME", "LOCALTIMESTAMP", "NOT", "NULL", "OFFSET", "ON",
      "ONLY", "OR", "ORDER", "PLACING", "PRIMARY", "REFERENCES", "RETURNING",
      "SELECT", "SESSION_USER", "SOME", "SYMMETRIC", "TABLE", "THEN", "TO",
      "TRAILING", "TRUE", "UNION", "UNIQUE", "USER", "USING", "VARIADIC",
      "WHEN", "WHERE", "WINDOW", "WITH");

  private static final String PLAIN_IDENTIFIER = "[a-z_][a-z0-9_$]*";

  @Override
  public String render(String name) {
    if (!name.matches(PLAIN_IDENTIFIER)
        || RESERVED.contains(name.toUpperCase(Locale.ROOT))) {
      return PostgresqlSqlDialect.DEFAULT.quoteIdentifier(name);
    }
    return name;
  }
}
```

`PostgresqlTypeResolver.java`（`TableMetadata.parseColumn` 的逻辑逐行搬入；行为必须等价）：

```java
package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.TableMetadata;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.Locale;

/** PostgreSQL scalar type declarations (the historical tool vocabulary). */
public final class PostgresqlTypeResolver implements TypeResolver {

  @Override
  public TableMetadata.Column parseColumn(String name, String typeDeclaration) {
    String raw = typeDeclaration.trim();
    String lowered = raw.toLowerCase(Locale.ROOT);
    ParsedType parsed = split(lowered, null);
    String base = parsed.base();
    Integer precision = parsed.precision();
    Integer scale = parsed.scale();
    switch (base) {
      case "boolean", "bool" -> requireNoParams(lowered, "boolean");
      case "smallint", "int2" -> requireNoParams(lowered, "smallint");
      case "integer", "int", "int4" -> requireNoParams(lowered, "integer");
      case "bigint", "int8" -> requireNoParams(lowered, "bigint");
      case "real", "float4" -> requireNoParams(lowered, "real");
      case "double precision", "double", "float8", "float" -> requireNoParams(lowered, "double precision");
      case "decimal", "numeric" -> {
        if (scale != null && precision == null) {
          throw parseError(raw);
        }
      }
      case "char", "character" -> precision = precision != null ? precision : 1;
      case "varchar", "character varying" -> { /* precision optional */ }
      case "text" -> { /* unbounded */ }
      case "date" -> requireNoParams(lowered, "date");
      case "timestamp" -> { /* precision optional */ }
      case "timestamptz", "timestamp with time zone" -> { /* precision optional */ }
      case "time" -> { /* precision optional */ }
      case "timetz", "time with time zone" -> { /* precision optional */ }
      default -> throw parseError(raw);
    }
    SqlTypeName typeName = switch (base) {
      case "boolean", "bool" -> SqlTypeName.BOOLEAN;
      case "smallint", "int2" -> SqlTypeName.SMALLINT;
      case "integer", "int", "int4" -> SqlTypeName.INTEGER;
      case "bigint", "int8" -> SqlTypeName.BIGINT;
      case "real", "float4" -> SqlTypeName.REAL;
      case "double precision", "double", "float8", "float" -> SqlTypeName.DOUBLE;
      case "decimal", "numeric" -> SqlTypeName.DECIMAL;
      case "char", "character" -> SqlTypeName.CHAR;
      case "varchar", "character varying", "text" -> SqlTypeName.VARCHAR;
      case "date" -> SqlTypeName.DATE;
      case "timestamp" -> SqlTypeName.TIMESTAMP;
      case "timestamptz", "timestamp with time zone" -> SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE;
      case "time" -> SqlTypeName.TIME;
      case "timetz", "time with time zone" -> SqlTypeName.TIME_WITH_LOCAL_TIME_ZONE;
      default -> throw parseError(raw);
    };
    return new TableMetadata.Column(name, typeName, precision, scale, raw);
  }

  private static void requireNoParams(String raw, String canonical) {
    if (raw.contains("(")) {
      throw parseError(raw);
    }
  }

  private static SqlMaskException parseError(String raw) {
    return new SqlMaskException(
        SqlMaskException.Code.CONFIG_ERROR,
        "unsupported or malformed type declaration '" + raw + "'; supported scalar types: "
            + "boolean, smallint, integer, bigint, real, double precision, decimal(p,s)/numeric(p,s), "
            + "char(n), varchar(n), text, date, timestamp[(p)], timestamp with time zone, time[(p)]");
  }
}
```

`AbstractCalciteDialectAdapter.java`（`PostgresqlDialectAdapter` 的通用逻辑上移；写入语句的三个方法对所有当前方言一致，直接放基类）：

```java
package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.sql.CteExpander;
import io.sqlmask.sql.SqlValidatorFactory;
import io.sqlmask.sql.ValidatedSql;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.ddl.SqlCreateTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialect-agnostic pipeline: parse (with the profile's parser config),
 * snapshot the original text, inline CTEs, validate and convert with the
 * profile's validator settings, and unparse with the profile's SqlDialect.
 * Subclasses declare a {@link DialectProfile} and may hook CREATE TABLE
 * variant checks.
 */
public abstract class AbstractCalciteDialectAdapter implements DialectAdapter {

  protected final DialectProfile profile;

  protected AbstractCalciteDialectAdapter(DialectProfile profile) {
    this.profile = profile;
  }

  @Override
  public final DialectProfile profile() {
    return profile;
  }

  @Override
  public final String name() {
    return profile.name();
  }

  @Override
  public final SqlNode parse(String sql, int statementOrdinal) {
    try {
      SqlNode node = SqlParser.create(sql, profile.parserConfig()).parseStmt();
      classify(node, statementOrdinal);
      return node;
    } catch (SqlParseException e) {
      throw new SqlMaskException(SqlMaskException.Code.PARSE_ERROR,
          "statement " + statementOrdinal + ": parse error (" + profile.name()
              + "): " + e.getMessage(), e);
    }
  }

  private void classify(SqlNode node, int statementOrdinal) {
    switch (node.getKind()) {
      case SELECT, INSERT -> {
        // accepted
      }
      case ORDER_BY -> {
        SqlNode query = ((org.apache.calcite.sql.SqlOrderBy) node).query;
        if (!isQuery(query)) {
          throw unsupported(query.getKind(), statementOrdinal);
        }
      }
      case WITH -> {
        SqlNode body = ((SqlWith) node).body;
        if (!isQuery(body)) {
          throw unsupported(body.getKind(), statementOrdinal);
        }
      }
      case CREATE_TABLE -> {
        if (((SqlCreateTable) node).query == null) {
          throw unsupported(node.getKind(), statementOrdinal);
        }
      }
      default -> throw unsupported(node.getKind(), statementOrdinal);
    }
  }

  private boolean isQuery(SqlNode node) {
    SqlKind kind = node.getKind();
    return kind == SqlKind.SELECT || kind == SqlKind.WITH || kind == SqlKind.ORDER_BY;
  }

  private SqlMaskException unsupported(SqlKind kind, int statementOrdinal) {
    return new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
        "statement " + statementOrdinal + ": unsupported statement kind " + kind
            + "; only SELECT and WITH ... SELECT queries are supported in this version");
  }

  @Override
  public final ValidatedSql validate(SqlNode parsed, SchemaPlus rootSchema) {
    // Calcite's validator mutates the parse tree in place, so snapshot the
    // original SQL text before anything touches the tree; this snapshot is
    // the inner query of a generated wrapper.
    String originalSql = unparse(parsed);
    // Calcite keeps CTE bodies out of the relational tree (transient scans),
    // so the analysis tree inlines CTEs into derived tables first.
    SqlNode analysisTree = new CteExpander().expand(parsed);
    SqlValidatorFactory factory = new SqlValidatorFactory(rootSchema,
        schemaPaths(rootSchema), profile.validatorConformance(),
        profile.caseSensitiveNameMatching(), profile.functionTable());
    org.apache.calcite.sql.validate.SqlValidator validator = factory.createValidator();
    SqlNode validated;
    try {
      validated = validator.validate(analysisTree);
    } catch (RuntimeException e) {
      throw new SqlMaskException(SqlMaskException.Code.VALIDATION_ERROR,
          "validation failed: " + e.getMessage(), e);
    }
    org.apache.calcite.sql2rel.SqlToRelConverter converter = factory.createConverter(validator);
    RelRoot root;
    try {
      root = converter.convertQuery(validated, false, true);
    } catch (RuntimeException e) {
      throw new SqlMaskException(SqlMaskException.Code.VALIDATION_ERROR,
          "query conversion failed: " + e.getMessage(), e);
    }
    if (root.rel.getRowType().getFieldCount() != root.validatedRowType.getFieldCount()) {
      throw new SqlMaskException(SqlMaskException.Code.VALIDATION_ERROR,
          "converted query does not match the validated output shape");
    }
    return new ValidatedSql(parsed, originalSql, validated, root, validator);
  }

  @Override
  public final SqlNode querySourceOf(SqlNode writeStatement) {
    switch (writeStatement.getKind()) {
      case INSERT: {
        SqlNode source = ((org.apache.calcite.sql.SqlInsert) writeStatement).getSource();
        return source != null && isQuery(source) ? source : null;
      }
      case CREATE_TABLE:
        return ((SqlCreateTable) writeStatement).query;
      default:
        throw unsupported(writeStatement.getKind(), 0);
    }
  }

  @Override
  public final boolean isPassThroughWrite(SqlNode writeStatement) {
    if (writeStatement.getKind() == SqlKind.INSERT) {
      SqlNode source = ((org.apache.calcite.sql.SqlInsert) writeStatement).getSource();
      // plain literal VALUES carry no base columns; but a query hidden inside
      // VALUES (subquery in an expression) must not slip through unmasked
      return source != null && source.getKind() == SqlKind.VALUES && !containsQuery(source);
    }
    return false;
  }

  private boolean containsQuery(SqlNode node) {
    if (node == null) {
      return false;
    }
    if (node.getKind() == SqlKind.SELECT || node.getKind() == SqlKind.WITH) {
      return true;
    }
    if (node instanceof SqlCall call) {
      for (SqlNode operand : call.getOperandList()) {
        if (containsQuery(operand)) {
          return true;
        }
      }
    }
    if (node instanceof SqlNodeList list) {
      for (SqlNode item : list) {
        if (containsQuery(item)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public final String composeWriteStatement(SqlNode writeStatement, String wrappedQuery) {
    switch (writeStatement.getKind()) {
      case INSERT: {
        org.apache.calcite.sql.SqlInsert insert =
            (org.apache.calcite.sql.SqlInsert) writeStatement;
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(unparse(insert.getTargetTable()));
        sql.append(renderColumnList(insert.getTargetColumnList()));
        sql.append(' ').append(wrappedQuery);
        return sql.toString();
      }
      case CREATE_TABLE: {
        SqlCreateTable create = (SqlCreateTable) writeStatement;
        checkCreateTableVariant(writeStatement);
        StringBuilder sql = new StringBuilder("CREATE TABLE ");
        if (create.ifNotExists) {
          sql.append("IF NOT EXISTS ");
        }
        sql.append(unparse(create.name));
        sql.append(renderColumnList(create.columnList));
        sql.append(" AS ").append(wrappedQuery);
        return sql.toString();
      }
      default:
        throw unsupported(writeStatement.getKind(), 0);
    }
  }

  /** Hook for dialect-specific CREATE TABLE variant rejection (default: none). */
  protected void checkCreateTableVariant(SqlNode writeStatement) {
  }

  private String renderColumnList(SqlNodeList columnList) {
    if (columnList == null || columnList.isEmpty()) {
      return "";
    }
    StringBuilder sql = new StringBuilder(" (");
    for (int i = 0; i < columnList.size(); i++) {
      if (i > 0) {
        sql.append(", ");
      }
      sql.append(unparse(columnList.get(i)));
    }
    return sql.append(')').toString();
  }

  @Override
  public final String unparse(SqlNode node) {
    return node.toSqlString(config -> config
        .withDialect(profile.sqlDialect())
        .withQuoteAllIdentifiers(false)
        .withAlwaysUseParentheses(false)
        .withSelectListItemsOnSeparateLines(false)
        .withUpdateSetListNewline(false)
        .withIndentation(0)).getSql();
  }

  @Override
  public final DialectCapabilities capabilities() {
    return profile.capabilities();
  }

  /**
   * Search paths derived from the schema tree, honoring the profile's style.
   * Order matters: the {@code [catalog, schema]} pair precedes the bare
   * {@code [schema]} path so fully-qualified resolution wins.
   */
  private List<List<String>> schemaPaths(SchemaPlus rootSchema) {
    List<List<String>> paths = new ArrayList<>();
    for (String catalog : rootSchema.getSubSchemaNames()) {
      SchemaPlus catalogSchema = rootSchema.getSubSchema(catalog);
      for (String schema : catalogSchema.getSubSchemaNames()) {
        paths.add(List.of(catalog, schema));
        if (profile.schemaPathStyle() == DialectProfile.SchemaPathStyle.CATALOG_SCHEMA_AND_SCHEMA) {
          paths.add(List.of(schema));
        }
      }
    }
    return paths;
  }
}
```

注意：`SqlInsert` 一律使用全限定名 `org.apache.calcite.sql.SqlInsert`（与现有 `PostgresqlDialectAdapter` 写法一致），不要写错包。

`DialectAdapter.java`：在接口中加 `profile()` 并让 `capabilities()` 变 default：

```java
  /** The declarative profile backing this adapter. */
  DialectProfile profile();

  /** What this dialect can safely express during rewriting. */
  default DialectCapabilities capabilities() {
    return profile().capabilities();
  }
```

`DialectProfiles.java`（按名取 profile；loader 在 Task 3 用它的 typeResolver）：

```java
package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Name -> profile lookup, case-insensitive, stable declaration order. */
public final class DialectProfiles {

  private static final Map<String, DialectProfile> PROFILES = new LinkedHashMap<>();

  static {
    PostgresqlDialectAdapter pg = new PostgresqlDialectAdapter();
    PROFILES.put(pg.name(), pg.profile());
  }

  public static DialectProfile byName(String name) {
    DialectProfile profile = name == null ? null : PROFILES.get(name.toLowerCase(Locale.ROOT));
    if (profile == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unsupported dialect '" + name + "'; supported dialects: "
              + String.join(", ", PROFILES.keySet()));
    }
    return profile;
  }

  public static java.util.Set<String> names() {
    return java.util.Collections.unmodifiableSet(PROFILES.keySet());
  }

  private DialectProfiles() {
  }
}
```

`DialectRegistry.java`：

```java
package io.sqlmask.dialect;

import java.util.function.Supplier;

/** Name -> adapter factory; the single place that instantiates dialects. */
public final class DialectRegistry {

  private static final Map<String, Supplier<DialectAdapter>> ADAPTERS = new LinkedHashMap<>();

  static {
    ADAPTERS.put(PostgresqlDialectAdapter.NAME, PostgresqlDialectAdapter::new);
  }

  public static DialectAdapter create(String name) {
    return newAdapter(DialectProfiles.byName(name));
  }

  private static DialectAdapter newAdapter(DialectProfile profile) {
    return switch (profile.name()) {
      case PostgresqlDialectAdapter.NAME -> new PostgresqlDialectAdapter();
      default -> throw new IllegalStateException("no adapter for " + profile.name());
    };
  }

  private DialectRegistry() {
  }
}
```

`TableMetadata.java` 修改：`Column` 变 5 组件（新增原始声明文本），`parseColumn` 委托给 resolver：

```java
  public record Column(String name, SqlTypeName sqlTypeName, Integer precision, Integer scale,
      String declaration) {

    public Column {
      if (precision != null && precision < 0) {
        throw new IllegalArgumentException("precision must be >= 0");
      }
      if (scale != null && scale < 0) {
        throw new IllegalArgumentException("scale must be >= 0");
      }
      declaration = declaration == null || declaration.isBlank() ? null : declaration;
    }

    /** Convenience constructor without an echoed declaration. */
    public Column(String name, SqlTypeName sqlTypeName, Integer precision, Integer scale) {
      this(name, sqlTypeName, precision, scale, null);
    }

    /** Echoes the declared type text when present, else reconstructs it. */
    public String typeDeclaration() {
      if (declaration != null) {
        return declaration;
      }
      // ... 保留现有的 switch 重构逻辑不变 ...
    }
  }

  /** Kept for compatibility: delegates to the PostgreSQL resolver. */
  public static Column parseColumn(String name, String typeDeclaration) {
    return new PostgresqlTypeResolver().parseColumn(name, typeDeclaration);
  }
```

同时删除 `YamlCalciteSchemaFactory.schemaPaths`（无调用方）。

`SqlValidatorFactory.java` 修改：

```java
  public SqlValidatorFactory(SchemaPlus rootSchema, List<List<String>> schemaPaths,
      org.apache.calcite.sql.validate.SqlConformance conformance,
      boolean caseSensitiveNameMatching,
      org.apache.calcite.sql.SqlOperatorTable functionTable) {
    this.rootSchema = CalciteSchema.from(rootSchema);
    this.schemaPaths = List.copyOf(schemaPaths);
    this.typeFactory = new JavaTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
    this.conformance = conformance;
    this.caseSensitiveNameMatching = caseSensitiveNameMatching;
    this.functionTable = functionTable;
  }

  public SqlValidator createValidator() {
    CalciteCatalogReader catalogReader = catalogReader();
    SqlOperatorTable operators = SqlOperatorTables.chain(
        functionTable,
        catalogReader,
        // engine-defined functions must not block validation: unknown names
        // resolve as opaque scalar UDFs (see UnknownFunctionTable)
        new UnknownFunctionTable(functionTable));
    SqlValidator.Config config = SqlValidator.Config.DEFAULT
        .withSqlConformance(conformance);
    return SqlValidatorUtil.newValidator(operators, catalogReader, typeFactory, config);
  }

  private CalciteCatalogReader catalogReader() {
    return new MultiSchemaPathCatalogReader(rootSchema, schemaPaths, typeFactory,
        caseSensitiveNameMatching);
  }
```
`MultiSchemaPathCatalogReader` 构造器接收 `caseSensitiveNameMatching` 并传给 `SqlNameMatchers.withCaseSensitive(...)`（替换现在硬编码的 `true`）。

`PostgresqlDialectAdapter.java` 重写为薄壳：

```java
public final class PostgresqlDialectAdapter extends AbstractCalciteDialectAdapter {

  public static final String NAME = "postgresql";

  public PostgresqlDialectAdapter() {
    super(new DialectProfile(
        NAME,
        SqlParser.config()
            .withParserFactory(SqlBabelParserImpl.FACTORY)
            .withQuoting(Quoting.DOUBLE_QUOTE)
            .withUnquotedCasing(Casing.TO_LOWER)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(true)
            .withConformance(SqlConformanceEnum.DEFAULT),
        SqlConformanceEnum.DEFAULT,
        true,
        PostgresqlFunctions.TABLE,
        new PostgresqlTypeResolver(),
        PostgresqlSqlDialect.DEFAULT,
        new PostgresqlIdentifierPolicy(),
        DialectProfile.SchemaPathStyle.CATALOG_SCHEMA,
        new DialectCapabilities(false)));
  }

  /** Refuses babel-only CREATE TABLE variants whose syntax the composer cannot reproduce. */
  @Override
  protected void checkCreateTableVariant(SqlNode writeStatement) {
    // requirePlainCreateTable 的现有实现原样搬入（SqlBabelCreateTable 检查）
  }
}
```

`RewriteEngine.java`：`createDialect(String)` 方法体改为 `return DialectRegistry.create(name);`（错误消息行为随之统一为列出支持方言）。

- [ ] **Step 4: 运行全量测试确认行为零变化**

Run: `mvn test`
Expected: 全部 PASS。特别核对 `GoldenOutputTest` 全绿（golden 文件不得改动）；若 golden diff，说明重构引入了行为变化，必须修复而不是重生成。
另确认 `DialectRegistryTest` PASS。若 `DialectRegistryTest.unknownDialectFailsWithSupportedList` 断言失败因错误消息措辞，按实现输出调整断言（保持"包含支持方言列表"的语义）。

- [ ] **Step 5: Commit**

```bash
git add -A src/
git commit -m "refactor: 引入 DialectProfile 方言 SPI 并将 PostgreSQL 适配迁移到该抽象"
```

---

### Task 2: SqlRewriteService 渲染方言化（标识符 + 字面量）

**Files:**
- Modify: `src/main/java/io/sqlmask/rewrite/SqlRewriteService.java`
- Delete: `src/main/java/io/sqlmask/rewrite/SqlIdentifierRenderer.java`
- Test: 既有 `GoldenOutputTest`（byte 锁）、`SqlRewriteServiceTest`（如引用旧 renderer 则同步修改）

**Interfaces:**
- Consumes: Task 1 的 `dialect.profile().identifierPolicy()`、`dialect.profile().sqlDialect()`。
- Produces: `SqlRewriteService.rewrite(ValidatedSql, RewritePlan, DialectAdapter)` 签名不变，行为按方言渲染。

- [ ] **Step 1: 修改 buildWrapper 与 renderUdfCall**

`SqlRewriteService.java` 关键改动（删除 `renderer` 字段与 `SqlIdentifierRenderer` 依赖）：

```java
  public String rewrite(ValidatedSql validated, RewritePlan plan, DialectAdapter dialect) {
    if (!plan.requiresWrapper()) {
      return validated.originalSql();
    }
    ensureWrapperIsSafe(plan, dialect);
    return buildWrapper(validated, plan, dialect);
  }

  private String buildWrapper(ValidatedSql validated, RewritePlan plan, DialectAdapter dialect) {
    io.sqlmask.dialect.IdentifierPolicy ids = dialect.profile().identifierPolicy();
    List<String> items = new ArrayList<>();
    for (OutputRewrite output : plan.outputs()) {
      String reference = ids.renderQualified(WRAPPER_ALIAS, output.outputName());
      if (output.isMasked()) {
        String call = renderUdfCall(output.policy().orElseThrow(), reference,
            ids, dialect.profile().sqlDialect());
        items.add(call + " AS " + ids.render(output.outputName()));
      } else {
        items.add(reference);
      }
    }
    return "SELECT " + String.join(", ", items)
        + " FROM (\n" + validated.originalSql() + "\n) AS " + ids.render(WRAPPER_ALIAS);
  }

  private String renderUdfCall(io.sqlmask.policy.MaskingPolicy policy, String reference,
      io.sqlmask.dialect.IdentifierPolicy ids, org.apache.calcite.sql.SqlDialect sqlDialect) {
    List<String> arguments = new ArrayList<>();
    arguments.add(reference);
    for (Object argument : policy.arguments()) {
      arguments.add(renderLiteral(argument, policy, sqlDialect));
    }
    return ids.render(policy.udf()) + "(" + String.join(", ", arguments) + ")";
  }

  private String renderLiteral(Object argument, io.sqlmask.policy.MaskingPolicy policy,
      org.apache.calcite.sql.SqlDialect sqlDialect) {
    SqlLiteral literal = toLiteral(argument, policy);
    return literal.toSqlString(config -> config
        .withDialect(sqlDialect)
        .withQuoteAllIdentifiers(false)
        .withAlwaysUseParentheses(false)
        .withIndentation(0)).getSql();
  }
```
（`toLiteral` 保持不变。注意：原实现用 `new PostgresqlSqlDialect(DEFAULT_CONTEXT)` 渲染字面量；`PostgresqlSqlDialect.DEFAULT` 与其等价，golden 测试会验证。）

- [ ] **Step 2: 修复引用旧类的测试并运行全量**

Run: `grep -rn "SqlIdentifierRenderer" src/` 找到残余引用（预期只有 `SqlRewriteServiceTest` 可能用于断言渲染）；按新接口改写或直接通过 `SqlRewriteService` 行为断言。
Run: `mvn test`
Expected: 全部 PASS，`GoldenOutputTest` 无 golden diff（PG 输出 byte 级不变）。

- [ ] **Step 3: Commit**

```bash
git add -A src/
git commit -m "refactor: 包装层标识符与字面量渲染改由方言 profile 提供"
```

---

### Task 3: YAML 配置按方言解析类型

**Files:**
- Modify: `src/main/java/io/sqlmask/config/YamlConfigLoader.java`
- Modify: `src/main/java/io/sqlmask/server/ConfigController.java`
- Test: `src/test/java/io/sqlmask/config/YamlConfigLoaderTest.java`（扩展）

**Interfaces:**
- Consumes: `DialectProfiles.byName(dialect).typeResolver()`。
- Produces: `loadContent(String yaml, String sourceName, String dialect)`、`load(Path, String dialect)`（旧签名保留并默认 `postgresql`）；`ConfigParseRequest(String metadataYaml, String dialect)`（dialect 可空）。
- 行为变化（有意、需 README 记录）：`typeDeclaration()` 回显原始声明文本——PG YAML 里 `text` 现在回显 `text`（原来规范化为 `varchar`）。

- [ ] **Step 1: 写失败测试**

在 `YamlConfigLoaderTest` 追加：

```java
  @Test
  void mysqlTypeNamesParseUnderMysqlDialect() {
    String yaml = """
        metadata:
          tables:
            - catalog: shop
              schema: app
              name: orders
              columns:
                - name: id
                  type: bigint
                - name: taken_at
                  type: datetime
                - name: memo
                  type: text
        policies: {}
        """;
    LoadedConfig loaded = new YamlConfigLoader().loadContent(yaml, "m.yaml", "mysql");
    assertEquals(SqlTypeName.TIMESTAMP,
        loaded.tables().get(0).columns().get(1).sqlTypeName());
    assertEquals("datetime", loaded.tables().get(0).columns().get(1).typeDeclaration());
  }

  @Test
  void mysqlTypeNameUnderPostgresDialectFails() {
    String yaml = """
        metadata:
          tables:
            - catalog: shop
              schema: app
              name: orders
              columns:
                - name: taken_at
                  type: datetime
        policies: {}
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new YamlConfigLoader().loadContent(yaml, "m.yaml", "postgresql"));
    assertTrue(e.getMessage().contains("datetime"), () -> e.getMessage());
  }

  @Test
  void unknownDialectInLoaderFailsWithList() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new YamlConfigLoader().loadContent("metadata: {tables: []}", "m.yaml", "oracle"));
    assertTrue(e.getMessage().contains("unsupported dialect"), () -> e.getMessage());
  }
```
注意：`metadata: {tables: []}` 空表列表会先触发既有"至少一张表"校验的话，把该用例的 YAML 换成 Task 1 测试同款单表（类型用 `bigint`），只针对 dialect 名本身断言。

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=YamlConfigLoaderTest`
Expected: FAIL（三参 `loadContent` 不存在）

- [ ] **Step 3: 实现 loader 方言参数**

```java
  public LoadedConfig load(Path path) {
    return load(path, "postgresql");
  }

  public LoadedConfig load(Path path, String dialect) {
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      return load(reader, path.toString(), dialect);
    } catch (IOException e) { /* 原有 IO_ERROR 逻辑不变 */ }
  }

  public LoadedConfig loadContent(String yamlContent, String sourceName) {
    return loadContent(yamlContent, sourceName, "postgresql");
  }

  public LoadedConfig loadContent(String yamlContent, String sourceName, String dialect) {
    return load(new java.io.StringReader(yamlContent), sourceName, dialect);
  }

  private LoadedConfig load(Reader reader, String sourceName, String dialect) {
    io.sqlmask.dialect.TypeResolver typeResolver =
        io.sqlmask.dialect.DialectProfiles.byName(dialect).typeResolver();
    // ... parse/loadTables/loadPolicies/loadColumnBindings 不变，loadTables 增传 typeResolver ...
  }
```
`loadTables` 内列类型解析处替换：

```java
        try {
          columns.add(typeResolver.parseColumn(columnName, columnType));
        } catch (SqlMaskException e) { /* 原有包装逻辑不变 */ }
```

`ConfigController.parse`：

```java
  public ConfigResponse parse(@RequestBody ConfigParseRequest request) {
    if (request == null || request.metadataYaml() == null || request.metadataYaml().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "metadataYaml is required");
    }
    String dialect = request.dialect() == null || request.dialect().isBlank()
        ? "postgresql"
        : request.dialect();
    LoadedConfig loaded = loader.loadContent(request.metadataYaml(), "metadata.yaml", dialect);
    return toResponse(loaded);
  }

  public record ConfigParseRequest(String metadataYaml, String dialect) {
  }
```

- [ ] **Step 4: 运行全量并处理回显变化**

Run: `mvn test`
Expected: 全部 PASS。若 `ConfigControllerTest` / 前端相关测试断言 `text`→`varchar` 规范化回显，改为断言回显 `text`（行为变化是有意的：回显原始声明），并在 Task 10 的 README 更新里记一笔。

- [ ] **Step 5: Commit**

```bash
git add -A src/
git commit -m "feat: YAML 类型声明按方言解析并回显原始文本"
```

---

### Task 4: Trino 方言（adapter + profile + 注册 + 测试）

**Files:**
- Create: `src/main/java/io/sqlmask/dialect/TrinoDialectAdapter.java`
- Create: `src/main/java/io/sqlmask/dialect/TrinoTypeResolver.java`
- Create: `src/main/java/io/sqlmask/dialect/TrinoIdentifierPolicy.java`
- Create: `src/main/java/io/sqlmask/dialect/CaseInsensitiveOperatorTable.java`
- Modify: `src/main/java/io/sqlmask/dialect/PostgresqlFunctions.java`（改用共享的大小写不敏感包装，行为不变）
- Modify: `src/main/java/io/sqlmask/dialect/DialectRegistry.java`、`DialectProfiles.java`（登记 trino）
- Modify: `src/main/java/io/sqlmask/dialect/DialectCapabilities.java`（常量改名 `STRICT`，语义 = 不可包装重复列名；`POSTGRESQL` 名字误导）
- Test: `src/test/java/io/sqlmask/dialect/TrinoDialectAdapterTest.java`、`src/test/java/io/sqlmask/rewrite/MultiDialectRewriteTest.java`（Trino 部分）

**Interfaces:**
- Produces: `DialectRegistry.create("trino")`；Trino 类型集（见 Step 3）；Trino 保留字集合（保守超集，见 Step 3 注释）。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.dialect;

import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrinoDialectAdapterTest {

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
              - name: seen_at
                type: timestamp(3)
              - name: payload
                type: varbinary
      policies: {}
      """;

  private final TrinoDialectAdapter adapter = new TrinoDialectAdapter();
  private SchemaPlus schema;

  @BeforeEach
  void setUp() {
    schema = YamlCalciteSchemaFactory.create(
        new YamlConfigLoader().loadContent(YAML, "trino-test.yaml", "trino"));
  }

  @Test
  void parsesSelectAndCte() {
    assertEquals(SqlKind.SELECT, adapter.parse("SELECT phone FROM customer", 0).getKind());
    assertEquals(SqlKind.WITH, adapter.parse(
        "WITH a AS (SELECT phone FROM customer) SELECT phone FROM a", 0).getKind());
  }

  @Test
  void rejectsUpdateAndPlainCreateTable() {
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("UPDATE customer SET phone = 'x'", 0));
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("CREATE TABLE t (x int)", 0));
  }

  @Test
  void unquotedIdentifiersFoldToLower() {
    SqlNode parsed = adapter.parse("SELECT PHONE FROM CUSTOMER", 0);
    var validated = adapter.validate(parsed, schema);
    assertEquals(java.util.List.of("phone"), validated.rowType().getFieldNames());
  }

  @Test
  void quotedIdentifiersKeepCase() {
    // "Phone" 不匹配声明的小写列 → 校验失败（Trino 语义）
    SqlNode parsed = adapter.parse("SELECT \"Phone\" FROM customer", 0);
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> adapter.validate(parsed, schema));
    assertEquals(SqlMaskException.Code.VALIDATION_ERROR, e.getCode());
  }

  @Test
  void trinoTypeNamesParse() {
    TableMetadata.Column col = new TrinoTypeResolver().parseColumn("c", "timestamp(3)");
    assertEquals(SqlTypeName.TIMESTAMP, col.sqlTypeName());
    assertEquals(3, col.precision());
    assertEquals(SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE,
        new TrinoTypeResolver().parseColumn("c", "timestamp with time zone").sqlTypeName());
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new TrinoTypeResolver().parseColumn("c", "json"));
    assertTrue(e.getMessage().contains("trino"), () -> e.getMessage());
  }

  @Test
  void trinoOnlyFunctionsValidateViaCatchAll() {
    // Calcite 不知道的 Trino 函数走未知函数兜底
    SqlNode parsed = adapter.parse(
        "SELECT date_format(seen_at, '%Y-%m') AS m FROM customer", 0);
    var validated = adapter.validate(parsed, schema);
    assertEquals(java.util.List.of("m"), validated.rowType().getFieldNames());
  }

  @Test
  void ctasWithoutTablePropertiesParses() {
    assertEquals(SqlKind.CREATE_TABLE,
        adapter.parse("CREATE TABLE t AS SELECT phone FROM customer", 1).getKind());
  }

  @Test
  void ctasWithTablePropertiesFailsAtParse() {
    // Trino 特有 WITH(...) 表属性超出 Calcite 语法 → 安全失败
    assertThrows(SqlMaskException.class, () -> adapter.parse(
        "CREATE TABLE t WITH (format = 'ORC') AS SELECT phone FROM customer", 1));
  }

  @Test
  void errorMessagesCarryDialectName() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> adapter.parse("SELEC 1", 3));
    assertTrue(e.getMessage().contains("(trino)"), () -> e.getMessage());
    assertTrue(e.getMessage().contains("statement 3"), () -> e.getMessage());
  }
}
```
（`TableMetadata`/`SqlTypeName` 需要 import；`payload varbinary` 列在部分用例未用到，用于覆盖类型集。）

新建 `MultiDialectRewriteTest.java`（Task 5 会补 MySQL 段，这里先建 Trino 段）：

```java
package io.sqlmask.rewrite;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cross-dialect end-to-end rewrite checks: same semantics, per-dialect rendering. */
class MultiDialectRewriteTest {

  private static final String TRINO_YAML = """
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
      policies:
        phone_mask:
          udf: mask_phone
          arguments: [3, 4]
      """;

  private final RewriteEngine engine = new RewriteEngine();

  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  @Test
  void trinoWrapperRendersPlainLowercaseIdentifiers() {
    String out = flat(engine.rewrite(TRINO_YAML, "SELECT id, phone FROM customer", "trino")
        .get(0).rewrittenSql());
    assertEquals("SELECT r.id, mask_phone(r.phone, 3, 4) AS phone FROM ( "
        + "SELECT id, phone FROM customer ) AS r", out);
  }

  @Test
  void trinoMixedCaseOutputNameGetsDoubleQuoted() {
    String out = flat(engine.rewrite(TRINO_YAML,
        "SELECT phone AS \"Phone\" FROM customer", "trino").get(0).rewrittenSql());
    assertTrue(out.startsWith("SELECT mask_phone(r.\"Phone\", 3, 4) AS \"Phone\" FROM ("),
        out);
  }

  @Test
  void trinoInsertAndCtasGetWrappedSource() {
    String ins = flat(engine.rewrite(TRINO_YAML,
        "INSERT INTO crm.public.archive (id, phone) SELECT id, phone FROM customer",
        "trino").get(0).rewrittenSql());
    assertTrue(ins.startsWith("INSERT INTO crm.public.archive (id, phone) "
        + "SELECT r.id, mask_phone(r.phone, 3, 4) AS phone FROM ("), ins);

    String ctas = flat(engine.rewrite(TRINO_YAML,
        "CREATE TABLE crm.public.masked AS SELECT phone FROM customer",
        "trino").get(0).rewrittenSql());
    assertTrue(ctas.startsWith("CREATE TABLE crm.public.masked AS "
        + "SELECT mask_phone(r.phone, 3, 4) AS phone FROM ("), ctas);
  }

  @Test
  void trinoDuplicateOutputNamesStillRefuseWrapping() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> engine.rewrite(TRINO_YAML,
        "SELECT phone AS v, email AS v FROM customer", "trino"));
    assertEquals(SqlMaskException.Code.REWRITE_ERROR, e.getCode());
  }

  @Test
  void trinoPolicyKeysMatchCaseInsensitivelyFoldedNames() {
    // unquoted 大写引用折叠小写后命中策略键
    String out = flat(engine.rewrite(TRINO_YAML, "SELECT PHONE FROM CUSTOMER", "trino")
        .get(0).rewrittenSql());
    assertTrue(out.startsWith("SELECT mask_phone(r.phone, 3, 4) AS phone FROM ("), out);
  }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=TrinoDialectAdapterTest -Dtest=MultiDialectRewriteTest`
Expected: FAIL（`TrinoDialectAdapter` 等不存在）

- [ ] **Step 3: 实现 Trino profile 三件套并注册**

`TrinoDialectAdapter.java`：

```java
package io.sqlmask.dialect;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.dialect.TrinoSqlDialect;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserImpl;
import org.apache.calcite.sql.validate.SqlConformanceEnum;

/** Trino: standard parser, double-quote identifiers, unquoted fold to lower. */
public final class TrinoDialectAdapter extends AbstractCalciteDialectAdapter {

  public static final String NAME = "trino";

  public TrinoDialectAdapter() {
    super(new DialectProfile(
        NAME,
        SqlParser.config()
            .withParserFactory(SqlParserImpl.FACTORY)
            .withQuoting(Quoting.DOUBLE_QUOTE)
            .withUnquotedCasing(Casing.TO_LOWER)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(true)
            .withConformance(SqlConformanceEnum.DEFAULT),
        SqlConformanceEnum.DEFAULT,
        true,
        org.apache.calcite.sql.fun.SqlStdOperatorTable.instance(),
        new TrinoTypeResolver(),
        TrinoSqlDialect.DEFAULT,
        new TrinoIdentifierPolicy(),
        DialectProfile.SchemaPathStyle.CATALOG_SCHEMA,
        new DialectCapabilities(false)));
  }
}
```

`TrinoIdentifierPolicy.java`：

```java
package io.sqlmask.dialect;

import org.apache.calcite.sql.dialect.TrinoSqlDialect;

import java.util.Locale;
import java.util.Set;

/**
 * Trino quoting: quote-when-needed with a conservative superset of Trino's
 * reserved words (quoting a non-reserved word is always legal; failing to
 * quote a reserved one breaks SQL — hence the superset bias). Spec §10.1
 * item 2: verify against the official Trino reserved-word list and trim.
 */
public final class TrinoIdentifierPolicy implements IdentifierPolicy {

  private static final Set<String> RESERVED = Set.of(
      "ALL", "ALTER", "AND", "ANY", "ARRAY", "AS", "ASC", "BETWEEN", "BOTH",
      "CASE", "CAST", "CHECK", "COLLATE", "COLUMN", "CONSTRAINT", "CREATE",
      "CROSS", "CURRENT_CATALOG", "CURRENT_DATE", "CURRENT_ROLE",
      "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "DEFAULT",
      "DEFERRABLE", "DESC", "DISTINCT", "DO", "ELSE", "END", "EXCEPT",
      "FALSE", "FETCH", "FOR", "FOREIGN", "FROM", "GRANT", "GROUP", "HAVING",
      "IN", "INITIALLY", "INNER", "INTERSECT", "INTO", "JOIN", "LATERAL",
      "LEADING", "LEFT", "LIMIT", "LOCALTIME", "LOCALTIMESTAMP", "NATURAL",
      "NOT", "NULL", "OFFSET", "ON", "ONLY", "OR", "ORDER", "OUTER",
      "PLACING", "PRIMARY", "REFERENCES", "RIGHT", "ROW", "ROWS", "SELECT",
      "SESSION_USER", "SOME", "SYMMETRIC", "TABLE", "THEN", "TO", "TRAILING",
      "TRUE", "UNION", "UNIQUE", "USER", "USING", "VARIADIC", "WHEN",
      "WHERE", "WINDOW", "WITH", "VALUES");

  private static final String PLAIN_IDENTIFIER = "[a-z_][a-z0-9_]*";

  @Override
  public String render(String name) {
    if (!name.matches(PLAIN_IDENTIFIER)
        || RESERVED.contains(name.toUpperCase(Locale.ROOT))) {
      return TrinoSqlDialect.DEFAULT.quoteIdentifier(name);
    }
    return name;
  }
}
```

`TrinoTypeResolver.java`：

```java
package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.TableMetadata;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.Locale;

/** Trino scalar type declarations. */
public final class TrinoTypeResolver implements TypeResolver {

  private static final String TZ_SUFFIX = " with time zone";

  @Override
  public TableMetadata.Column parseColumn(String name, String typeDeclaration) {
    String raw = typeDeclaration.trim();
    String lowered = raw.toLowerCase(Locale.ROOT);
    boolean withTimeZone = lowered.endsWith(TZ_SUFFIX);
    ParsedType parsed = split(lowered, withTimeZone ? TZ_SUFFIX : null);
    if (withTimeZone) {
      return timeZoneColumn(name, raw, parsed);
    }
    String base = parsed.base();
    Integer precision = parsed.precision();
    Integer scale = parsed.scale();
    switch (base) {
      case "boolean" -> requireNoParams(raw);
      case "tinyint", "smallint", "integer", "int", "bigint", "real", "double" -> { }
      case "decimal" -> {
        if (scale != null && precision == null) {
          throw parseError(raw);
        }
      }
      case "char", "character" -> precision = precision != null ? precision : 1;
      case "varchar", "character varying" -> { /* precision optional (unbounded) */ }
      case "varbinary" -> { }
      case "date" -> requireNoParams(raw);
      case "timestamp" -> { /* precision optional */ }
      case "time" -> { /* precision optional */ }
      default -> throw parseError(raw);
    }
    SqlTypeName typeName = switch (base) {
      case "boolean" -> SqlTypeName.BOOLEAN;
      case "tinyint" -> SqlTypeName.TINYINT;
      case "smallint" -> SqlTypeName.SMALLINT;
      case "integer", "int" -> SqlTypeName.INTEGER;
      case "bigint" -> SqlTypeName.BIGINT;
      case "real" -> SqlTypeName.REAL;
      case "double" -> SqlTypeName.DOUBLE;
      case "decimal" -> SqlTypeName.DECIMAL;
      case "char", "character" -> SqlTypeName.CHAR;
      case "varchar", "character varying" -> SqlTypeName.VARCHAR;
      case "varbinary" -> SqlTypeName.VARBINARY;
      case "date" -> SqlTypeName.DATE;
      case "timestamp" -> SqlTypeName.TIMESTAMP;
      case "time" -> SqlTypeName.TIME;
      default -> throw parseError(raw);
    };
    return new TableMetadata.Column(name, typeName, precision, scale, raw);
  }

  private static TableMetadata.Column timeZoneColumn(String name, String raw, ParsedType parsed) {
    switch (parsed.base()) {
      case "timestamp" -> {
        return new TableMetadata.Column(name, SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE,
            parsed.precision(), parsed.scale(), raw);
      }
      case "time" -> {
        return new TableMetadata.Column(name, SqlTypeName.TIME_WITH_LOCAL_TIME_ZONE,
            parsed.precision(), parsed.scale(), raw);
      }
      default -> throw parseError(raw);
    }
  }

  private static void requireNoParams(String raw) {
    if (raw.contains("(")) {
      throw parseError(raw);
    }
  }

  private static SqlMaskException parseError(String raw) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "unsupported or malformed type declaration '" + raw + "' for dialect trino; "
            + "supported scalar types: boolean, tinyint, smallint, integer/int, bigint, "
            + "real, double, decimal(p,s), char(n), varchar[(n)], varbinary, date, "
            + "time[(p)] [with time zone], timestamp[(p)] [with time zone]");
  }
}
```
实现说明：`split(lowered, TZ_SUFFIX)` 在无后缀时返回 null、有后缀时返回剥离后缀的解析结果；上面代码先判后缀、后走普通解析，两个分支互不重叠。`timestamp(3) with time zone` 的解析顺序：先剥后缀，再对 `timestamp(3)` 走 `PARAMS` 正则。

`CaseInsensitiveOperatorTable.java`（从 `PostgresqlFunctions` 提取）：

```java
package io.sqlmask.dialect;

import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.fun.SqlLibraryOperatorTableFactory;
import org.apache.calcite.sql.util.ListSqlOperatorTable;
import org.apache.calcite.sql.validate.SqlNameMatcher;
import org.apache.calcite.sql.validate.SqlNameMatchers;

import java.util.List;

/** Library operator lookup that is case-insensitive regardless of session matching. */
public final class CaseInsensitiveOperatorTable {

  /** Wraps the operator list of a Calcite {@link SqlLibrary}. */
  public static SqlOperatorTable of(SqlLibrary library) {
    return of(SqlLibraryOperatorTableFactory.INSTANCE
        .getOperatorTable(library).getOperatorList());
  }

  /** Wraps an explicit operator list (dialect extras such as PG {@code concat}). */
  public static SqlOperatorTable of(List<SqlOperator> operators) {
    return new ListSqlOperatorTable(operators) {
      @Override
      public void lookupOperatorOverloads(SqlIdentifier opName,
          SqlFunctionCategory category, SqlSyntax syntax,
          List<SqlOperator> operatorList, SqlNameMatcher nameMatcher) {
        super.lookupOperatorOverloads(opName, category, syntax, operatorList,
            SqlNameMatchers.withCaseSensitive(false));
      }
    };
  }

  private CaseInsensitiveOperatorTable() {
  }
}
```

`PostgresqlFunctions.java` 改为用共享包装（行为等价——原私有 `caseInsensitive` 方法删除）：

```java
  public static final SqlOperatorTable TABLE = SqlOperatorTables.chain(
      SqlStdOperatorTable.instance(),
      CaseInsensitiveOperatorTable.of(SqlLibrary.POSTGRESQL),
      CaseInsensitiveOperatorTable.of(List.of(CONCAT)));
```
（`buildOperators` 与私有 `caseInsensitive` 方法删除；`CONCAT` 定义原样保留。）

`DialectCapabilities`：常量改名：
```java
  public static final DialectCapabilities STRICT = new DialectCapabilities(false);
```
PG/Trino profile 用 `DialectCapabilities.STRICT`（`describe()` 输出不变）。

`DialectRegistry` static 块加：
```java
    ADAPTERS.put(TrinoDialectAdapter.NAME, TrinoDialectAdapter::new);
```
`newAdapter` switch 加 `case TrinoDialectAdapter.NAME -> new TrinoDialectAdapter();`
`DialectProfiles` static 块加：
```java
    TrinoDialectAdapter trino = new TrinoDialectAdapter();
    PROFILES.put(trino.name(), trino.profile());
```

- [ ] **Step 4: 运行全量**

Run: `mvn test`
Expected: 全部 PASS（含新 Trino 测试；PG golden 无 diff）。

- [ ] **Step 5: Commit**

```bash
git add -A src/ pom.xml
git commit -m "feat: 新增 Trino 方言（parser/类型/标识符/注册）"
```

---

### Task 5: MySQL 方言（adapter + profile + 注册 + 测试，含 MYSQL_5 回归）

**Files:**
- Create: `src/main/java/io/sqlmask/dialect/MysqlDialectAdapter.java`
- Create: `src/main/java/io/sqlmask/dialect/MysqlTypeResolver.java`
- Create: `src/main/java/io/sqlmask/dialect/MysqlIdentifierPolicy.java`
- Create: `src/main/java/io/sqlmask/dialect/MysqlFunctions.java`
- Modify: `DialectRegistry.java`、`DialectProfiles.java`（登记 mysql）
- Test: `src/test/java/io/sqlmask/dialect/MysqlDialectAdapterTest.java`；`MultiDialectRewriteTest.java` 补 MySQL 段

**Interfaces:**
- Produces: `DialectRegistry.create("mysql")`；MySQL 类型集；`MysqlFunctions.TABLE`（std + `SqlLibrary.MYSQL`，大小写不敏感）。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.dialect;

import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlTypeName;
import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlDialectAdapterTest {

  private static final String YAML = """
      metadata:
        tables:
          - catalog: shop
            schema: app
            name: orders
            columns:
              - name: id
                type: bigint
              - name: amount
                type: decimal(10,2)
              - name: taken_at
                type: datetime
              - name: memo
                type: text
              - name: state
                type: tinyint
      policies: {}
      """;

  private final MysqlDialectAdapter adapter = new MysqlDialectAdapter();
  private SchemaPlus schema;

  @BeforeEach
  void setUp() {
    schema = YamlCalciteSchemaFactory.create(
        new YamlConfigLoader().loadContent(YAML, "mysql-test.yaml", "mysql"));
  }

  @Test
  void parsesWithBacktickQuoting() {
    assertEquals(SqlKind.SELECT, adapter.parse("SELECT `memo` FROM `orders`", 0).getKind());
  }

  @Test
  void doubleQuoteIsAStringLiteralNotIdentifier() {
    // MySQL 默认 ANSI_QUOTES 关闭："..." 是字符串
    SqlNode parsed = adapter.parse("SELECT \"memo\" FROM orders", 0);
    assertEquals(SqlKind.SELECT, parsed.getKind());
  }

  @Test
  void unquotedIdentifiersMatchCaseInsensitively() {
    SqlNode parsed = adapter.parse("SELECT MEMO, TAKEN_AT FROM ORDERS", 0);
    var validated = adapter.validate(parsed, schema);
    assertEquals(java.util.List.of("memo", "taken_at"), validated.rowType().getFieldNames());
  }

  @Test
  void mysqlTypeMapping() {
    MysqlTypeResolver resolver = new MysqlTypeResolver();
    assertEquals(SqlTypeName.TIMESTAMP, resolver.parseColumn("c", "datetime").sqlTypeName());
    assertEquals(SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE,
        resolver.parseColumn("c", "timestamp").sqlTypeName());
    assertEquals(SqlTypeName.TINYINT, resolver.parseColumn("c", "tinyint").sqlTypeName());
    assertEquals(SqlTypeName.VARCHAR, resolver.parseColumn("c", "longtext").sqlTypeName());
    assertEquals(SqlTypeName.INTEGER, resolver.parseColumn("c", "mediumint").sqlTypeName());
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> resolver.parseColumn("c", "json"));
    assertTrue(e.getMessage().contains("mysql"), () -> e.getMessage());
  }

  @Test
  void havingAliasAllowedByMysqlConformance() {
    // MYSQL_5 允许 HAVING 引用 SELECT 别名（DEFAULT 会拒绝）
    SqlNode parsed = adapter.parse(
        "SELECT state, count(*) AS c FROM orders GROUP BY state HAVING c > 1", 0);
    adapter.validate(parsed, schema);
  }

  @Test
  void mysqlLibraryFunctionsResolve() {
    // SqlLibrary.MYSQL 中的函数按真实签名解析
    SqlNode parsed = adapter.parse("SELECT IFNULL(state, 0) AS s FROM orders", 0);
    adapter.validate(parsed, schema);
  }

  @Test
  void ctasWithoutAsFailsAtParse() {
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("CREATE TABLE t SELECT memo FROM orders", 1));
  }

  @Test
  void limitOffsetCommaFormFailsAtParse() {
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("SELECT memo FROM orders LIMIT 5, 10", 0));
  }

  @Test
  void insertValuesIsPlainSelectFreeWrite() {
    assertEquals(SqlKind.INSERT,
        adapter.parse("INSERT INTO app.orders (id) VALUES (1)", 2).getKind());
  }
}
```

`MultiDialectRewriteTest` 追加（MySQL 段）：

```java
  private static final String MYSQL_YAML = """
      metadata:
        tables:
          - catalog: shop
            schema: app
            name: customer
            columns:
              - name: id
                type: bigint
              - name: phone
                type: varchar(20)
              - name: email
                type: varchar(100)
      columns:
        - catalog: shop
          schema: app
          table: customer
          column: phone
          policy: phone_mask
      policies:
        phone_mask:
          udf: mask_phone
          arguments: [3, 4]
      """;

  @Test
  void mysqlWrapperAlwaysBacktickQuotes() {
    String out = flat(engine.rewrite(MYSQL_YAML, "SELECT id, phone FROM customer", "mysql")
        .get(0).rewrittenSql());
    assertEquals("SELECT `r`.`id`, `mask_phone`(`r`.`phone`, 3, 4) AS `phone` FROM ( "
        + "SELECT id, phone FROM customer ) AS `r`", out);
  }

  @Test
  void mysqlTwoPartNamesResolve() {
    String out = flat(engine.rewrite(MYSQL_YAML,
        "SELECT phone FROM app.customer", "mysql").get(0).rewrittenSql());
    assertTrue(out.contains("`mask_phone`"), out);
  }

  @Test
  void mysqlWriteStatementsRecomposed() {
    String ins = flat(engine.rewrite(MYSQL_YAML,
        "INSERT INTO `app`.`archive` (`id`, `phone`) SELECT id, phone FROM customer",
        "mysql").get(0).rewrittenSql());
    assertTrue(ins.startsWith("INSERT INTO `app`.`archive` (`id`, `phone`) "
        + "SELECT `r`.`id`, `mask_phone`(`r`.`phone`, 3, 4) AS `phone` FROM ("), ins);
  }

  @Test
  void mysqlCaseInsensitiveReferenceHitsPolicy() {
    String out = flat(engine.rewrite(MYSQL_YAML, "SELECT PHONE FROM Customer", "mysql")
        .get(0).rewrittenSql());
    assertTrue(out.contains("`mask_phone`"), out);
  }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn test -Dtest=MysqlDialectAdapterTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 MySQL profile 四件套并注册**

`MysqlDialectAdapter.java`：

```java
package io.sqlmask.dialect;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserImpl;
import org.apache.calcite.sql.validate.SqlConformanceEnum;

/**
 * MySQL: backtick identifiers, no case folding, case-insensitive matching
 * (column names are case-insensitive in MySQL), MYSQL_5 conformance.
 */
public final class MysqlDialectAdapter extends AbstractCalciteDialectAdapter {

  public static final String NAME = "mysql";

  public MysqlDialectAdapter() {
    super(new DialectProfile(
        NAME,
        SqlParser.config()
            .withParserFactory(SqlParserImpl.FACTORY)
            .withQuoting(Quoting.BACK_TICK)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(false)
            .withConformance(SqlConformanceEnum.MYSQL_5),
        SqlConformanceEnum.MYSQL_5,
        false,
        MysqlFunctions.TABLE,
        new MysqlTypeResolver(),
        MysqlSqlDialect.DEFAULT,
        new MysqlIdentifierPolicy(),
        DialectProfile.SchemaPathStyle.CATALOG_SCHEMA_AND_SCHEMA,
        new DialectCapabilities(false)));
  }
}
```

`MysqlIdentifierPolicy.java`：

```java
package io.sqlmask.dialect;

/**
 * MySQL quoting: always backtick-quote wrapper identifiers. MySQL doubles
 * embedded backticks; always-quoting removes the need for a reserved-word
 * list and is always legal.
 */
public final class MysqlIdentifierPolicy implements IdentifierPolicy {

  @Override
  public String render(String name) {
    return "`" + name.replace("`", "``") + "`";
  }
}
```

`MysqlFunctions.java`：

```java
package io.sqlmask.dialect;

import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.util.SqlOperatorTables;

/** MySQL operator table: standard built-ins plus Calcite's MySQL library. */
public final class MysqlFunctions {

  public static final SqlOperatorTable TABLE = SqlOperatorTables.chain(
      org.apache.calcite.sql.fun.SqlStdOperatorTable.instance(),
      CaseInsensitiveOperatorTable.of(SqlLibrary.MYSQL));

  private MysqlFunctions() {
  }
}
```

`MysqlTypeResolver.java`：

```java
package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.TableMetadata;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.Locale;

/** MySQL scalar type declarations. */
public final class MysqlTypeResolver implements TypeResolver {

  @Override
  public TableMetadata.Column parseColumn(String name, String typeDeclaration) {
    String raw = typeDeclaration.trim();
    String lowered = raw.toLowerCase(Locale.ROOT);
    ParsedType parsed = split(lowered, null);
    String base = parsed.base();
    Integer precision = parsed.precision();
    Integer scale = parsed.scale();
    switch (base) {
      case "boolean", "bool" -> { /* BOOLEAN = tinyint(1) alias */ }
      case "tinyint", "smallint" -> { /* display width ignored */ }
      case "mediumint", "int", "integer" -> { }
      case "bigint" -> { }
      case "decimal", "dec", "numeric" -> {
        if (scale != null && precision == null) {
          throw parseError(raw);
        }
      }
      case "float" -> { }
      case "double", "double precision" -> { }
      case "char" -> precision = precision != null ? precision : 1;
      case "varchar" -> { /* (n) customary; tolerated without */ }
      case "tinytext", "text", "mediumtext", "longtext" -> requireNoParams(raw);
      case "binary" -> precision = precision != null ? precision : 1;
      case "varbinary" -> { }
      case "date" -> requireNoParams(raw);
      case "datetime" -> { /* precision optional */ }
      case "timestamp" -> { /* precision optional */ }
      case "time" -> { /* precision optional */ }
      default -> throw parseError(raw);
    }
    SqlTypeName typeName = switch (base) {
      case "boolean", "bool" -> SqlTypeName.BOOLEAN;
      case "tinyint" -> SqlTypeName.TINYINT;
      case "smallint" -> SqlTypeName.SMALLINT;
      case "mediumint", "int", "integer" -> SqlTypeName.INTEGER;
      case "bigint" -> SqlTypeName.BIGINT;
      case "decimal", "dec", "numeric" -> SqlTypeName.DECIMAL;
      case "float" -> SqlTypeName.REAL;
      case "double", "double precision" -> SqlTypeName.DOUBLE;
      case "char" -> SqlTypeName.CHAR;
      case "varchar" -> SqlTypeName.VARCHAR;
      case "tinytext", "text", "mediumtext", "longtext" -> SqlTypeName.VARCHAR;
      case "binary" -> SqlTypeName.BINARY;
      case "varbinary" -> SqlTypeName.VARBINARY;
      case "date" -> SqlTypeName.DATE;
      case "datetime" -> SqlTypeName.TIMESTAMP;
      case "timestamp" -> SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE;
      case "time" -> SqlTypeName.TIME;
      default -> throw parseError(raw);
    };
    return new TableMetadata.Column(name, typeName, precision, scale, raw);
  }

  private static void requireNoParams(String raw) {
    if (raw.contains("(")) {
      throw parseError(raw);
    }
  }

  private static SqlMaskException parseError(String raw) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "unsupported or malformed type declaration '" + raw + "' for dialect mysql; "
            + "supported scalar types: boolean, tinyint[(n)], smallint[(n)], mediumint, "
            + "int/integer, bigint, decimal(p,s), float, double, char[(n)], varchar(n), "
            + "tinytext/text/mediumtext/longtext, binary[(n)], varbinary(n), date, "
            + "datetime[(p)], timestamp[(p)], time[(p)] "
            + "(json/year/enum/set/bit/geometry are not supported)");
  }
}
```

注册（`DialectRegistry`、`DialectProfiles` 各加 mysql 条目，写法同 Task 4 的 trino）。

- [ ] **Step 4: 运行全量（MYSQL_5/共享代码回归 + spec §10.2 item 5）**

Run: `mvn test`
Expected: 全部 PASS。若 `havingAliasAllowedByMysqlConformance` 或 `mysqlLibraryFunctionsResolve` 因 Calcite 细节失败，记录失败原因并调整用例断言（这两个测试钉行为，不猜行为）；其余必须绿。

- [ ] **Step 5: Commit**

```bash
git add -A src/
git commit -m "feat: 新增 MySQL 方言（反引号/大小写不敏感/MYSQL_5/类型映射）"
```

---

### Task 6: MySQL 搜索路径 pinning 测试（spec §10.1 item 1）

**Files:**
- Test: `src/test/java/io/sqlmask/dialect/MysqlSchemaPathPinningTest.java`（新建）

**Interfaces:**
- Consumes: `DialectProfile.SchemaPathStyle.CATALOG_SCHEMA_AND_SCHEMA`（Task 5）。
- Produces: 钉死「两段名解析、非限定名唯一解析、跨 schema 同名行为」三个事实；若多路径行为不可接受，回退方案是把 MySQL profile 改为 `SCHEMA_ONLY`（本任务内完成，改动只有 profile 一个枚举值 + 对应断言）。

- [ ] **Step 1: 写实验性测试（先观察后钉死）**

```java
package io.sqlmask.dialect;

import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins CalciteCatalogReader's multi-path resolution order for the MySQL
 * profile ([catalog, schema] pairs plus bare [schema] paths), per spec
 * §10.1 item 1. If the observed behavior is unacceptable, the fallback is a
 * SCHEMA_ONLY profile (catalog becomes a pure YAML grouping field).
 */
class MysqlSchemaPathPinningTest {

  private static final String TWO_SCHEMAS = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - name: phone
                type: varchar
          - catalog: crm
            schema: sales
            name: customer
            columns:
              - name: phone
                type: varchar
      policies: {}
      """;

  private static final String ONE_SCHEMA = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - name: phone
                type: varchar
      policies: {}
      """;

  private final MysqlDialectAdapter adapter = new MysqlDialectAdapter();

  private SchemaPlus schemaOf(String yaml) {
    return YamlCalciteSchemaFactory.create(
        new YamlConfigLoader().loadContent(yaml, "pin.yaml", "mysql"));
  }

  @Test
  void twoPartNameResolvesAgainstBareSchemaPath() {
    var validated = adapter.validate(
        adapter.parse("SELECT phone FROM public.customer", 0), schemaOf(ONE_SCHEMA));
    assertEquals(java.util.List.of("phone"), validated.rowType().getFieldNames());
  }

  @Test
  void unqualifiedNameResolvesWhenUnique() {
    var validated = adapter.validate(
        adapter.parse("SELECT phone FROM customer", 0), schemaOf(ONE_SCHEMA));
    assertEquals(java.util.List.of("phone"), validated.rowType().getFieldNames());
  }

  @Test
  void unqualifiedNameAcrossDuplicateSchemasHasAPinnedOutcome() {
    // 观察点：Calcite 对两条路径都能命中的非限定名是「按路径顺序首匹配」还是
    // 「报二义」。运行一次记录实际行为，然后把下面 assert 改为钉死该行为，
    // 并把结论写进 README（见 Task 10）。
    try {
      adapter.validate(adapter.parse("SELECT phone FROM customer", 0), schemaOf(TWO_SCHEMAS));
      // 首匹配：钉死为成功（声明顺序 public 在前）
    } catch (SqlMaskException e) {
      assertTrue(String.valueOf(e.getMessage()).toLowerCase().contains("ambigu"),
          () -> "unexpected failure: " + e.getMessage());
      // 二义：钉死为 CONFIG/VALIDATION_ERROR
    }
  }
}
```

- [ ] **Step 2: 运行并钉死行为**

Run: `mvn test -Dtest=MysqlSchemaPathPinningTest`
Expected: 前两个测试 PASS（两段名、非限定名唯一必须工作——这是硬要求，失败则检查 `AbstractCalciteDialectAdapter.schemaPaths` 的路径生成）。第三个测试按 try/catch 两个分支都能通过（先观察）。然后把 try/catch 改成单一断言（观察到的实际行为），注释记录结论；若实际行为是"静默首匹配"，确认首匹配顺序是声明顺序（public 在 sales 前）；若是二义错误，保持错误并在 README 记录。
决策规则：只有当两段名或非限定名唯一解析**失败**时才回退——在
`DialectProfile.SchemaPathStyle` 新增枚举常量 `SCHEMA_ONLY`、
`AbstractCalciteDialectAdapter.schemaPaths` 增加对应分支（仅生成一元素
`[schema]` 路径），`MysqlDialectAdapter` 改用 `SCHEMA_ONLY` 并重跑本测试；
`catalog` 从此只是 YAML 分组字段。

- [ ] **Step 3: 运行全量并提交**

Run: `mvn test`

```bash
git add src/test/java/io/sqlmask/dialect/MysqlSchemaPathPinningTest.java src/main/java/io/sqlmask/dialect/
git commit -m "test: 钉死 MySQL 多搜索路径解析行为（spec 10.1）"
```

---

### Task 7: trino-parser 输出验证（test 作用域）

**Files:**
- Modify: `pom.xml`（唯一新增依赖，scope=test）
- Create: `src/test/resources/metadata/trino-integration.yaml`（本任务创建，Task 8 复用）
- Create: `src/test/java/io/sqlmask/regression/TrinoOutputSyntaxTest.java`

**Interfaces:**
- Consumes: `DialectRegistry.create("trino")` 产出的改写结果。
- Produces: 测试工具断言 `assertValidTrino(String sql)`（真 Trino parser 再解析）；`trino-integration.yaml` 资源。

- [ ] **Step 1: 添加依赖**

`pom.xml` `<dependencies>` 追加（版本若拉取失败，改用任一存在的 Trino 发布版本并在提交信息里注明）：

```xml
    <dependency>
      <groupId>io.trino</groupId>
      <artifactId>trino-parser</artifactId>
      <version>456</version>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: 写验证测试**

```java
package io.sqlmask.regression;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.rewrite.RewriteEngine;
import io.trino.sql.parser.SqlParser;
import io.trino.sql.tree.Statement;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that every SQL statement the trino dialect generates (wrapped or
 * passed through) is accepted by the real Trino parser — spec §6 item 4.
 */
class TrinoOutputSyntaxTest {

  private static final Path TRINO_METADATA =
      Path.of("src/test/resources/metadata/trino-integration.yaml");
  private static final Path TPCDS_METADATA = Path.of("tpcds/metadata.yaml");
  private static final Path TPCDS_QUERIES_DIR = Path.of("tpcds/queries");

  private final RewriteEngine engine = new RewriteEngine();

  private static void assertValidTrino(String sql) {
    try {
      Statement statement = new SqlParser().createStatement(sql);
      if (statement == null) {
        fail("parser returned null for: " + sql);
      }
    } catch (RuntimeException e) {
      fail("generated SQL is not valid Trino: " + sql + "\n" + e.getMessage());
    }
  }

  @Test
  void allCoreFixtureOutputsParseInRealTrino() throws Exception {
    String metadata = Files.readString(TRINO_METADATA, StandardCharsets.UTF_8);
    String queries = Files.readString(
        Path.of("src/test/resources/queries/with-and-nested.sql"), StandardCharsets.UTF_8);
    for (RewriteEngine.StatementRewrite statement : engine.rewrite(metadata, queries, "trino")) {
      assertValidTrino(statement.rewrittenSql());
    }
  }

  @Test
  void trinoTpcdsPortableSubsetParsesInRealTrino() throws Exception {
    String metadata = Files.readString(TPCDS_METADATA, StandardCharsets.UTF_8);
    for (String file : List.of("tpcds_common_cases.sql", "tpcds_masking_test.sql",
        "tpcds_oneline.sql", "tpcds_crlf.sql")) {
      String queries = Files.readString(
          TPCDS_QUERIES_DIR.resolve(file), StandardCharsets.UTF_8);
      try {
        for (RewriteEngine.StatementRewrite statement : engine.rewrite(metadata, queries, "trino")) {
          assertValidTrino(statement.rewrittenSql());
        }
      } catch (SqlMaskException e) {
        fail(file + " was expected to rewrite under trino but failed: " + e.getMessage());
      }
    }
  }
}
```
注意：`TRINO_METADATA` 是 `src/test/resources/metadata/trino-integration.yaml`，本任务创建：复制 `src/test/resources/metadata/integration.yaml`，先 `grep -E "type:" src/test/resources/metadata/integration.yaml` 列出实际类型，再把 PG 专有类型按 Trino 改写（`text`→`varchar`；`double precision`→`double`；其余 PG 名若 Trino 原生合法则保留）。

- [ ] **Step 3: 运行并修复渲染问题**

Run: `mvn test -Dtest=TrinoOutputSyntaxTest`
Expected: PASS。若真 parser 拒绝某条输出，这是"Calcite 眼中的 Trino ≠ 真 Trino"的实例——修 `TrinoIdentifierPolicy`/unparse 配置而不是跳过测试。同时跑 `mvn -q dependency:tree -Dincludes=com.google.guava` 确认无 guava 版本断裂（spec §10.2 item 6；test scope 冲突通常无害）。

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/test/
git commit -m "test: 用真 trino-parser 验证 Trino 方言输出语法"
```

---

### Task 8: 跨方言集成测试（方言变体集）

**Files:**
- Create: `src/test/resources/metadata/mysql-integration.yaml`
- Modify: `src/test/java/io/sqlmask/rewrite/MultiDialectRewriteTest.java`（补齐 §10.3 item 9 的方言变体场景，及 §6.5 / §10.2 item 3、4 的验证项）

**Interfaces:**
- Consumes: 三个方言的 `DialectRegistry.create`；`trino-integration.yaml`（Task 7 已创建）。
- Produces: `mysql-integration.yaml` 资源。

- [ ] **Step 1: 创建方言元数据资源**

`mysql-integration.yaml`：复制 `src/test/resources/metadata/integration.yaml`，类型按 MySQL 调整（`text` 保留、`double precision`→`double`、`timestamp with time zone`→`timestamp`、`timestamptz`→`timestamp`，其余保留；`varchar` 可加惯用长度如 `varchar(100)`）。（`trino-integration.yaml` 已在 Task 7 创建。）

- [ ] **Step 2: 补齐方言变体场景测试**

在 `MultiDialectRewriteTest` 追加每个方言一段的：CTE 血缘（含 CTE 名遮蔽表名——spec §10.2 item 7 的 CteExpander 等价性验证）、多来源策略字典序、无策略直通、`INSERT ... VALUES` 直通、VALUES 藏子查询失败、UPDATE/DELETE 失败、失败语句中止整批。结构照抄 `SqlMaskIntegrationTest` 的断言风格，SQL 文本按方言变体（PG 的 `::` 强转场景在 trino/mysql 用 `CAST(x AS type)`）：

```java
  @Test
  void trinoCteLineageAndShadowing() {
    String out = flat(engine.rewrite(TRINO_YAML,
        "WITH customer AS (SELECT phone FROM crm.public.customer) SELECT phone FROM customer",
        "trino").get(0).rewrittenSql());
    assertTrue(out.startsWith("SELECT mask_phone(r.phone, 3, 4) AS phone FROM ("), out);
    assertTrue(out.toUpperCase().contains("WITH CUSTOMER AS"), out);
  }

  @Test
  void mysqlCteLineageAndShadowing() {
    String out = flat(engine.rewrite(MYSQL_YAML,
        "WITH customer AS (SELECT phone FROM customer) SELECT phone FROM customer",
        "mysql").get(0).rewrittenSql());
    assertTrue(out.startsWith("SELECT `mask_phone`(`r`.`phone`, 3, 4) AS `phone` FROM ("), out);
  }

  @Test
  void mysqlMultiSourcePolicyIsDeterministic() {
    String out = flat(engine.rewrite(MYSQL_YAML,
        "SELECT concat(email, '-', phone) AS contact FROM customer", "mysql")
        .get(0).rewrittenSql());
    assertTrue(out.contains("`mask_email`"), out);
  }

  @Test
  void mysqlInsertValuesPassesThrough() {
    String sql = "INSERT INTO app.archive (id) VALUES (1)";
    assertEquals(sql + ";", flat(engine.rewrite(MYSQL_YAML, sql, "mysql")
        .get(0).rewrittenSql()) + ";");
  }

  @Test
  void updateFailsOnEveryDialect() {
    for (String dialect : List.of("postgresql", "trino", "mysql")) {
      assertThrows(SqlMaskException.class,
          () -> engine.rewrite(TRINO_YAML, "UPDATE customer SET phone = 'x'", dialect));
    }
  }
```

再追加三个验证项测试（对应 spec §6.5 / §10.2 item 3、4）：

```java
  @Test
  void mysqlOutputReparsesUnderMysqlConfig() {
    // spec §6.5：MySQL 无官方独立 parser，用 MySQL 配置的 Calcite round-trip 兜底
    String out = engine.rewrite(MYSQL_YAML, "SELECT phone FROM customer", "mysql")
        .get(0).rewrittenSql();
    new io.sqlmask.dialect.MysqlDialectAdapter().parse(out, 0);
  }

  @Test
  void mysqlStringArgumentsRenderPerDialectEscaping() {
    // spec §10.2 item 3：MySQL 字符串参数的反斜杠/引号转义由 MysqlSqlDialect 决定；
    // YAML 单引号写法 'a''b\c' 的值是 a'b\c。先跑一次观察输出，然后把下面的
    // assertTrue 替换为 assertEquals("<观察到的完整输出>", out) 钉死行为。
    String yaml = MYSQL_YAML.replace("arguments: [3, 4]", "arguments: ['a''b\\c', 3]");
    String out = flat(engine.rewrite(yaml, "SELECT phone FROM customer", "mysql")
        .get(0).rewrittenSql());
    assertTrue(out.contains("'a"), () -> out);
  }

  @Test
  void mysqlCtasWithColumnDefinitionsHasPinnedOutcome() {
    // spec §10.2 item 4：带列定义的 CTAS 能否解析、列定义被 Calcite 规范化
    // 渲染（INT→INTEGER 等）后是否仍是合法 MySQL——两种结果都可接受，钉死其一：
    try {
      String out = flat(engine.rewrite(MYSQL_YAML,
          "CREATE TABLE app.masked (id bigint, phone varchar(20)) AS SELECT id, phone FROM customer",
          "mysql").get(0).rewrittenSql());
      assertTrue(out.toUpperCase().startsWith("CREATE TABLE `APP`.`MASKED` (")
              || out.toUpperCase().startsWith("CREATE TABLE `app`.`masked` ("),
          out);
      // 重组后列定义以 Calcite 规范形式渲染；把观察到的完整输出钉进断言
    } catch (SqlMaskException e) {
      assertEquals(SqlMaskException.Code.PARSE_ERROR, e.getCode(),
          () -> "unexpected failure: " + e.getMessage());
      // 解析不了 = 安全失败；结论记入 README 安全失败清单（Task 11）
    }
  }
```
（`mysqlStringArgumentsRenderPerDialectEscaping` 首跑后必须把宽松断言替换为完整输出的 `assertEquals` 钉死，与 Task 6 的 pinning 模式一致。）
（`UPDATE` 用例的 YAML 参数按方言选对应元数据；写成参数化循环。）

- [ ] **Step 3: 运行全量**

Run: `mvn test`
Expected: 全部 PASS。

- [ ] **Step 4: Commit**

```bash
git add src/test/
git commit -m "test: 跨方言集成测试与方言元数据资源"
```

---

### Task 9: TPC-DS 跨方言 golden（spec §10.3 item 10）

**Files:**
- Create: `src/test/java/io/sqlmask/regression/MultiDialectGoldenOutputTest.java`
- Create: `src/test/resources/golden/tpcds-<dialect>-<用例名>.sql`（如 `tpcds-trino-common_cases.sql`、`tpcds-mysql-robustness.sql`，由 `-Dgolden.write=true` 生成后人工审查）
- 不需要按方言改 metadata：`tpcds/metadata.yaml` 只含 `integer/bigint/char(n)/varchar(n)/date`，Trino/MySQL 原生合法（已核实）。

**Interfaces:**
- Consumes: `tpcds/queries/` 可移植子集（common、masking_test、oneline、crlf、robustness）；Task 1-5 的方言。
- Produces: trino/mysql 的 TPC-DS golden 输出锁。

- [ ] **Step 1: 写 golden 测试（生成模式）**

```java
package io.sqlmask.regression;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.rewrite.RewriteEngine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-dialect TPC-DS portable-subset output lock (spec §6 item 6). Regenerate
 * with -Dgolden.write=true and review the diff; PG goldens stay in
 * GoldenOutputTest and must not move.
 */
class MultiDialectGoldenOutputTest {

  private static final Path TPCDS_METADATA = Path.of("tpcds/metadata.yaml");
  private static final Path TPCDS_QUERIES_DIR = Path.of("tpcds/queries");

  /** portable subset: files that rewrite cleanly under trino and mysql */
  private static final List<String> PORTABLE = List.of(
      "tpcds_common_cases.sql", "tpcds_masking_test.sql",
      "tpcds_oneline.sql", "tpcds_crlf.sql", "tpcds_robustness.sql");

  private final RewriteEngine engine = new RewriteEngine();

  @Test
  void tpcdsPortableSubsetMatchesGoldenPerDialect() throws Exception {
    String metadata = Files.readString(TPCDS_METADATA, StandardCharsets.UTF_8);
    for (String dialect : List.of("trino", "mysql")) {
      for (String file : PORTABLE) {
        Path golden = Path.of("src/test/resources/golden/tpcds-" + dialect + "-"
            + file.replace("tpcds_", ""));
        String actual;
        try {
          actual = engine.rewrite(metadata,
                  Files.readString(TPCDS_QUERIES_DIR.resolve(file), StandardCharsets.UTF_8),
                  dialect).stream()
              .map(s -> s.rewrittenSql() + ";")
              .reduce((a, b) -> a + "\n\n" + b)
              .orElse("");
        } catch (SqlMaskException e) {
          throw new IllegalStateException(
              file + " no longer rewrites under " + dialect
                  + "; move it out of PORTABLE and record the failure: " + e.getMessage(), e);
        }
        if (Boolean.getBoolean("golden.write")) {
          Files.createDirectories(golden.getParent());
          Files.writeString(golden, actual, StandardCharsets.UTF_8);
          continue;
        }
        assertEquals(normalize(Files.readString(golden, StandardCharsets.UTF_8)),
            normalize(actual), dialect + " / " + file);
      }
    }
  }

  @Test
  void pgClassifiedFailuresKeepFailingUnderOtherDialects() throws Exception {
    // 这些文件在 PG 下的失败分类（GoldenOutputTest）在 trino/mysql 不允许
    // 变成静默成功——重新分类的语句必须显式记录
    String metadata = Files.readString(TPCDS_METADATA, StandardCharsets.UTF_8);
    for (String dialect : List.of("trino", "mysql")) {
      assertThrows(SqlMaskException.class, () -> engine.rewrite(metadata,
          Files.readString(TPCDS_QUERIES_DIR.resolve("tpcds_edge_cases.sql"),
              StandardCharsets.UTF_8), dialect),
          "duplicate-alias refusal must hold for " + dialect);
      assertThrows(SqlMaskException.class, () -> engine.rewrite(metadata,
          Files.readString(TPCDS_QUERIES_DIR.resolve("tpcds_open_cases.sql"),
              StandardCharsets.UTF_8), dialect),
          "correlated-scalar-subquery refusal must hold for " + dialect);
    }
  }

  private static String normalize(String text) {
    return text.replace("\r\n", "\n");
  }
}
```

- [ ] **Step 2: 生成 golden 并人工审查**

Run: `mvn test -Dtest=MultiDialectGoldenOutputTest -Dgolden.write=true`
然后逐个 diff 审查 `src/test/resources/golden/tpcds-trino-*.sql` 与 `tpcds-mysql-*.sql`：确认包装形状、引号（trino 双引号按需 / mysql 一律反引号）、LIMIT 原样保留（内层是原文快照）、无策略语句直通。任何"看起来不像该引擎合法 SQL"的行停下修 profile，不要重生成掩盖。
再不带 `-Dgolden.write=true` 跑一遍：

Run: `mvn test -Dtest=MultiDialectGoldenOutputTest`
Expected: PASS。

- [ ] **Step 3: Commit**

```bash
git add src/test/
git commit -m "test: TPC-DS 可移植子集的 trino/mysql golden 锁"
```

---

### Task 10: CLI / REST / 前端方言入口

**Files:**
- Modify: `src/main/java/io/sqlmask/cli/SqlMaskApplication.java`
- Modify: `src/main/java/io/sqlmask/server/ConfigController.java`（若 Task 3 未完成此处，则本任务补齐）
- Modify: `src/main/resources/static/index.html`
- Test: `src/test/java/io/sqlmask/server/ConfigControllerTest.java`（扩展）

**Interfaces:**
- Consumes: `DialectProfiles.names()`。
- Produces: CLI `--dialect {postgresql|trino|mysql}`；`/api/config/parse` 接受 `dialect`；前端方言选择器。

- [ ] **Step 1: CLI 选项更新**

`SqlMaskApplication`：

```java
  @Option(names = "--dialect", defaultValue = "postgresql",
      description = "Target dialect: postgresql, trino or mysql.")
  private String dialect;
```
并把 `execute()` 里的手写校验改为复用注册表（错误消息与 Web 端一致）：

```java
    try {
      io.sqlmask.dialect.DialectRegistry.create(dialect);
    } catch (io.sqlmask.error.SqlMaskException e) {
      err.println("sql-mask: [" + e.getCode() + "] " + e.getMessage());
      return 2;
    }
```
（`DialectProfiles.byName` 抛的 CONFIG_ERROR 即统一文案；`CliOptions` 的 javadoc 同步为三方言。）
`SqlMaskApplicationTest` 中"不支持方言返回 2"的用例改用未知名（如 `oracle`），消息断言放宽为包含 `unsupported dialect`。

- [ ] **Step 2: 前端选择器**

`index.html` 第 201 行徽标处替换为选择器 + 按方言提示：

```html
      <select id="dialect" class="badge" aria-label="方言">
        <option value="postgresql">dialect: postgresql</option>
        <option value="trino">dialect: trino</option>
        <option value="mysql">dialect: mysql</option>
      </select>
      <span class="hint" id="dialect-hint"></span>
```
JS（脚本头部变量区 + 事件绑定区）：

```javascript
  const dialectEl = document.getElementById("dialect");
  const dialectHintEl = document.getElementById("dialect-hint");
  const DIALECT_HINTS = {
    postgresql: "双引号是标识符，字符串用单引号",
    trino: "双引号是标识符，字符串用单引号",
    mysql: "反引号是标识符，双引号是字符串"
  };
  function refreshDialectHint() {
    dialectHintEl.textContent = DIALECT_HINTS[dialectEl.value] || "";
  }
  dialectEl.addEventListener("change", refreshDialectHint);
  refreshDialectHint();
```
第 668 行 fetch body 改为 `dialect: dialectEl.value`；`fetch("/api/config/parse")` 调用处 body 同样加 `dialect: dialectEl.value`。

- [ ] **Step 3: ConfigControllerTest 扩展 + 全量**

在 `ConfigControllerTest` 追加（沿用现有 MockMvc 风格）：

```java
  @Test
  void parseRespectsDialectForTypeValidation() throws Exception {
    String yaml = """
        metadata:
          tables:
            - catalog: shop
              schema: app
              name: orders
              columns:
                - name: taken_at
                  type: datetime
        policies: {}
        """;
    mockMvc.perform(post("/api/config/parse")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataYaml\": " + objectMapper.writeValueAsString(yaml)
                + ", \"dialect\": \"mysql\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tables[0].columns[0].type").value("datetime"));

    mockMvc.perform(post("/api/config/parse")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataYaml\": " + objectMapper.writeValueAsString(yaml)
                + ", \"dialect\": \"postgresql\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }
```
（按该测试文件现有的 objectMapper/mockMvc 命名调整；若现有测试用别的请求构造方式，照抄现有风格。）

Run: `mvn test`
Expected: 全部 PASS。

- [ ] **Step 4: Commit**

```bash
git add -A src/
git commit -m "feat: CLI/REST/前端开放 postgresql|trino|mysql 方言选择"
```

---

### Task 11: README 与收尾验证

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-09-06-multi-dialect-design.md`（§10 各项状态标注）

- [ ] **Step 1: README 更新**

- 标题与首段：从「PostgreSQL SQL 脱敏改写服务」改为「面向 PostgreSQL / Trino / MySQL 的 SQL 脱敏改写服务」；`--dialect` 说明改三方言；
- 新增「方言支持」小节：三方言的引号/大小写语义表（spec §2 表格精简版）、各引擎类型集（spec §4.1/4.2 的类型清单）、各自安全失败清单（MySQL：CTAS 不带 AS、`LIMIT a,b`、`ON DUPLICATE KEY UPDATE`、`REPLACE INTO`；Trino：带 `WITH(...)` 表属性的 CTAS）；
- Task 6 的 pinning 结论写进 MySQL 小节（非限定名跨 schema 是首匹配还是二义，按 Task 6 观察结果写）；
- 回显行为变化：`text` 等类型声明现在原样回显（替换原「text 会规范化显示为 varchar」句子）；
- MySQL 小节注明：内层 SQL 由 MySQL 按自身语义解释（反斜杠转义等），工具保留原文不改变语义（spec §10.4 item 11）。

- [ ] **Step 2: spec §10 状态回写**

把 §10.1/10.2/10.3/10.4 每条标注「已由 Task N 落地」或「由 Task N 观察，结论：…」（如 MySQL 路径行为、CTAS 列定义边界）。

- [ ] **Step 3: 全量最终验证**

Run: `mvn clean test`
Expected: 全部 PASS；`git status` 无未跟踪产物。
再跑一次 CLI 冒烟（可执行 jar 或测试替身均可，重点验证三方言走通）：

```bash
mvn -q package -DskipTests
java -jar target/sql-mask.jar --metadata src/test/resources/metadata/mysql-integration.yaml \
  --sql "SELECT phone FROM customer" --dialect mysql
```
Expected: 输出反引号包装 SQL，退出码 0。

- [ ] **Step 4: Commit**

```bash
git add README.md docs/
git commit -m "docs: README 方言说明与 spec 待办项状态回写"
```

---

## 任务依赖与顺序

Task 1 → 2（渲染层依赖 profile）→ 3（loader 依赖 profile 注册）；Task 4、5 依赖 1-3 且互相独立（可并行）；Task 6 依赖 5；Task 7 依赖 4；Task 8 依赖 4、5（并引用 Task 7 的 trino 元数据资源）；Task 9 依赖 4、5（golden 生成放最后可吸收前面任务的输出微调，故意靠后）；Task 10 依赖 3；Task 11 收尾依赖全部。
