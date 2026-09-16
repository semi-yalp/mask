# mask-sqlparser 自定义解析器实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 mask-sqlparser 模块，基于 Calcite 官方 codegen 扩展机制生成自定义解析器 `SqlMaskParserImpl`，支持 `SELECT TOP (n)` 与 `INSERT OVERWRITE [TABLE]`（conformance 开关守卫），并将 mask-core 三方言全量切换到它。

**Architecture:** 从 calcite-core 1.42.0 二进制 jar 厂商化 codegen 文件（Parser.jj / default_config.fmpp / includes），FMPP 展开自有 config.fmpp（数据自包含：`default` 键嵌套粘贴 default_config 全文，`parser` 键只放覆盖项），javacc 生成解析器。扩展走官方挂点：INSERT OVERWRITE 用 `statementParserMethods`（Parser.jj 零改动），TOP 在 `SqlSelect()` 与 `OrderByLimitOpt` 加两处小 patch。开关是 `SqlConformance` 委托类（`SqlMaskConformance`），方言不开 → 精确报错。语法规格与安全口径见 spec。

**Tech Stack:** Java 21、Maven 多模块、Calcite 1.42.0（core + babel）、fmpp-maven-plugin 1.0、javacc-maven-plugin 3.x、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-09-17-custom-parser-design.md`

## Global Constraints

- Calcite 版本锁 1.42.0（与父 pom `calcite.version` 一致），codegen 基文件必须从同版本 jar 解出。
- 生成解析器包名 `io.sqlmask.parser`，类名 `SqlMaskParserImpl`，静态 `FACTORY` 与 `SqlBabelParserImpl.FACTORY` 同形。
- `TOP`/`OVERWRITE` 必须是**非保留字**（`nonReservedKeywordsToAdd`），作标识符（列名/别名/表名）的用法不得回归。
- 方言开关关闭时，新解析器行为必须与 Babel 等价（差分测试证明）；`validatorConformance` 一律传原 `SqlConformanceEnum`，不传包装类。
- 所有新语法失败 = `SqlParseException`（带位置），沿 `SqlMaskException(PARSE_ERROR)` fail-closed；拒绝项要有专有错误消息。
- 语句范围不变：管线只收 SELECT / WITH…SELECT / INSERT…SELECT / CTAS；`PERCENT`、`WITH TIES`、`PARTITION (…)`、`DIRECTORY`、TOP 与 LIMIT/OFFSET/FETCH 并存 → 明确拒绝。
- mask-lite 不动。所有工作提交到 main 分支（当前工作区在 `feature/audit-log-es` 上有并行会话，禁止把提交落在该分支——用计划末尾的「main 提交规程」）。
- 每 Task 结束跑一次该 Task 的验证命令并 commit；模块内测试命令统一 `mvn -pl mask-sqlparser test`，mask-core 为 `mvn -pl mask-sqlparser,mask-core -am test`。

---

### Task 1: mask-sqlparser 模块骨架 + 代码生成构建链（baseline 解析器）

**Files:**
- Create: `mask-sqlparser/pom.xml`
- Create: `mask-sqlparser/src/main/codegen/config.fmpp`
- Create（vendor，从 jar 解出）: `mask-sqlparser/src/main/codegen/default_config.fmpp`、`mask-sqlparser/src/main/codegen/templates/Parser.jj`、`mask-sqlparser/src/main/codegen/includes/parserImpls.ftl`、`mask-sqlparser/src/main/codegen/includes/compoundIdentifier.ftl`
- Create: `mask-sqlparser/EXTENSIONS.md`
- Modify: `pom.xml`（父 pom `<modules>` 增加 `<module>mask-sqlparser</module>`）
- Test: `mask-sqlparser/src/test/java/io/sqlmask/parser/ParserSmokeTest.java`

**Interfaces:**
- Produces: `io.sqlmask.parser.SqlMaskParserImpl.FACTORY`（`org.apache.calcite.sql.parser.SqlParserImplFactory` 类型，后续所有 Task 消费）。
- 本 Task 解决 spec §11.1（FMPP 数据自包含合并）与 §11.2（插件版本）。

- [ ] **Step 1: 厂商化 codegen 文件（从锁定版本的 jar 解出）**

```bash
cd "C:/Users/yhh/orca/mask"
mkdir -p mask-sqlparser/src/main/codegen/templates mask-sqlparser/src/main/codegen/includes
cd mask-sqlparser/src/main/codegen
JAR=~/.m2/repository/org/apache/calcite/calcite-core/1.42.0/calcite-core-1.42.0.jar
unzip -o "$JAR" "codegen/templates/Parser.jj" "codegen/includes/*" "codegen/default_config.fmpp" -d /tmp/mask-parser-vendor
cp /tmp/mask-parser-vendor/codegen/templates/Parser.jj templates/
cp /tmp/mask-parser-vendor/codegen/includes/parserImpls.ftl /tmp/mask-parser-vendor/codegen/includes/compoundIdentifier.ftl includes/
cp /tmp/mask-parser-vendor/codegen/default_config.fmpp .
```

注意：jar 内 codegen 与 1.42.0 tag 的 GitHub 版本同源（templates/Parser.jj、includes/parserImpls.ftl、includes/compoundIdentifier.ftl、default_config.fmpp）。若某文件在 jar 中不存在则从 `https://raw.githubusercontent.com/apache/calcite/calcite-1.42.0/core/src/main/codegen/...` 拉取（本次探索已确认 jar 内齐全）。

- [ ] **Step 2: 写 config.fmpp（数据自包含：default 键嵌套全文，parser 键只放覆盖项）**

创建 `mask-sqlparser/src/main/codegen/config.fmpp`：

```
data: {
  parser: {
    # Generated parser implementation package and class name.
    package: "io.sqlmask.parser",
    class: "SqlMaskParserImpl",

    # List of files in @includes directory.
    implementationFiles: [
      "parserImpls.ftl"
    ]
  }

  # Calcite 模板通过 `parser.X!default.parser.X` 读配置；把 default_config.fmpp
  # 全文嵌套在 default 键下，模板的所有回退键即可解析，无需构建期合并。
  # 升级 Calcite 时：重新解出 default_config.fmpp 并原样替换本块（见 EXTENSIONS.md）。
  default: {
    # ===== 以下为 default_config.fmpp 全文原样粘贴（464 行，vendored 同目录） =====
```

然后把 `default_config.fmpp` 的全文（从第一行注释到最后一行 `}`）粘贴进 `default: {` 与结尾 `}` 之间，最后补：

```
  }
}
freemarkerLinks: {
  includes: includes/
}
```

粘贴完成后核对：文件以 `data: {` 开头，`default: {` 块内的顶层键恰好是 `parser`（即 default_config.fmpp 的根哈希），文件末尾是 `freemarkerLinks` 块。

- [ ] **Step 3: 写 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.sqlmask</groupId>
    <artifactId>sql-mask-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>
  <artifactId>mask-sqlparser</artifactId>

  <dependencies>
    <dependency>
      <groupId>org.apache.calcite</groupId>
      <artifactId>calcite-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>com.googlecode.fmpp-maven-plugin</groupId>
        <artifactId>fmpp-maven-plugin</artifactId>
        <version>1.0</version>
        <executions>
          <execution>
            <phase>generate-sources</phase>
            <goals><goal>generate</goal></goals>
          </execution>
        </executions>
        <configuration>
          <cfgFile>src/main/codegen/config.fmpp</cfgFile>
          <templateDirectory>src/main/codegen/templates</templateDirectory>
          <template>Parser.jj</template>
          <outputDirectory>target/generated-sources/fmpp</outputDirectory>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.codehaus.mojo</groupId>
        <artifactId>javacc-maven-plugin</artifactId>
        <version>3.0.3</version>
        <executions>
          <execution>
            <goals><goal>javacc</goal></goals>
          </execution>
        </executions>
        <configuration>
          <sourceDirectory>target/generated-sources/fmpp</sourceDirectory>
          <includes><include>**/Parser.jj</include></includes>
          <outputDirectory>target/generated-sources/javacc</outputDirectory>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

父 pom（`C:/Users/yhh/orca/mask/pom.xml`）`<modules>` 列表加一行 `<module>mask-sqlparser</module>`（放在 mask-core 之前，保持依赖顺序）。groupId/version 以父 pom 实际值为准（打开父 pom 抄 `<groupId>`/`<version>`，不要凭本计划的占位值提交）。javacc-maven-plugin 版本 3.0.3 拉取失败则改 2.6（Flink 同款）——这是 spec §11.2 的两个候选。

- [ ] **Step 4: 构建并确认生成产物**

Run: `mvn -pl mask-sqlparser -am install`
Expected: BUILD SUCCESS；`mask-sqlparser/target/generated-sources/javacc/io/sqlmask/parser/SqlMaskParserImpl.java` 存在。

若 FMPP 阶段报 `default` 未定义：说明 `default: {}` 嵌套粘贴有误（常见：粘贴内容带了 `data:` 包装或漏了末尾 `}`）——对照 default_config.fmpp 原文修正 config.fmpp。若 javacc 阶段语法错误：说明 vendor 的 Parser.jj 与 FMPP 展开产物不一致，重跑 Step 1。此两步失败不得跳过，必须修到绿。

- [ ] **Step 5: 写冒烟测试（先于 Task 2-4 证明 baseline 可用）**

```java
package io.sqlmask.parser;

import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ParserSmokeTest {

  private SqlNode parse(String sql) throws Exception {
    return SqlParser.create(sql,
        SqlParser.config().withParserFactory(SqlMaskParserImpl.FACTORY)).parseStmt();
  }

  @Test
  void parsesPlainSelect() throws Exception {
    assertEquals(SqlKind.SELECT, parse("SELECT 1").getKind());
  }

  @Test
  void parsesInsertInto() throws Exception {
    assertEquals(SqlKind.INSERT, parse("INSERT INTO t SELECT id FROM t").getKind());
  }

  @Test
  void factoryIsNotNull() {
    assertNotNull(SqlMaskParserImpl.FACTORY);
  }
}
```

Run: `mvn -pl mask-sqlparser test`
Expected: PASS（3 个用例）。

- [ ] **Step 6: 写 EXTENSIONS.md**

内容必须包含：provenance（calcite-core 1.42.0 jar 内 `codegen/` 目录 + 解出命令）、本次扩展 diff 清单（当前为空，Task 3/4/7 回填）、Calcite 升级 runbook（重新解出 4 个文件 → 重放 diff → 重跑差分与三方言套件）。

- [ ] **Step 7: Commit（main 提交规程，见计划末尾）**

```bash
git add mask-sqlparser pom.xml
git commit -m "feat(sqlparser): mask-sqlparser 模块骨架，codegen 构建链产出 baseline 解析器"
```

---

### Task 2: SqlMaskConformance 开关类

**Files:**
- Create: `mask-sqlparser/src/main/java/io/sqlmask/parser/SqlMaskConformance.java`
- Test: `mask-sqlparser/src/test/java/io/sqlmask/parser/SqlMaskConformanceTest.java`

**Interfaces:**
- Produces: `SqlMaskConformance extends SqlDelegatingConformance`，构造器 `SqlMaskConformance(SqlConformance delegate, boolean allowTopN, boolean allowInsertOverwrite)`，静态工厂 `public static SqlMaskConformance of(SqlConformance delegate, boolean allowTopN, boolean allowInsertOverwrite)`，访问器 `public boolean isTopNAllowed()`、`public boolean isInsertOverwriteAllowed()`。Task 3/4 的语法动作与 Task 6 的方言配置依赖这两个访问器名。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.parser;

import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlMaskConformanceTest {

  @Test
  void flagsDefaultToClosed() {
    SqlMaskConformance c = SqlMaskConformance.of(SqlConformanceEnum.BABEL, false, false);
    assertFalse(c.isTopNAllowed());
    assertFalse(c.isInsertOverwriteAllowed());
  }

  @Test
  void flagsOpenIndependently() {
    SqlMaskConformance c = SqlMaskConformance.of(SqlConformanceEnum.BABEL, true, false);
    assertTrue(c.isTopNAllowed());
    assertFalse(c.isInsertOverwriteAllowed());
  }

  @Test
  void delegatesToWrappedConformance() {
    // 语义委托：包装 DEFAULT 后，DEFAULT 独有的限制保持生效
    SqlConformance c = SqlMaskConformance.of(SqlConformanceEnum.DEFAULT, true, true);
    assertFalse(c.isLimitStartCountAllowed());   // DEFAULT 拒绝 LIMIT n, m
    SqlConformance m = SqlMaskConformance.of(SqlConformanceEnum.MYSQL_5, true, true);
    assertTrue(m.isLimitStartCountAllowed());    // MYSQL_5 允许
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-sqlparser test -Dtest=SqlMaskConformanceTest`
Expected: COMPILATION ERROR（SqlMaskConformance 不存在）。

- [ ] **Step 3: 实现**

```java
package io.sqlmask.parser;

import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlDelegatingConformance;

/**
 * Parser-side conformance：委托底层方言 conformance，外加两个方言扩展开关。
 * 仅用于 SqlParser.config；校验器一律使用未包装的原 conformance（spec §4.5）。
 */
public final class SqlMaskConformance extends SqlDelegatingConformance {

  private final boolean allowTopN;
  private final boolean allowInsertOverwrite;

  private SqlMaskConformance(SqlConformance delegate, boolean allowTopN, boolean allowInsertOverwrite) {
    super(delegate);
    this.allowTopN = allowTopN;
    this.allowInsertOverwrite = allowInsertOverwrite;
  }

  public static SqlMaskConformance of(SqlConformance delegate, boolean allowTopN, boolean allowInsertOverwrite) {
    return new SqlMaskConformance(delegate, allowTopN, allowInsertOverwrite);
  }

  /** SQL Server 风格 SELECT TOP (n) 是否放行。 */
  public boolean isTopNAllowed() {
    return allowTopN;
  }

  /** Hive/Spark/Doris 风格 INSERT OVERWRITE 是否放行。 */
  public boolean isInsertOverwriteAllowed() {
    return allowInsertOverwrite;
  }
}
```

实现注意：若 `SqlDelegatingConformance` 是 final/构造器签名不同（1.42 实际为准，`org.apache.calcite.sql.validate.SqlDelegatingConformance`），按实际签名调整，保持「委托 + 两布尔」形态与访问器名不变。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl mask-sqlparser test -Dtest=SqlMaskConformanceTest`
Expected: PASS（3 个用例）。

- [ ] **Step 5: Commit**

```bash
git add mask-sqlparser/src/main/java/io/sqlmask/parser/SqlMaskConformance.java mask-sqlparser/src/test/java/io/sqlmask/parser/SqlMaskConformanceTest.java
git commit -m "feat(sqlparser): SqlMaskConformance 方言扩展开关（TOP/OVERWRITE）"
```

---

### Task 3: OVERWRITE 语法扩展（statementParserMethods 路线，Parser.jj 零改动）

**Files:**
- Modify: `mask-sqlparser/src/main/codegen/config.fmpp`（`parser` 覆盖块加 5 个键）
- Create: `mask-sqlparser/src/main/codegen/includes/maskParserImpls.ftl`
- Create: `mask-sqlparser/src/main/java/io/sqlmask/parser/SqlInsertOverwrite.java`
- Test: `mask-sqlparser/src/test/java/io/sqlmask/parser/InsertOverwriteTest.java`

**Interfaces:**
- Consumes: `SqlMaskConformance.isInsertOverwriteAllowed()`（Task 2）。
- Produces: `SqlInsertOverwrite extends SqlInsert`，构造器 `(SqlParserPos, SqlNodeList keywords, SqlNode targetTable, SqlNode source, SqlNodeList columnList)`，`getKind()==SqlKind.INSERT`，`public boolean isOverwrite()` 恒 true。解析方法名 `SqlMaskInsertOverwrite()`（config.fmpp `statementParserMethods` 按此名字符串挂载，Task 6/7 消费节点类型）。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.parser;

import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsertOverwriteTest {

  private static SqlParser parser(boolean allowOverwrite) {
    return SqlParser.create("",
        SqlParser.config()
            .withParserFactory(SqlMaskParserImpl.FACTORY)
            .withConformance(SqlMaskConformance.of(
                org.apache.calcite.sql.validate.SqlConformanceEnum.BABEL, false, allowOverwrite)));
  }

  private SqlNode parse(String sql, boolean allowOverwrite) throws SqlParseException {
    return parser(allowOverwrite).createParser(sql).parseStmt();
  }

  @Test
  void parsesOverwriteWithTableKeyword() throws SqlParseException {
    SqlNode node = parse("INSERT OVERWRITE TABLE t SELECT id FROM t", true);
    assertEquals(SqlKind.INSERT, node.getKind());
    assertTrue(node instanceof SqlInsertOverwrite);
  }

  @Test
  void parsesOverwriteWithoutTableKeyword() throws SqlParseException {
    SqlNode node = parse("INSERT OVERWRITE t SELECT id FROM t", true);
    assertTrue(node instanceof SqlInsertOverwrite);
  }

  @Test
  void parsesOverwriteWithColumnList() throws SqlParseException {
    SqlNode node = parse("INSERT OVERWRITE TABLE t (a, b) SELECT 1, 2", true);
    assertTrue(node instanceof SqlInsertOverwrite);
  }

  @Test
  void rejectsPartitionClause() {
    SqlParseException e = assertThrows(SqlParseException.class,
        () -> parse("INSERT OVERWRITE TABLE t PARTITION (ds = '1') SELECT id FROM t", true));
    assertTrue(e.getMessage().contains("PARTITION"), () -> e.getMessage());
  }

  @Test
  void rejectsDirectoryTarget() {
    SqlParseException e = assertThrows(SqlParseException.class,
        () -> parse("INSERT OVERWRITE DIRECTORY '/x' SELECT id FROM t", true));
    assertTrue(e.getMessage().contains("DIRECTORY"), () -> e.getMessage());
  }

  @Test
  void failsWithClearMessageWhenDialectDisallows() {
    SqlParseException e = assertThrows(SqlParseException.class,
        () -> parse("INSERT OVERWRITE TABLE t SELECT id FROM t", false));
    assertTrue(e.getMessage().contains("not enabled"), () -> e.getMessage());
  }

  @Test
  void plainInsertIntoStillWorks() throws SqlParseException {
    assertEquals(SqlKind.INSERT, parse("INSERT INTO t SELECT id FROM t", true).getKind());
  }
}
```

实现注：`SqlParser.createParser(sql)` 是 1.42 从配置创建解析器的 API；若编译报无此方法，改用 `SqlParser.create(sql, config)` 形态（两种等价，以编译为准，7 个用例的断言不变）。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-sqlparser test -Dtest=InsertOverwriteTest`
Expected: FAIL/ERROR（OVERWRITE 不可解析或 SqlInsertOverwrite 不存在）。

- [ ] **Step 3: 实现——config.fmpp 覆盖块加键**

`data: { parser: { ... } }` 覆盖块改为（新增 5 键）：

```
  parser: {
    package: "io.sqlmask.parser",
    class: "SqlMaskParserImpl",
    implementationFiles: [
      "parserImpls.ftl",
      "maskParserImpls.ftl"
    ],
    keywords: [
      "OVERWRITE"
    ],
    nonReservedKeywordsToAdd: [
      "OVERWRITE"
    ],
    imports: [
      "io.sqlmask.parser.SqlInsertOverwrite",
      "io.sqlmask.parser.SqlMaskConformance"
    ],
    statementParserMethods: [
      "SqlMaskInsertOverwrite()"
    ]
  }
```

依据：标准关键字硬编码在 Parser.jj TOKEN 区（`| < INSERT: "INSERT" >` 等，模板 8647/8896 行），`parser.keywords` 是纯增量键（模板 9097-9098 行 "additional parser keywords are included here"）；`nonReservedKeywordsToAdd` 与默认非保留表拼接（模板 9137 行）。`statementParserMethods` 挂到 `SqlStmt()` 首批备选（模板 1170 行，自带 `LOOKAHEAD(2)`，先于 1762 行 `SqlInsert()` 匹配）。

- [ ] **Step 4: 实现——maskParserImpls.ftl**

```ftl
<#-- mask 扩展产生式（spec §4.3）；由 config.fmpp implementationFiles 挂载 -->
SqlNode SqlMaskInsertOverwrite() :
{
    final SqlNodeList keywordList = new SqlNodeList(getPos());
    final SqlIdentifier tableName;
    SqlNode source;
    SqlNodeList columnList;
    final Pair<SqlNodeList, SqlNodeList> p;
    final Span s;
}
{
    <INSERT> { s = span(); }
    <OVERWRITE>
    {
        if (!(this.conformance instanceof SqlMaskConformance)
            || !((SqlMaskConformance) this.conformance).isInsertOverwriteAllowed()) {
            throw new ParseException("INSERT OVERWRITE is not enabled for this dialect");
        }
    }
    [ <TABLE> ]
    tableName = CompoundTableIdentifier()
    {
        if (getToken(1).kind == PARTITION) {
            throw new ParseException(
                "INSERT OVERWRITE ... PARTITION clause is not supported");
        }
        if (getToken(1).kind == IDENTIFIER && "DIRECTORY".equalsIgnoreCase(getToken(1).image)) {
            throw new ParseException("INSERT OVERWRITE DIRECTORY is not supported");
        }
    }
    (
        LOOKAHEAD(2)
        p = ParenthesizedCompoundIdentifierList()
        {
            columnList = p.left.isEmpty() ? null : p.left;
        }
    |   { columnList = null; }
    )
    source = OrderedQueryOrExpr(ExprContext.ACCEPT_QUERY)
    {
        return new SqlInsertOverwrite(s.end(source), keywordList, tableName, source,
            columnList);
    }
}
```

（`CompoundTableIdentifier`、`ParenthesizedCompoundIdentifierList`、`OrderedQueryOrExpr` 均为基语法既有产生式，SqlInsert() 同款用法；`PARTITION` 是 Calcite 既有关键字 token；`ParseException` 由 javacc 生成于同包，含 String 构造器。）

- [ ] **Step 5: 实现——SqlInsertOverwrite.java**

```java
package io.sqlmask.parser;

import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

/**
 * INSERT OVERWRITE [TABLE] t …：SqlInsert 子类，仅携带 overwrite 语义；
 * kind 保持 {@link SqlKind#INSERT}，校验/血缘/直通判断按普通 INSERT 走（spec §4.3）。
 */
public class SqlInsertOverwrite extends SqlInsert {

  private static final SqlOperator OPERATOR =
      new SqlSpecialOperator("INSERT_OVERWRITE", SqlKind.INSERT);

  public SqlInsertOverwrite(SqlParserPos pos, SqlNodeList keywords, SqlNode targetTable,
      SqlNode source, SqlNodeList columnList) {
    super(pos, keywords, targetTable, source, columnList);
  }

  @Override public SqlOperator getOperator() {
    return OPERATOR;
  }

  public boolean isOverwrite() {
    return true;
  }

  @Override public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
    final SqlWriter.Frame frame = writer.startList(SqlWriter.FrameTypeEnum.SELECT);
    writer.sep("INSERT OVERWRITE TABLE");
    final int opLeft = getOperator().getLeftPrec();
    final int opRight = getOperator().getRightPrec();
    getTargetTable().unparse(writer, opLeft, opRight);
    if (getTargetColumnList() != null) {
      getTargetColumnList().unparse(writer, opLeft, opRight);
    }
    writer.newlineAndIndent();
    getSource().unparse(writer, 0, 0);
    writer.endList(frame);
  }
}
```

（unparse 主体复制自 SqlInsert.unparse 1.42 源码，仅把 `writer.sep(isUpsert() ? "UPSERT INTO" : "INSERT INTO")` 换为 `INSERT OVERWRITE TABLE`；字段经 getter 访问。若 SqlInsert 的 getter 名不同以编译为准。）

- [ ] **Step 6: 重新生成并跑测试**

Run: `mvn -pl mask-sqlparser install && mvn -pl mask-sqlparser test -Dtest=InsertOverwriteTest`
Expected: PASS（7 个用例）。若 javacc 报 choice 冲突（SqlStmt 的 LOOKAHEAD(2) 与 SqlInsert 竞争）：把 hook 的展开产物 `target/generated-sources/fmpp/Parser.jj` 中 `stmt = SqlMaskInsertOverwrite()` 一行的 LOOKAHEAD 调为 3 不可行（模板固化 LOOKAHEAD(2)），改为在 ftl 产生式首行加 `LOOKAHEAD(<INSERT> <OVERWRITE>)` 前缀——保持语义等价，EXTENSIONS.md 记录。

- [ ] **Step 7: 回归冒烟**

Run: `mvn -pl mask-sqlparser test`
Expected: 全部 PASS（含 Task 1 的 3 个冒烟用例——证明加键后 baseline 未破坏）。

- [ ] **Step 8: Commit**

```bash
git add mask-sqlparser
git commit -m "feat(sqlparser): INSERT OVERWRITE 语法扩展（statementParserMethods 挂载 + SqlInsertOverwrite 节点）"
```

---

### Task 4: TOP 语法扩展（SqlSelect 挂点 + 冲突守卫）

**Files:**
- Modify: `mask-sqlparser/src/main/codegen/config.fmpp`（keywords、nonReservedKeywordsToAdd 各加 `"TOP"`）
- Modify: `mask-sqlparser/src/main/codegen/templates/Parser.jj`（两处 patch，见 EXTENSIONS.md）
- Modify: `mask-sqlparser/src/main/codegen/includes/maskParserImpls.ftl`（加 SqlMaskTopN()）
- Test: `mask-sqlparser/src/test/java/io/sqlmask/parser/TopNTest.java`

**Interfaces:**
- Consumes: `SqlMaskConformance.isTopNAllowed()`（Task 2）。
- Produces: `SELECT TOP …` 解析为 `SqlSelect.fetch`（`((SqlSelect) node).getFetch() != null`）；`SqlMaskTopN()` 产生式（仅语法内部使用）。Task 6/7 消费 fetch 断言。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.parser;

import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopNTest {

  private static SqlNode parse(String sql) throws SqlParseException {
    return SqlParser.create(sql,
        SqlParser.config()
            .withParserFactory(SqlMaskParserImpl.FACTORY)
            .withConformance(SqlMaskConformance.of(SqlConformanceEnum.BABEL, true, false)))
        .parseStmt();
  }

  @Test
  void parsesTopWithoutParenthesesIntoFetch() throws SqlParseException {
    SqlNode node = parse("SELECT TOP 10 id FROM t");
    assertEquals(SqlKind.SELECT, node.getKind());
    assertNotNull(((SqlSelect) node).getFetch());
  }

  @Test
  void parsesParenthesizedTopIntoFetch() throws SqlParseException {
    assertNotNull(((SqlSelect) parse("SELECT TOP (10) id FROM t")).getFetch());
  }

  @Test
  void topCoexistsWithOrderBy() throws SqlParseException {
    assertEquals(SqlKind.ORDER_BY, parse("SELECT TOP 3 id FROM t ORDER BY id").getKind());
  }

  @Test
  void rejectsPercent() {
    SqlParseException e = assertThrows(SqlParseException.class,
        () -> parse("SELECT TOP 10 PERCENT id FROM t"));
    assertTrue(e.getMessage().contains("PERCENT"), () -> e.getMessage());
  }

  @Test
  void rejectsWithTies() {
    SqlParseException e = assertThrows(SqlParseException.class,
        () -> parse("SELECT TOP (10) WITH TIES id FROM t ORDER BY id"));
    assertTrue(e.getMessage().contains("WITH TIES"), () -> e.getMessage());
  }

  @Test
  void rejectsTopCombinedWithLimit() {
    SqlParseException e = assertThrows(SqlParseException.class,
        () -> parse("SELECT TOP 5 id FROM t LIMIT 2"));
    assertTrue(e.getMessage().contains("TOP"), () -> e.getMessage());
  }

  @Test
  void failsWithClearMessageWhenDialectDisallows() {
    SqlParseException e = assertThrows(SqlParseException.class,
        () -> SqlParser.create("SELECT TOP 10 id FROM t",
            SqlParser.config()
                .withParserFactory(SqlMaskParserImpl.FACTORY)
                .withConformance(SqlMaskConformance.of(SqlConformanceEnum.BABEL, false, false)))
            .parseStmt());
    assertTrue(e.getMessage().contains("not enabled"), () -> e.getMessage());
  }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl mask-sqlparser test -Dtest=TopNTest`
Expected: FAIL/ERROR（TOP 不可解析）。

- [ ] **Step 3: config.fmpp 加 TOP**

`keywords: ["TOP", "OVERWRITE"]`、`nonReservedKeywordsToAdd: ["TOP", "OVERWRITE"]`。

- [ ] **Step 4: Parser.jj 两处 patch（重放点写入 EXTENSIONS.md）**

patch 1——`SqlSelect()` 产生式（vendor 基准约 1363-1431 行），三处小改：

```diff
     final SqlNodeList by;
     final List<SqlNode> hints = new ArrayList<SqlNode>();
+    SqlNode topFetch = null;
     final Span s;
```

```diff
     {
         keywordList = new SqlNodeList(keywords, s.addAll(keywords).pos());
     }
+    [
+        LOOKAHEAD( { getToken(1).kind == TOP
+            && (getToken(2).kind == LPAREN
+                || getToken(2).kind == UNSIGNED_INTEGER_LITERAL) } )
+        topFetch = SqlMaskTopN()
+    ]
     AddSelectItem(selectList)
```

```diff
             fromClause, where, groupBy, having, windowDecls, qualify,
-            null, null, null, new SqlNodeList(hints, getPos()));
+            null, null, topFetch, new SqlNodeList(hints, getPos()));
```

（构造器参数序 `…qualify, orderBy, offset, fetch, hints` 已从 SqlSelect 1.42 源码 63-75 行核实；第三个 null 是 fetch。语义前瞻保证 `SELECT top FROM t`（token(2)=FROM）不进 TOP 分支——top 作列名不受影响。TOP 放在 AllOrDistinct 之后、投影列之前，与 T-SQL `SELECT [DISTINCT] TOP (n)` 语序一致。）

patch 2——`OrderByLimitOpt()` 产生式（vendor 基准约 741 行）末尾 action 块首加冲突守卫（尾部 LIMIT/OFFSET/FETCH 会包装成 SqlOrderBy，不拦则静默覆盖 TOP 的 fetch）：

```diff
     {
+        if (e instanceof SqlSelect
+            && ((SqlSelect) e).getFetch() != null
+            && (offsetFetch[0] != null || offsetFetch[1] != null)) {
+            throw new ParseException(
+                "TOP cannot be combined with OFFSET/LIMIT/FETCH");
+        }
         if (orderBy != null || offsetFetch[0] != null || offsetFetch[1] != null) {
```

- [ ] **Step 5: maskParserImpls.ftl 加 SqlMaskTopN()**

```ftl
<#-- SELECT TOP (n)：映射进 SqlSelect.fetch（spec §4.2），由 Parser.jj SqlSelect() 挂点调用 -->
SqlNode SqlMaskTopN() :
{
    final SqlNode expr;
    final Span s;
}
{
    <TOP> { s = span(); }
    (
        LOOKAHEAD(2) <LPAREN> expr = Expression(ExprContext.ACCEPT_SUB_QUERY) <RPAREN>
    |   expr = DecimalLiteral()
    )
    {
        if (!(this.conformance instanceof SqlMaskConformance)
            || !((SqlMaskConformance) this.conformance).isTopNAllowed()) {
            throw new ParseException("TOP is not enabled for this dialect");
        }
        if (getToken(1).kind == IDENTIFIER && "PERCENT".equalsIgnoreCase(getToken(1).image)) {
            throw new ParseException("TOP ... PERCENT is not supported");
        }
        if (getToken(1).kind == WITH && getToken(2).kind == IDENTIFIER
            && "TIES".equalsIgnoreCase(getToken(2).image)) {
            throw new ParseException("TOP ... WITH TIES is not supported");
        }
        return expr;
    }
}
```

（`Expression(ExprContext.ACCEPT_SUB_QUERY)` / `DecimalLiteral()` 为基语法既有产生式；若 `Expression` 的形参形态与 1.42 模板不同，grep 模板既有调用点照抄。）

- [ ] **Step 6: 重新生成并跑测试**

Run: `mvn -pl mask-sqlparser install && mvn -pl mask-sqlparser test -Dtest=TopNTest`
Expected: PASS（7 个用例）。若 javacc 报 LOOKAHEAD 歧义警告导致失败：把 patch 1 语义前瞻中 `getToken(2)` 的判断保持不变，检查 `<TOP>` token 是否真在生成产物 TOKEN 区（config 键拼写），不得用放宽 LOOKAHEAD 的方式掩盖。

- [ ] **Step 7: Commit**

```bash
git add mask-sqlparser
git commit -m "feat(sqlparser): SELECT TOP (n) 语法扩展（fetch 映射 + LIMIT/OFFSET 冲突守卫）"
```

---

### Task 5: 标识符兼容回归 + Babel 差分语料验证

**Files:**
- Test: `mask-sqlparser/src/test/java/io/sqlmask/parser/IdentifierRegressionTest.java`
- Test: `mask-sqlparser/src/test/java/io/sqlmask/parser/BabelEquivalenceTest.java`
- Create: `mask-sqlparser/src/test/resources/mask-parser-corpus.sql`

**Interfaces:**
- Consumes: `SqlMaskParserImpl.FACTORY`（Task 1）、`SqlMaskConformance.of`（Task 2）。
- Produces: 差分验证框架（spec §7.2）；corpus 文件后续可继续追加。

- [ ] **Step 1: 写标识符回归测试（先跑——预期 Task 3/4 已使前 3 个用例可过，第 4 个钉死边界）**

```java
package io.sqlmask.parser;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdentifierRegressionTest {

  private static SqlNode parse(String sql) throws SqlParseException {
    return SqlParser.create(sql,
        SqlParser.config()
            .withParserFactory(SqlMaskParserImpl.FACTORY)
            .withConformance(SqlMaskConformance.of(SqlConformanceEnum.BABEL, false, false)))
        .parseStmt();
  }

  @Test
  void topUsableAsColumnName() {
    assertDoesNotThrow(() -> parse("SELECT top FROM t"));
    assertDoesNotThrow(() -> parse("SELECT t.top FROM t"));
    assertDoesNotThrow(() -> parse("SELECT id AS top FROM t"));
  }

  @Test
  void overwriteUsableAsColumnName() {
    assertDoesNotThrow(() -> parse("SELECT overwrite FROM t"));
    assertDoesNotThrow(() -> parse("SELECT id AS overwrite FROM t"));
  }

  @Test
  void topUsableAsTableName() {
    assertDoesNotThrow(() -> parse("SELECT id FROM top"));
  }

  @Test
  void topAsFunctionCallFailsClosed() {
    // 已知边界（spec §4.2）：名为 top 的函数调用会被识别为 TOP 子句 → 解析失败，
    // 语义上 fail-closed（错误而非错误掩码），钉死行为防止未来悄悄改变。
    assertThrows(SqlParseException.class, () -> parse("SELECT top(1) FROM t"));
  }
}
```

Run: `mvn -pl mask-sqlparser test -Dtest=IdentifierRegressionTest`
Expected: PASS（若 `top` 作列名失败，说明 LOOKAHEAD 语义前瞻或 nonReservedKeywordsToAdd 接线有误，回 Task 4 修，不得放宽断言）。

- [ ] **Step 2: 写差分语料文件**

创建 `mask-sqlparser/src/test/resources/mask-parser-corpus.sql`，每行一条语句、不写分号、`#` 开头为注释（内容 = 常见方言中立语句 25 条，覆盖 SELECT 各子句、JOIN、CTE、子查询、聚合、INSERT INTO、CTAS、以及探测实验中已支持的 PIVOT / CROSS APPLY / ROWNUM / `::` / RLIKE / `LIMIT n,m` / LEFT SEMI JOIN / CONVERT 等，例）：

```sql
# baseline select forms
SELECT id FROM t
SELECT DISTINCT id, name FROM t WHERE id > 1
SELECT id FROM t ORDER BY id DESC
SELECT id FROM t LIMIT 10
SELECT id FROM t LIMIT 10 OFFSET 5
SELECT id FROM t FETCH FIRST 5 ROWS ONLY
SELECT a.id, b.name FROM a JOIN b ON a.id = b.id
SELECT id FROM (SELECT id FROM t) AS x
SELECT id FROM t WHERE id IN (SELECT id FROM t2)
SELECT id, count(*) AS c FROM t GROUP BY id HAVING count(*) > 1
# cte
WITH x AS (SELECT id FROM t) SELECT id FROM x
# writes
INSERT INTO t SELECT id FROM t
INSERT INTO t (a, b) SELECT 1, 2
CREATE TABLE t2 AS SELECT id FROM t
# calcite-native dialect forms (probe-verified 2026-09-17)
SELECT * FROM (SELECT id, x FROM t) PIVOT (count(id) FOR x IN (1, 2))
SELECT id FROM t CROSS APPLY (SELECT id2 FROM t2) s
SELECT ROWNUM FROM t
SELECT id::int FROM t
SELECT id FROM t WHERE a RLIKE 'x'
SELECT id FROM t ORDER BY id LIMIT 1, 2
SELECT id FROM t LEFT SEMI JOIN t2 ON t.id = t2.id
SELECT CONVERT(int, id) FROM t
SELECT SQL_NO_CACHE id FROM t
SELECT id FROM t WHERE a <=> b
SELECT id, count(*) FROM t GROUP BY 2
```

- [ ] **Step 3: 写差分测试**

```java
package io.sqlmask.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

class BabelEquivalenceTest {

  private static SqlParser.Config babel() {
    return SqlParser.config()
        .withParserFactory(org.apache.calcite.sql.parser.babel.SqlBabelParserImpl.FACTORY)
        .withConformance(SqlConformanceEnum.BABEL);
  }

  private static SqlParser.Config ours() {
    return SqlParser.config()
        .withParserFactory(SqlMaskParserImpl.FACTORY)
        .withConformance(SqlMaskConformance.of(SqlConformanceEnum.BABEL, false, false));
  }

  private static List<String> corpus() throws IOException {
    List<String> lines = new ArrayList<>(java.nio.file.Files.readAllLines(
        java.nio.file.Path.of("src/test/resources/mask-parser-corpus.sql"),
        StandardCharsets.UTF_8));
    // golden 改写语料：mask-core golden 目录多语句文件，按分号切（spec §7.2）
    java.nio.file.Path golden = java.nio.file.Path.of(
        "../mask-core/src/test/resources/golden/tpcds-common.sql");
    if (java.nio.file.Files.exists(golden)) {
      for (String stmt : java.nio.file.Files.readString(golden, StandardCharsets.UTF_8)
          .split(";\\s*\\n")) {
        if (!stmt.isBlank()) {
          lines.add(stmt.replace('\n', ' ').trim());
        }
      }
    }
    return lines.stream()
        .map(String::trim)
        .filter(s -> !s.isEmpty() && !s.startsWith("#"))
        .toList();
  }

  @Test
  void newParserMatchesBabelWhenFlagsClosed() throws IOException {
    for (String sql : corpus()) {
      try {
        SqlNode base = SqlParser.create(sql, babel()).parseStmt();
        SqlNode masked = SqlParser.create(sql, ours()).parseStmt();
        assertEquals(base.getKind(), masked.getKind(), () -> "kind differs: " + sql);
        assertEquals(base.toString(), masked.toString(),
            () -> "unparse differs: " + sql);
      } catch (SqlParseException e) {
        fail("both parsers must accept corpus statement: " + sql + " -> " + e.getMessage());
      }
    }
    assertNotNull(corpus());
  }
}
```

Run（在 mask-sqlparser 目录）：`mvn -pl mask-sqlparser test -Dtest=BabelEquivalenceTest`
Expected: PASS。差分失败（kind 或 unparse 不一致）= fork 走样，必须定位到具体语句修复，禁止删语料凑绿。

- [ ] **Step 4: Commit**

```bash
git add mask-sqlparser
git commit -m "test(sqlparser): 标识符兼容回归 + Babel 差分语料验证（flags 关闭等价）"
```

---

### Task 6: mask-core 三方言切换 + composeWriteStatement OVERWRITE 分支

**Files:**
- Modify: `mask-core/pom.xml`（加 `mask-sqlparser` 依赖，groupId/version 抄父 pom）
- Modify: `mask-core/src/main/java/io/sqlmask/dialect/MysqlDialectAdapter.java`（parserConfig 换 factory + 包装 conformance）
- Modify: `mask-core/src/main/java/io/sqlmask/dialect/PostgresqlDialectAdapter.java`（同上）
- Modify: `mask-core/src/main/java/io/sqlmask/dialect/TrinoDialectAdapter.java`（同上）
- Modify: `mask-core/src/main/java/io/sqlmask/dialect/AbstractCalciteDialectAdapter.java:193-203`（composeWriteStatement INSERT 分支）
- Test: `mask-core/src/test/java/io/sqlmask/dialect/MaskParserProfileTest.java`

**Interfaces:**
- Consumes: `io.sqlmask.parser.SqlMaskParserImpl.FACTORY`、`io.sqlmask.parser.SqlMaskConformance.of(...)`、`io.sqlmask.parser.SqlInsertOverwrite`（Task 1-4）。
- Produces: 三方言 `DialectProfile.parserConfig` 均为 `SqlMaskParserImpl.FACTORY + SqlMaskConformance.of(原 enum, false, false)`；`composeWriteStatement` 对 `SqlInsertOverwrite` 输出 `INSERT OVERWRITE TABLE …`。`validatorConformance` 保持原 enum 不变（spec §4.5）。

- [ ] **Step 1: 写失败测试**

```java
package io.sqlmask.dialect;

import io.sqlmask.parser.SqlMaskConformance;
import io.sqlmask.parser.SqlInsertOverwrite;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlParser;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.parser.SqlParserImplFactory;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskParserProfileTest {

  /** 开关全开的测试专用 profile（生产三方言开关全关，spec §5）。 */
  private static final DialectProfile OPEN_PROFILE = new DialectProfile(
      "mysql-open",
      SqlParser.config()
          .withParserFactory((SqlParserImplFactory) io.sqlmask.parser.SqlMaskParserImpl.FACTORY)
          .withQuoting(org.apache.calcite.avatica.util.Quoting.BACK_TICK)
          .withUnquotedCasing(org.apache.calcite.config.Casing.UNCHANGED)
          .withQuotedCasing(org.apache.calcite.config.Casing.UNCHANGED)
          .withCaseSensitive(false)
          .withConformance(SqlMaskConformance.of(SqlConformanceEnum.MYSQL_5, true, true)),
      SqlConformanceEnum.MYSQL_5,
      false,
      MysqlFunctions.TABLE,
      new MysqlTypeResolver(),
      new MysqlUnparseDialect(),
      new MysqlIdentifierPolicy(),
      DialectProfile.SchemaPathStyle.CATALOG_SCHEMA_AND_SCHEMA,
      new DialectCapabilities(false));

  private final AbstractCalciteDialectAdapter openAdapter =
      new AbstractCalciteDialectAdapter(OPEN_PROFILE) {};

  @Test
  void insertOverwriteClassifiesAsInsertAndComposesWithOverwriteHeader()
      throws SqlParseException {
    SqlNode node = openAdapter.parse(
        "INSERT OVERWRITE TABLE `orders` SELECT `id`, `memo` FROM `orders`", 0);
    assertTrue(node instanceof SqlInsertOverwrite);
    assertEquals("INSERT OVERWRITE TABLE `orders` (SELECT `id`, `memo` FROM `orders`)",
        openAdapter.composeWriteStatement(node,
            "(SELECT `id`, `memo` FROM `orders`)"));
  }

  @Test
  void topParsesAndSetsFetch() throws SqlParseException {
    SqlNode node = openAdapter.parse("SELECT TOP (3) `id` FROM `orders`", 0);
    assertNotNull(((SqlSelect) node).getFetch());
  }

  @Test
  void productionMysqlProfileRejectsBothExtensions() {
    MysqlDialectAdapter adapter = new MysqlDialectAdapter();
    SqlMaskException e1 = assertThrows(SqlMaskException.class,
        () -> adapter.parse("INSERT OVERWRITE TABLE `orders` SELECT 1", 0));
    assertEquals(SqlMaskException.Code.PARSE_ERROR, e1.getCode());
    assertTrue(e1.getMessage().contains("not enabled"), () -> e1.getMessage());
    assertThrows(SqlMaskException.class, () -> adapter.parse("SELECT TOP 10 `id` FROM `orders`", 0));
  }
}
```

实现注：`AbstractCalciteDialectAdapter` 构造器为 protected，匿名子类需同包（测试在 `io.sqlmask.dialect` 包内 ✓）。`DialectProfile` 构造参数序以现有 `MysqlDialectAdapter` 实际代码为准逐项对齐（name/parserConfig/validatorConformance/caseSensitiveNameMatching/functionTable/typeResolver/unparseDialect/identifierPolicy/schemaPathStyle/capabilities——以源码为准）。若 adapter 的 `parse` 把底层 `ParseException` 包装成 `SqlMaskException(PARSE_ERROR)`（AbstractCalciteDialectAdapter.java:52-56 现有逻辑 ✓），第二个断言直接生效。

Run: `mvn -pl mask-sqlparser,mask-core -am test -Dtest=MaskParserProfileTest`
Expected: FAIL（mask-core 未依赖 mask-sqlparser，编译错误）。

- [ ] **Step 2: mask-core 加依赖**

`mask-core/pom.xml` `<dependencies>` 加：

```xml
<dependency>
  <groupId>io.sqlmask</groupId>
  <artifactId>mask-sqlparser</artifactId>
  <version>${project.version}</version>
</dependency>
```

- [ ] **Step 3: 三个 adapter 的 parserConfig 换新解析器**

以 MysqlDialectAdapter 为例（其余两个同型，保留各自 quoting/casing/conformance 原值）：

```java
import io.sqlmask.parser.SqlMaskConformance;
import io.sqlmask.parser.SqlMaskParserImpl;
...
        SqlParser.config()
            .withParserFactory(SqlMaskParserImpl.FACTORY)          // 原 SqlBabelParserImpl.FACTORY
            .withQuoting(Quoting.BACK_TICK)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(false)
            .withConformance(SqlMaskConformance.of(SqlConformanceEnum.MYSQL_5, false, false)),
        SqlConformanceEnum.MYSQL_5,                                 // validatorConformance 不变
```

- [ ] **Step 4: composeWriteStatement 加 OVERWRITE 分支**

`AbstractCalciteDialectAdapter.java` INSERT case（现 198 行 `StringBuilder sql = new StringBuilder("INSERT INTO ");`）改为：

```java
        StringBuilder sql = new StringBuilder(
            insert instanceof io.sqlmask.parser.SqlInsertOverwrite
                ? "INSERT OVERWRITE TABLE " : "INSERT INTO ");
```

- [ ] **Step 5: 跑测试**

Run: `mvn -pl mask-sqlparser,mask-core -am test -Dtest=MaskParserProfileTest`
Expected: PASS（3 个用例）。

- [ ] **Step 6: 全量回归（fork 未走样的主证据）**

Run: `mvn -pl mask-sqlparser,mask-core -am test`
Expected: mask-core 全部现有测试 PASS。任何失败按「三方言切换引入」定位（对比切换前同一测试），不得改断言迁就。

- [ ] **Step 7: Commit**

```bash
git add mask-core pom.xml
git commit -m "feat(mask-core): 三方言切换 SqlMaskParserImpl（扩展开关全关），compose 支持 INSERT OVERWRITE 头"
```

---

### Task 7: 端到端管线验证 + 文档收尾

**Files:**
- Test: `mask-core/src/test/java/io/sqlmask/dialect/MaskParserPipelineTest.java`
- Modify: `mask-sqlparser/EXTENSIONS.md`（回填 diff 清单）
- Modify: `README.md`（方言支持节：新解析器与拒绝清单一句话说明）
- Modify: `docs/superpowers/specs/2026-09-17-custom-parser-design.md`（§11 状态回写，仿 multi-dialect spec §10 格式）

**Interfaces:**
- Consumes: Task 6 的三方言与 `composeWriteStatement` 行为、`YamlCalciteSchemaFactory`（现有测试基建）。
- Produces: spec §11.1-§11.5 全部闭环的证据。

- [ ] **Step 1: 写端到端测试（镜像 MysqlDialectAdapterTest 的 YAML+schema 模式）**

```java
package io.sqlmask.dialect;

import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import io.sqlmask.sql.ValidatedSql;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskParserPipelineTest {

  private static final String YAML = """
      metadata:
        tables:
          - catalog: shop
            schema: app
            name: orders
            columns:
              - name: id
                type: bigint
              - name: memo
                type: text
      policies: {}
      """;

  private AbstractCalciteDialectAdapter adapter;
  private SchemaPlus schema;

  @BeforeEach
  void setUp() {
    // 开关全开 + MYSQL_5 语法的测试 profile；构造方式与 MaskParserProfileTest.OPEN_PROFILE 一致
    adapter = MaskParserProfileTest.openAdapter();
    schema = YamlCalciteSchemaFactory.create(
        new YamlConfigLoader().loadContent(YAML, "pipeline-test.yaml", "mysql"));
  }

  @Test
  void insertOverwriteFlowsThroughValidateAndComposes() throws SqlParseException {
    SqlNode node = adapter.parse(
        "INSERT OVERWRITE TABLE `orders` SELECT `id`, `memo` FROM `orders`", 0);
    ValidatedSql validated = adapter.validate(node, schema);   // spec §11.3：子类过校验/转换
    assertEquals(SqlKind.INSERT, validated.parsed().getKind());
    assertEquals(SqlKind.SELECT, adapter.querySourceOf(node).getKind());
    String composed = adapter.composeWriteStatement(node,
        "(SELECT `id`, mask(`memo`) AS `memo` FROM `orders`)");
    assertTrue(composed.startsWith("INSERT OVERWRITE TABLE `orders`"), () -> composed);
    // round-trip：重组产物可被本解析器再次解析
    assertNotNull(adapter.parse(composed, 0));
  }

  @Test
  void topSelectValidatesWithFetchIntact() throws SqlParseException {
    SqlNode node = adapter.parse("SELECT TOP (5) `id` FROM `orders`", 0);
    ValidatedSql validated = adapter.validate(node, schema);
    assertEquals(java.util.List.of("id"), validated.rowType().getFieldNames());
  }

  @Test
  void rejectedVariantsFailClosedWithClearMessages() {
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("INSERT OVERWRITE TABLE `orders` PARTITION (x = 1) SELECT 1", 0));
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("SELECT TOP 5 PERCENT `id` FROM `orders`", 0));
    assertThrows(SqlMaskException.class,
        () -> adapter.parse("SELECT TOP 5 `id` FROM `orders` LIMIT 3", 0));
  }
}
```

实现注：`MaskParserProfileTest.openAdapter()` 是 Step 1（Task 6）测试类补的一个静态辅助——把 OPEN_PROFILE + 匿名 adapter 提为 `static AbstractCalciteDialectAdapter openAdapter()`；`ValidatedSql` 的访问器名以 `io.sqlmask.sql.ValidatedSql` 实际字段为准（`parsed()/rowType()` 在现有测试中出现过，编译为准）。校验失败若暴露 SqlInsert 子类分派问题（spec §11.3 风险），按 spec 回退方案处理：validate 输入树中替换为普通 `SqlInsert`、compose 前回填标志——实现为 `AbstractCalciteDialectAdapter.validate` 入口的 `SqlInsertOverwrite → SqlInsert` 临时替换并记录，EXTENSIONS.md 写明。

Run: `mvn -pl mask-sqlparser,mask-core -am test -Dtest=MaskParserPipelineTest`
Expected: PASS。

- [ ] **Step 2: 回填 EXTENSIONS.md diff 清单**

逐条列出：config.fmpp 覆盖键 7 处（package/class/implementationFiles/keywords/nonReservedKeywordsToAdd/imports/statementParserMethods）、`Parser.jj` 两处 patch（SqlSelect 挂点 + OrderByLimitOpt 守卫，含行号锚点）、`maskParserImpls.ftl` 两个产生式、Java 类 2 个（SqlMaskConformance/SqlInsertOverwrite）。

- [ ] **Step 3: 更新 README 与 spec 状态回写**

README「方言支持」节加一句：三方言解析已切至 mask-sqlparser 自定义解析器（Babel 等价 + 扩展开关全关），TOP/OVERWRITE 等扩展语法当前方言关闭、明确的拒绝清单见模块 EXTENSIONS.md 与 spec §4。spec §11 每项后追加「状态回写（2026-09-XX 实现完成后）」小节：FMPP 接法最终形态（config.fmpp 自包含嵌套）、插件版本定版、validator 分派结论、top(x) 边界钉死结论、差分语料收集方式。

- [ ] **Step 4: 全仓构建收尾**

Run: `mvn -pl mask-sqlparser,mask-core -am install`
Expected: BUILD SUCCESS（含两个模块全部测试）。

- [ ] **Step 5: Commit**

```bash
git add mask-core mask-sqlparser/EXTENSIONS.md README.md docs/superpowers/specs/2026-09-17-custom-parser-design.md
git commit -m "test: 解析器端到端管线验证；docs: EXTENSIONS diff 清单与 spec §11 状态回写"
```

---

## main 提交规程（每个 Task 的 Commit 步骤通用）

当前工作区检出在 `feature/audit-log-es`（并行会话活跃），**禁止**直接在该分支 commit。每个 Task 的提交改用 plumbing（不触碰工作区与并行会话的索引）：

```bash
cd "C:/Users/yhh/orca/mask"
git add <本 task 的文件>          # 仅暂存本 task 文件
TREE=$(git write-tree)           # 陷阱：write-tree 抓的是整个索引；add 前确认 git status
                                 # 只有本 task 文件被暂存（其余保持未暂存），否则用临时索引
git diff --stat main "$TREE"     # 必须只含本 task 文件！若出现其他文件立即停下排查
C=$(git commit-tree "$TREE" -p main -m "<commit message>")
git branch -f main "$C"
git restore --staged <本 task 的文件>   # 当前分支索引还原，文件留在工作区
```

若执行期间并行会话已把当前分支推进且工作区干净（`git status` 无其他改动、当前分支就是为本次工作新建的分支），则可退回普通 `git add + git commit + git branch -f main HEAD` 流程。每次提交后 `git diff --stat main~1 main` 必须只含本 task 文件。

## Self-Review 记录

- Spec coverage：§3 模块/构建→Task 1；§4.3 OVERWRITE→Task 3；§4.2 TOP→Task 4；§4.4 标识符→Task 5；§4.5/§5 开关与切换→Task 2/6；§6 错误→Task 3/4/7 拒绝用例；§7.1 单测→Task 3/4/5；§7.2 差分→Task 5（golden 按 `;` 切行实现，curated 语料补充）；§7.3 全量+e2e→Task 6/7；§8 升级→Task 1/7 EXTENSIONS.md；§11.1-11.5→Task 1/1/7/5/5。无缺口。
- 占位符扫描：无 TBD/TODO；「以实际源码为准」处均给出确定来源文件与回退动作，非占位。
- 类型一致性：`SqlMaskConformance.of/isTopNAllowed/isInsertOverwriteAllowed`、`SqlInsertOverwrite` 构造器与 `isOverwrite()`、`SqlMaskTopN()/SqlMaskInsertOverwrite()` 方法名在各 Task 间已逐一对照一致。
