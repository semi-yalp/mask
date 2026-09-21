# 统一查询服务批 2（Hive / SparkSQL）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Hive 与 Spark/SparkSQL 纳入统一查询服务：改写器新增两个方言 profile（hive / sparksql），mask-query 用 hive-jdbc 单驱动执行，元数据采集明确拒绝（表结构走 YAML 导入）。

**Architecture:** 全部沿用批 1 结构与接缝。方言 = `AbstractCalciteDialectAdapter` 子类 + `DialectProfile` 声明式差异（标识符/类型/渲染/conformance），注册进 `DialectProfiles`/`DialectRegistry` 静态表后全链路自动生效（校验、血缘、行过滤、包装、审计零改动）。执行侧只动 `QueryEngine` 枚举（两个新值 + hive2 URL 分支）与 mask-query pom（hive-jdbc 瘦身依赖）。采集侧在 mask-metadata `CollectService` 加一扇门。

**Tech Stack:** calcite-core 1.42.0（`HiveSqlDialect`/`SparkSqlDialect` 均存在，已核实）、mask-sqlparser 的 `SqlMaskParserImpl.FACTORY` + `SqlMaskConformance`（含 Hive/Spark 预留的 `allowInsertOverwrite` 开关）、org.apache.hive:hive-jdbc:3.1.3。

**Spec:** `docs/superpowers/specs/2026-09-17-query-service-batch2-design.md`（同 worktree；与批 1 spec `2026-09-17-query-service-design.md` 配合阅读，继承部分以批 1 为准）

## Global Constraints

- 全仓构建：`mvn test`；单模块：`mvn -pl <module> -am test`（上游模块无匹配测试时加 `-Dsurefire.failIfNoSpecifiedTests=false`）。每任务全绿再提交。
- 提交信息循仓库惯例：`feat(core): 中文描述` / `feat(metadata): ...` / `feat(query): ...` / `docs(query): ...`。
- 密码/API Key 红线同批 1（本批不涉及新凭据面）。
- 错误契约 `{code, message}`；新拒绝路径一律 fail-closed，消息带方言名。
- **包装层与行过滤管线零改动**：Hive/Spark 只新增 profile，不得触碰 `RewriteEngine`/`SqlRewriteService`/`RowFilterRewriter`。
- 新增依赖仅 `org.apache.hive:hive-jdbc:3.1.3`（mask-query，compile）；不得新增其他第三方依赖、不得引入采集（本批没有新的 introspector）。
- Hive/Spark 的 parser conformance 用 `SqlMaskConformance.of(SqlConformanceEnum.LENIENT, false, true)`（allowInsertOverwrite=true 是 mask-sqlparser 为 Hive/Spark/Doris 预留的开关）；校验器 conformance 用 `SqlConformanceEnum.LENIENT`。
- 执行侧 `QueryEngine` 的 `hive`/`sparksql` jdbcUrl 形态：`jdbc:hive2://H:P/DB`（DB 可空，空时 `jdbc:hive2://H:P/`）；`sslmode=require`（区分大小写不敏感）追加 `;ssl=true`。

---
## File Structure

```
mask-core/src/main/java/io/sqlmask/dialect/
  HiveDialectAdapter.java            [Task 1] adapter + NAME
  HiveIdentifierPolicy.java          [Task 1] 反引号（同 MysqlIdentifierPolicy 语义）
  HiveTypeResolver.java              [Task 1] 类型集
  HiveUnparseDialect.java            [Task 1] extends HiveSqlDialect + BETWEEN 覆盖
  SparkSqlDialectAdapter.java        [Task 2] adapter + NAME
  SparkSqlIdentifierPolicy.java      [Task 2]（与 Hive 同实现，独立类型）
  SparkSqlTypeResolver.java          [Task 2]
  SparkSqlUnparseDialect.java        [Task 2] extends SparkSqlDialect + BETWEEN 覆盖
  DialectProfiles.java               [Task 1/2] 注册 hive / sparksql
  DialectRegistry.java               [Task 1/2] 注册 hive / sparksql

mask-metadata/src/main/java/io/sqlmask/metaserver/service/CollectService.java [Task 3] 采集门
mask-metadata/src/test/java/io/sqlmask/metaserver/service/CollectServiceTest.java [Task 3]

mask-query/pom.xml                           [Task 4] hive-jdbc 依赖
mask-query/src/main/java/io/sqlmask/query/executors/QueryEngine.java [Task 4] +HIVE/SPARKSQL + URL 分支
mask-query/src/test/java/io/sqlmask/query/executors/QueryEngineTest.java [Task 4]

README.md                                   [Task 5]
docs/query-acceptance/golden-queries.md      [Task 5] 扩两节
```

---

### Task 1: mask-core — Hive 方言 profile（含注册）

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/dialect/HiveDialectAdapter.java`
- Create: `mask-core/src/main/java/io/sqlmask/dialect/HiveIdentifierPolicy.java`
- Create: `mask-core/src/main/java/io/sqlmask/dialect/HiveTypeResolver.java`
- Create: `mask-core/src/main/java/io/sqlmask/dialect/HiveUnparseDialect.java`
- Modify: `mask-core/src/main/java/io/sqlmask/dialect/DialectProfiles.java`、`DialectRegistry.java`
- Test: `mask-core/src/test/java/io/sqlmask/dialect/HiveDialectProfileTest.java`（新建）

**Interfaces:**
- Consumes: 批 1 契约——`DialectProfile` record、`TypeResolver`（`TypeResolver.split` 返回 `ParsedType(base, precision, scale)`）、`IdentifierPolicy`、`SqlMaskParserImpl.FACTORY`（io.sqlmask.parser）、`SqlMaskConformance.of`、`AbstractCalciteDialectAdapter.checkCreateTableVariant` 钩子；
- Produces: `HiveDialectAdapter.NAME = "hive"`；`DialectProfiles.byName("hive")` 可用；DialectRegistry 可 `create("hive")`；`RewriteEngine.rewrite(metadataYaml, sql, "hive")` 全链路可用。

- [ ] **Step 1: 写失败测试**

新建 `HiveDialectProfileTest`，对齐既有方言测试的 JUnit 风格（参考 `mask-core/src/test/java/io/sqlmask/dialect/` 下既有测试）:

```java
package io.sqlmask.dialect;

import io.sqlmask.rewrite.RewriteEngine;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HiveDialectProfileTest {

  private static final String YAML = """
      metadata:
        tables:
          - catalog: hive
            schema: default
            name: customer
            columns:
              - { name: id, type: bigint }
              - { name: phone, type: string }
      columns:
        - { catalog: hive, schema: default, table: customer, column: phone, policy: m }
      policies:
        m: { udf: mask_phone, arguments: [3, 4] }
      """;

  private final RewriteEngine engine = new RewriteEngine();

  @Test
  void rewritesSelectWithBacktickWrapper() {
    List<StatementRewrite> out = engine.rewrite(YAML, null,
        "SELECT phone FROM default.customer", "hive", io.sqlmask.policy.model.Subject.anonymous());
    assertThat(out).hasSize(1);
    assertThat(out.get(0).kind()).isEqualTo(io.sqlmask.rewrite.StatementKind.SELECT);
    assertThat(out.get(0).masked()).isTrue();
    assertThat(out.get(0).rewrittenSql()).contains(
        "SELECT mask_phone(r.`phone`, 3, 4) AS `phone`");
    assertThat(out.get(0).rewrittenSql()).contains(") AS `r`");
  }

  @Test
  void rowFilterInjectedForHiveTwoPartNames() {
    // 行过滤在 metadata 内嵌路径：单独声明 rowFilter 的表
    String filteredYaml = """
        metadata:
          tables:
            - catalog: hive
              schema: default
              name: customer
              rowFilter: "status = 'active'"
              columns:
                - { name: id, type: bigint }
                - { name: phone, type: string }
        policies: {}
        """;
    List<StatementRewrite> out = engine.rewrite(filteredYaml, null,
        "SELECT phone FROM customer", "hive", io.sqlmask.policy.model.Subject.anonymous());
    assertThat(out.get(0).rowFiltered()).isTrue();
    assertThat(out.get(0).rewrittenSql()).contains(
        "(SELECT `id`, `phone` FROM `hive`.`default`.`customer` "
            + "AS `t` WHERE `status` = 'active')");
  }

  @Test
  void unnamedOutputColumnIsRenderedWithQuotedAlias() {
    List<StatementRewrite> out = engine.rewrite(YAML, null,
        "SELECT upper(phone) FROM customer", "hive",
        io.sqlmask.policy.model.Subject.anonymous());
    assertThat(out.get(0).masked()).isTrue();
    assertThat(out.get(0).rewrittenSql()).contains("AS `EXPR$0`");
  }

  @Test
  void insertOverwriteIsParsedAndWrapped() {
    List<StatementRewrite> out = engine.rewrite(YAML, null,
        "INSERT OVERWRITE TABLE arch SELECT phone FROM customer", "hive",
        io.sqlmask.policy.model.Subject.anonymous());
    assertThat(out).hasSize(1);
    assertThat(out.get(0).kind()).isEqualTo(io.sqlmask.rewrite.StatementKind.INSERT_SELECT);
    assertThat(out.get(0).masked()).isTrue();
    assertThat(out.get(0).rewrittenSql()).startsWith("INSERT OVERWRITE TABLE ");
  }

  @Test
  void typeResolverRejectsUnknownAndComplexTypes() {
    var resolver = new HiveTypeResolver();
    assertThat(resolver.parseColumn("a", "string").sqlTypeName()).isNotNull();
    assertThatThrownBy(() -> resolver.parseColumn("a", "array<int>"))
        .hasMessageContaining("array");
    assertThatThrownBy(() -> resolver.parseColumn("a", "map<string,int>"))
        .hasMessageContaining("map");
    assertThatThrownBy(() -> resolver.parseColumn("a", "struct<x:int>"))
        .hasMessageContaining("struct");
  }
}
```

注意：`StatementRewrite.kind()`、`rowFiltered`、`masked` 均为批 1 已交付字段，若其 JSON/访问器命名与上述不符以实际代码为准。golden 断言里包装层的**确切渲染文本**（`r.`phone``、`AS `r``、内层派生表列清单）以本任务实现后第一次全绿运行的输出为准——这是方言渲染的验收基准；若实现输出与上面预言有出入，**以实际渲染为准**修正测试期望并在提交说明里记录（渲染正确性以「真库可执行」为终局标准，golden 是回归钉子）。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-core -am test -Dtest=HiveDialectProfileTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（HiveDialectAdapter 等不存在）。

- [ ] **Step 3: 实现**

`HiveTypeResolver.java`（模板：`TrinoTypeResolver`；未知/复杂类型一律 `parseError`，**不做**降级）：

```java
package io.sqlmask.dialect;

import io.sqlmask.metadata.TableMetadata;
import org.apache.calcite.sql.type.SqlTypeName;

import java.util.Locale;

/** Hive scalar type declarations (spec batch-2 §3); complex types are rejected. */
public final class HiveTypeResolver implements TypeResolver {

  @Override
  public TableMetadata.Column parseColumn(String name, String typeDeclaration) {
    String raw = typeDeclaration.trim();
    String lowered = raw.toLowerCase(Locale.ROOT);
    ParsedType parsed = TypeResolver.split(lowered, null);
    String base = parsed.base();
    Integer precision = parsed.precision();
    Integer scale = parsed.scale();
    switch (base) {
      case "tinyint" -> { }
      case "smallint" -> { }
      case "int", "integer" -> { }
      case "bigint" -> { }
      case "float" -> { }
      case "double" -> { }
      case "decimal" -> {
        if (scale != null && precision == null) {
          throw parseError(raw);
        }
      }
      case "string" -> { }
      case "varchar", "character varying" -> { /* precision optional */ }
      case "char", "character" -> precision = precision != null ? precision : 1;
      case "boolean" -> { }
      case "date" -> { }
      case "timestamp" -> { }
      case "binary" -> { }
      default -> throw parseError(raw);
    }
    SqlTypeName typeName = switch (base) {
      case "tinyint" -> SqlTypeName.TINYINT;
      case "smallint" -> SqlTypeName.SMALLINT;
      case "int", "integer" -> SqlTypeName.INTEGER;
      case "bigint" -> SqlTypeName.BIGINT;
      case "float" -> SqlTypeName.FLOAT;
      case "double" -> SqlTypeName.DOUBLE;
      case "decimal" -> SqlTypeName.DECIMAL;
      case "string" -> SqlTypeName.VARCHAR;
      case "varchar", "character varying" -> SqlTypeName.VARCHAR;
      case "char", "character" -> SqlTypeName.CHAR;
      case "boolean" -> SqlTypeName.BOOLEAN;
      case "date" -> SqlTypeName.DATE;
      case "timestamp" -> SqlTypeName.TIMESTAMP;
      case "binary" -> SqlTypeName.VARBINARY;
      default -> throw new IllegalStateException("unreachable");
    };
    return new TableMetadata.Column(name, raw, typeName,
        precision != null ? precision : null, scale != null ? scale : null);
  }

  private static SqlMaskException parseError(String raw) {
    return new io.sqlmask.error.SqlMaskException(io.sqlmask.error.SqlMaskException.Code.CONFIG_ERROR,
        "unsupported hive type '" + raw + "' "
            + "(supported: tinyint, smallint, int, bigint, float, double, decimal(p,s), "
            + "string, varchar(n), char(n), boolean, date, timestamp, binary)");
  }
}
```

（`TableMetadata.Column` 的实际构造签名以 `TrinoTypeResolver` 用法为准——它用了
`parseColumn` 同一模式；实现时先读一遍该文件与 `TypeResolver`。）

`HiveIdentifierPolicy.java`（与 `MysqlIdentifierPolicy` 同语义，独立类型）：

```java
package io.sqlmask.dialect;

/** Hive quoting: always backtick-quote wrapper identifiers (same as MySQL). */
public final class HiveIdentifierPolicy implements IdentifierPolicy {

  @Override
  public String render(String name) {
    return "`" + name.replace("`", "``") + "`";
  }
}
```

`HiveUnparseDialect.java`（模板：`MysqlUnparseDialect`——把基类换成
`org.apache.calcite.sql.dialect.HiveSqlDialect` 并用同样的
`unparsePlainBetween` 覆盖 ASYMMETRIC 渲染；先读 `MysqlUnparseDialect` 全文，
除基类外逐字保留）：

```java
package io.sqlmask.dialect;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.dialect.HiveSqlDialect;
import org.apache.calcite.sql.fun.SqlBetweenOperator;

/**
 * Hive-flavored dialect for unparsing. Hive has no ASYMMETRIC/SYMMETRIC
 * keyword after BETWEEN (same gap as MySQL), so plain (ASYMMETRIC) BETWEEN
 * is rendered without the flag; the SYMMETRIC form keeps Calcite's default
 * rendering, which the engine rejects loudly rather than being silently
 * downgraded.
 */
final class HiveUnparseDialect extends HiveSqlDialect {

  HiveUnparseDialect() {
    super(HiveSqlDialect.DEFAULT_CONTEXT);
  }

  @Override
  public void unparseCall(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
    if (call.getOperator() instanceof SqlBetweenOperator between
        && between.flag == SqlBetweenOperator.Flag.ASYMMETRIC) {
      unparsePlainBetween(writer, call, between);
      return;
    }
    super.unparseCall(writer, call, leftPrec, rightPrec);
  }

  private void unparsePlainBetween(SqlWriter writer, SqlCall call,
      SqlBetweenOperator between) {
    call.operand(0).unparse(writer, 0, 0);
    writer.keyword(between.isNegated() ? "NOT BETWEEN" : "BETWEEN");
    call.operand(1).unparse(writer, 0, 0);
    writer.keyword("AND");
    call.operand(2).unparse(writer, 0, 0);
  }
}
```

`HiveDialectAdapter.java`（模板：`MysqlDialectAdapter`——parser 配置改
`Quoting.BACK_TICK` + `Casing.TO_LOWER`（未引号折叠小写）+ 保留
`QuotedCasing.UNCHANGED` + `caseSensitive(false)` + conformance
`SqlMaskConformance.of(SqlConformanceEnum.LENIENT, false, true)`；validator
conformance `SqlConformanceEnum.LENIENT`；schemaPathStyle
`CATALOG_SCHEMA_AND_SCHEMA`；capabilities 与 MySQL 相同 `new DialectCapabilities(false)`；
`checkCreateTableVariant` 与 MySQL 逐字相同（拒绝 REPLACE/VOLATILE/SET/MULTISET）；
`Hive` 类型用 `HiveTypeResolver`；函数表用 `MysqlFunctions.TABLE` 同款拼法——新建
`HiveFunctions` 不必要，先复用 `MysqlFunctions.TABLE`（其内容为 std 库 + 常用标量，
Hive 校验所需的函数绝大多数来自 SqlStdOperatorTable；实现时若 validation 报未知函数
来自函数表缺项，再加一个 `HiveFunctions` 并在报告说明））：

```java
package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.parser.SqlMaskConformance;
import io.sqlmask.parser.SqlMaskParserImpl;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.babel.SqlBabelCreateTable;
import org.apache.calcite.sql.babel.TableCollectionType;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;

import java.util.List;

/**
 * Hive: backtick identifiers, unquoted names fold to lower case (Hive storage
 * semantics), case-insensitive matching, LENIENT conformance with the
 * Hive/Spark INSERT OVERWRITE extension enabled. Wrapper and row-filter
 * rendering reuse the MySQL-style projection aliases; babel-only CREATE
 * TABLE variants are refused so only plain CTAS reaches the composer.
 */
public final class HiveDialectAdapter extends AbstractCalciteDialectAdapter {

  public static final String NAME = "hive";

  public HiveDialectAdapter() {
    super(new DialectProfile(
        NAME,
        SqlParser.config()
            .withParserFactory(SqlMaskParserImpl.FACTORY)
            .withQuoting(Quoting.BACK_TICK)
            .withUnquotedCasing(Casing.TO_LOWER)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(false)
            .withConformance(SqlMaskConformance.of(SqlConformanceEnum.LENIENT, false, true)),
        SqlConformanceEnum.LENIENT,
        false,
        MysqlFunctions.TABLE,
        new HiveTypeResolver(),
        new HiveUnparseDialect(),
        new HiveIdentifierPolicy(),
        DialectProfile.SchemaPathStyle.CATALOG_SCHEMA_AND_SCHEMA,
        new DialectCapabilities(false)));
  }

  /** Refuses babel-only CREATE TABLE variants whose syntax the composer cannot reproduce. */
  @Override
  protected void checkCreateTableVariant(SqlNode writeStatement) {
    if (!(writeStatement instanceof SqlBabelCreateTable babel)) {
      return;
    }
    List<SqlNode> operands = babel.getOperandList();
    boolean replace = ((SqlLiteral) operands.get(0)).booleanValue();
    TableCollectionType collectionType =
        ((SqlLiteral) operands.get(1)).symbolValue(TableCollectionType.class);
    boolean volatileTable = ((SqlLiteral) operands.get(2)).booleanValue();
    if (replace || volatileTable
        || collectionType == TableCollectionType.MULTISET) {
      throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
          "unsupported CREATE TABLE variant (REPLACE / VOLATILE / SET / MULTISET); "
              + "only plain CREATE TABLE [IF NOT EXISTS] ... AS SELECT is supported (hive)");
    }
  }
}
```

注册：`DialectProfiles` static 块加
`HiveDialectAdapter hive = new HiveDialectAdapter(); PROFILES.put(hive.name(), hive.profile());`；
`DialectRegistry` static 块加 `ADAPTERS.put(HiveDialectAdapter.NAME, HiveDialectAdapter::new);`。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-core -am test`
Expected: PASS（新测试 + 既有全部；`DialectProducts.byName` 错误消息因注册表
自动扩为 5 方言——既有测试若断言「supported dialects: postgresql, trino, mysql」
需同步为含 hive）。

- [ ] **Step 5: Commit**

```bash
git add mask-core/src/main/java/io/sqlmask/dialect/Hive*.java \
  mask-core/src/main/java/io/sqlmask/dialect/DialectProfiles.java \
  mask-core/src/main/java/io/sqlmask/dialect/DialectRegistry.java \
  mask-core/src/test/java/io/sqlmask/dialect/HiveDialectProfileTest.java
git commit -m "feat(core): Hive 方言 profile（反引号/折叠小写/类型集/INSERT OVERWRITE 扩展）"
```

---

### Task 2: mask-core — Spark SQL 方言 profile（含注册）

**Files:**
- Create: `mask-core/src/main/java/io/sqlmask/dialect/SparkSqlDialectAdapter.java`
- Create: `mask-core/src/main/java/io/sqlmask/dialect/SparkSqlIdentifierPolicy.java`
- Create: `mask-core/src/main/java/io/sqlmask/dialect/SparkSqlTypeResolver.java`
- Create: `mask-core/src/main/java/io/sqlmask/dialect/SparkSqlUnparseDialect.java`
- Modify: `mask-core/src/main/java/io/sqlmask/dialect/DialectProfiles.java`、`DialectRegistry.java`
- Test: `mask-core/src/test/java/io/sqlmask/dialect/SparkSqlDialectProfileTest.java`（新建）

**Interfaces:**
- Consumes: Task 1 的全部模板（Hive 四件套结构、注册方式）；
- Produces: `SparkSqlDialectAdapter.NAME = "sparksql"`；`DialectProfiles.byName("sparksql")` / `DialectRegistry.create("sparksql")` 可用。

- [ ] **Step 1: 写失败测试**

`SparkSqlDialectProfileTest` 结构同 Hive（YAML 的 catalog 用 `spark`、schema 用
`default`；`type: string`），四个用例：

1. `rewritesSelectWithBacktickWrapper`——`SELECT phone FROM default.customer` →
   masked=true、注意 **Spark 未引号折叠小写**：golden 断言 `AS `r`` 与
   `r.`phone``（与 Hive 相同形态）。
2. `rowFilterInjectedForTwoPartNames`——rowFilter YAML 复查注入（与 Hive 相同断言形态）。
3. `unnamedOutputColumnIsRenderedWithQuotedAlias`——`AS `EXPR$0``。
4. `typeResolverRejectsUnknownAndComplexTypes`——`SparkSqlTypeResolver` 对
   `array<int>` / `map<string,int>` / `struct<x:int>` / 未知名（如 `timestamp_ntz`）
   抛 CONFIG_ERROR。

（三个语句类用例的断言文本拷贝自 Task 1 对应方法，把 catalog/schema 与
hive→sparksql 字样替换。）

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-core -am test -Dtest=SparkSqlDialectProfileTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败。

- [ ] **Step 3: 实现**

Task 1 四个文件的 Spark 版，逐处替换：

- `SparkSqlTypeResolver`：类型集与 Hive 相同（tinyint/smallint/int/bigint/float/double/decimal/string/varchar/char/boolean/date/timestamp/binary），错误消息与支持清单逐字同 Hive 版（错误前缀 `unsupported sparksql type`）；实现方式与 Hive 相同（两个类各自独立，不抽公共基类——共 14 种类型清单、代码量与 Hive 相同，为避免 Task 1/2 之间的共享类型形成隐式耦合，宁可各写一份，报告里不必说明）；
- `SparkSqlIdentifierPolicy`：与 Hive 同实现；
- `SparkSqlUnparseDialect`：基类 `org.apache.calcite.sql.dialect.SparkSqlDialect`（构造 `super(SparkSqlDialect.DEFAULT_CONTEXT)`），BETWEEN 覆盖与 Hive 版相同；
- `SparkSqlDialectAdapter`：NAME = `"sparksql"`；parser 配置与 Hive 相同（BACK_TICK + TO_LOWER + caseSensitive(false) + `SqlMaskConformance.of(SqlConformanceEnum.LENIENT, false, true)`）；validator conformance LENIENT；`MysqlFunctions.TABLE`；`CATALOG_SCHEMA_AND_SCHEMA`；`new DialectCapabilities(false)`；`checkCreateTableVariant` 与 Hive 版逐字相同。

注册两处 static 块各加一行（Hive 行之后）。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-core -am test`
Expected: PASS（6 方言全绿）。

- [ ] **Step 5: Commit**

```bash
git add mask-core/src/main/java/io/sqlmask/dialect/SparkSql*.java \
  mask-core/src/main/java/io/sqlmask/dialect/DialectProfiles.java \
  mask-core/src/main/java/io/sqlmask/dialect/DialectRegistry.java \
  mask-core/src/test/java/io/sqlmask/dialect/SparkSqlDialectProfileTest.java
git commit -m "feat(core): Spark SQL 方言 profile（与 Hive 同族：反引号/折叠小写/类型集/OVERWRITE 扩展）"
```

---

### Task 3: mask-metadata — 采集门（hive/sparksql 拒绝采集）

**Files:**
- Modify: `mask-metadata/src/main/java/io/sqlmask/metaserver/service/CollectService.java`
- Test: `mask-metadata/src/test/java/io/sqlmask/metaserver/service/CollectServiceTest.java`

**Interfaces:**
- Consumes: `CollectService.collect(String name)` 现有入口；`InstanceRow.dialect()`；
  `SqlMaskException(Code.CONFIG_ERROR, message)`；
- Produces: `CollectService` 在构造 `ConnectionSpec` 前检查
  `row.dialect()` ∈ {postgresql, mysql, trino}，否则 CONFIG_ERROR（消息见 Step 3）
  ——「执行优先不采集」硬边界。注意 `DialectProfiles.byName` 现在已接受
  hive/sparksql（意味着实例可创建、可导入 YAML），只有采集这一条路径被拒。

- [ ] **Step 1: 写失败测试**

在 `CollectServiceTest` 追加（沿用类内既有桩与断言风格）：

```java
@Test
void collectRejectsHiveAndSparksqlDialects() {
  // 用类内既有 create/helper 造一个 dialect=hive、有 connection 的实例
  buildInstanceWithConnection("hive-inst", "hive");   // 以类内既有 helper 为准
  buildInstanceWithConnection("spark-inst", "sparksql");
  assertThatThrownBy(() -> service.collect("hive-inst"))
      .isInstanceOf(SqlMaskException.class)
      .hasMessageContaining("collection is not supported for dialect 'hive'")
      .hasMessageContaining("import");
  assertThatThrownBy(() -> service.collect("spark-inst"))
      .hasMessageContaining("sparksql");
  // 既有三方言采集路径不受影响（类内既有用例保住回归）
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-metadata -am test -Dtest=CollectServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（未拒绝，走真采集桩路径）。

- [ ] **Step 3: 实现**

`CollectService.collect` 在 `connection == null` 分支之后、`collectMetrics.record(...)`
之前插入：

```java
    if (!SUPPORTED_COLLECT_DIALECTS.contains(row.dialect())) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "collection is not supported for dialect '" + row.dialect()
              + "'; import table structures via instance YAML import instead");
    }
```

类内加常量：

```java
  private static final java.util.Set<String> SUPPORTED_COLLECT_DIALECTS =
      java.util.Set.of("postgresql", "mysql", "trino");
```

（POST 采集入口的 engine 参数若另有限制逻辑，保持不动——本门只拦实例级采集。）

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-metadata -am test`
Expected: PASS（含既有 CollectServiceTest 全部）。

- [ ] **Step 5: Commit**

```bash
git add mask-metadata/src/main/java/io/sqlmask/metaserver/service/CollectService.java \
  mask-metadata/src/test/java/io/sqlmask/metaserver/service/CollectServiceTest.java
git commit -m "feat(metadata): 采集门——hive/sparksql 明确拒绝（执行优先不采集的硬边界）"
```

---

### Task 4: mask-query — 引擎目录扩展 + hive-jdbc 依赖

**Files:**
- Modify: `mask-query/pom.xml`（hive-jdbc 依赖）
- Modify: `mask-query/src/main/java/io/sqlmask/query/executors/QueryEngine.java`
- Test: `mask-query/src/test/java/io/sqlmask/query/executors/QueryEngineTest.java`

**Interfaces:**
- Consumes: `QueryEngine` 现有枚举四值、`jdbcUrl(ConnectionView)` 实例方法、
  `ConnectionView(host, port, database, dbUser, passwordRef, sslmode, connectTimeoutSeconds)`（批 1）；
- Produces: `QueryEngine.HIVE("hive","hive",10000)`、`SPARKSQL("sparksql","sparksql",10000)`；
  `HIVE.jdbcUrl(conn)` → `jdbc:hive2://h:p/db[;ssl=true]`；`SPARKSQL.jdbcUrl(conn)` → 同形态
  （database 为空时 `jdbc:hive2://h:p/`）；`of("hive")`/`of("sparksql")` 归一可用。

- [ ] **Step 1: 写失败测试**

`QueryEngineTest` 追加：

```java
@Test
void hiveAndSparksqlBuildHive2Urls() {
  ConnectionView plain = conn("disable");
  ConnectionView secure = conn("require");
  assertThat(QueryEngine.HIVE.jdbcUrl(plain))
      .isEqualTo("jdbc:hive2://h:1234/db");
  assertThat(QueryEngine.HIVE.jdbcUrl(secure))
      .isEqualTo("jdbc:hive2://h:1234/db;ssl=true");
  assertThat(QueryEngine.SPARKSQL.jdbcUrl(plain))
      .isEqualTo("jdbc:hive2://h:1234/db");
  ConnectionView emptyDb = new ConnectionView("h", 10000, "", "u", "REF", "disable", 10);
  assertThat(QueryEngine.SPARKSQL.jdbcUrl(emptyDb))
      .isEqualTo("jdbc:hive2://h:10000/");
  assertThat(QueryEngine.of(" Hive ")).isEqualTo(QueryEngine.HIVE);
  assertThat(QueryEngine.of("sparksql")).isEqualTo(QueryEngine.SPARKSQL);
  assertThat(QueryEngine.HIVE.dialect()).isEqualTo("hive");
  assertThat(QueryEngine.SPARKSQL.dialect()).isEqualTo("sparksql");
  assertThat(QueryEngine.HIVE.defaultPort()).isEqualTo(10000);
}
```

（`conn(String sslmode)` helper 是类内既有私有方法。）

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl mask-query -am test -Dtest=QueryEngineTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（枚举无 HIVE）。

- [ ] **Step 3: 实现**

`QueryEngine` 枚举追加两值（值为灰字常量按既有格式，注释对齐既有风格）：

```java
  HIVE("hive", "hive", 10000),
  SPARKSQL("sparksql", "sparksql", 10000),
```

`jdbcUrl` switch 加分支（追加在 TRINO 之后）：

```java
      case HIVE, SPARKSQL -> {
        String ssl = sslmode.toLowerCase(Locale.ROOT);
        if (!ssl.equals("disable") && !ssl.equals("require")) {
          throw new QueryException(QueryException.CONFIG_ERROR,
              "unsupported sslmode '" + sslmode + "' for " + id
                  + " (supported: disable, require)");
        }
        String db = c.database() == null ? "" : c.database();
        yield "jdbc:hive2://" + c.host() + ":" + c.port() + "/" + db
            + ("require".equals(ssl) ? ";ssl=true" : "");
      }
```

注意：`sslmode` 变量在现有方法内已有（`String sslmode = c.sslmode() == null ||
isBlank ? "disable" : c.sslmode();`）——分支内直接复用该方法头部的 `sslmode`
小写化，与既有 MYSQL/TRINO 分支写法保持一致。

`mask-query/pom.xml` 追加依赖（在 trino-jdbc 之后）：

```xml
    <dependency>
      <groupId>org.apache.hive</groupId>
      <artifactId>hive-jdbc</artifactId>
      <version>3.1.3</version>
      <exclusions>
        <exclusion>
          <groupId>org.slf4j</groupId>
          <artifactId>slf4j-log4j12</artifactId>
        </exclusion>
        <exclusion>
          <groupId>log4j</groupId>
          <artifactId>log4j</artifactId>
        </exclusion>
        <exclusion>
          <groupId>javax.servlet</groupId>
          <artifactId>servlet-api</artifactId>
        </exclusion>
        <exclusion>
          <groupId>org.apache.zookeeper</groupId>
          <artifactId>zookeeper</artifactId>
        </exclusion>
      </exclusions>
    </dependency>
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -pl mask-query -am test`
Expected: PASS（依赖引入后既有上下文测试仍绿——这是 hive-jdbc 传递依赖与
Spring 上下文无冲突的直接证据；若出现包冲突（如 javax.annotation/jsr305
重复），以 exclude 最小化解决并报告）。

- [ ] **Step 5: Commit**

```bash
git add mask-query/pom.xml mask-query/src/main/java/io/sqlmask/query/executors/QueryEngine.java \
  mask-query/src/test/java/io/sqlmask/query/executors/QueryEngineTest.java
git commit -m "feat(query): Hive/SparkSQL 引擎目录（hive2 URL、缺省 10000）与 hive-jdbc 依赖"
```

---

### Task 5: 文档 — README 与 golden 验收清单

**Files:**
- Modify: `README.md`
- Modify: `docs/query-acceptance/golden-queries.md`

**Interfaces:** 纯文档；内容以 spec 批 2 §3/§4/§5/§6/§7 为准。

- [ ] **Step 1: README 更新**

1. 「方言支持」表加两行（Hive / Spark SQL）：引号=反引号（按需）；未引号=折叠小写；
   字符串=单引号；两段名 `db.table` 支持（db = 声明 schema）；非限定名冲突=schema
   字母序首匹配（同 MySQL）；
2. 各引擎类型集加两节（Hive 14 种 / Spark 同 14 种，含「复杂类型拒绝声明」一句——
   与 trino 的降级路径对比说明）；
3. 「安全失败清单」加：Hive/Spark 的 REPLACE/VOLATILE/SET/MULTISET CTAS 变体拒绝
   （babel 语法）；STORED AS / USING 等专有 CTAS 变体按解析失败拒绝；
4. 元数据微服务章节「采集」注明：`hive`/`sparksql` 方言实例**不支持 pull-metadata**
   （采集端点明确拒绝），表结构用 YAML 导入（`POST /api/instances/import`）；
5. 查询服务章节：引擎表加 hive / sparksql（jdbc:hive2://，缺省 10000）；
   「时间护栏差异」注记（hive-jdbc setQueryTimeout 支持不全，容器兜底为主路径——与
   批 1 spec §7.5 加注同一语气）；
6. UDF 部署前提表加两行：Hive `CREATE TEMPORARY FUNCTION <name> AS '<class>'
   USING JAR '<path>'`；Spark `CREATE [OR REPLACE] FUNCTION <name> AS '<class>'
   USING JAR '<path>'`；
7. 「安全失败清单」通用段落不动。

- [ ] **Step 2: golden 清单扩展**

`docs/query-acceptance/golden-queries.md` 加两节（格式对齐既有节：三列表
原始 SQL / 改写后 SQL / 期望脱敏结果；「改写后 SQL」列为形态示意，以期望结果列
为验收基准）：

- **Hive 节**：基础脱敏（`SELECT phone FROM default.customer` → 单行脱敏结果）、
  行过滤叠加、两段名查询（`SELECT phone FROM customer` 经默认 schema 解析）、
  `INSERT OVERWRITE TABLE arch SELECT phone FROM customer`（写语句 → 目标表
  行被脱敏，kind=INSERT_SELECT）、LIMIT；UDF 建函数 DDL 两条
  （TEMPORARY 与永久 JAR 版）；
- **Spark SQL 节**：同四条查询（catalog 声明为 `spark`）+ 注记：Spark 未引号
  折叠小写与大小写不敏感；catalog 若为 `spark_catalog` 以实例声明为准；
- 两节各补一条**运行方式**：compose profile 的 `hive` 与 `spark` 服务，HS2
  端口 10000；
- 顶部「引擎与 UDF 前提」表加两行（Hive JAR 函数 / Spark JAR 函数）。

- [ ] **Step 3: 全仓回归**

Run: `mvn test`（根 pom 5 模块；surefire 无匹配模块需 `-Dsurefire.failIfNoSpecifiedTests=false` 时单独处理）
Expected: 全部 PASS（含 Task 1-4 新增测试）。

- [ ] **Step 4: Commit**

```bash
git add README.md docs/query-acceptance/golden-queries.md
git commit -m "docs(query): Hive/SparkSQL 方言支持、采集边界、护栏差异与 golden 验收两节"
```

---

## 任务依赖与执行顺序

```
Task 1 → Task 2（Hive → Spark，模板递进；同文件注册）
Task 3 依赖 Task 1/2 的注册（测试要造 hive 方言实例——也可先建实例后注册，但
        CollectServiceTest 的断言与注册表无关，可并行；按顺序执行最稳）
Task 4 独立于 1-3（mask-query 模块），可并行
Task 5 依赖全部
```

执行环境：从 `docs/query-batch2-spec` 分支（已含批 2 spec）派生实施分支，或在
该分支直接实施（本分支只有两个 docs 提交，工作区干净）；实施期间 main 可能被
并行会话推进，合入时按批 1 经验解决冲突（冲突面预计限于 DialectProfiles/
DialectRegistry/CollectService 三个文件）。