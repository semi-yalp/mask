package io.sqlmask.rewrite;

import io.sqlmask.dialect.MysqlDialectAdapter;
import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        - catalog: shop
          schema: app
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

  @Test
  void trinoCteLineageAndShadowing() {
    // spec §10.2 item 7 (CteExpander 方言等价性): the CTE name shadows the base
    // table — the outer FROM customer must resolve to the CTE projection, where
    // p exists; a base-table resolution would fail validation with
    // "Column 'p' not found", so the assertions below pin both shadowing and
    // lineage through the expanded CTE
    String out = flat(engine.rewrite(TRINO_YAML,
        "WITH customer AS (SELECT phone AS p FROM crm.public.customer) SELECT p FROM customer",
        "trino").get(0).rewrittenSql());
    assertTrue(out.startsWith("SELECT mask_phone(r.p, 3, 4) AS p FROM ("), out);
    assertTrue(out.toUpperCase().contains("WITH CUSTOMER AS"), out);
  }

  @Test
  void trinoMultiSourcePolicyIsDeterministic() {
    // contact depends on email and phone; 'email' < 'phone' lexicographically
    // so mask_email wins regardless of YAML declaration order
    String out = flat(engine.rewrite(TRINO_YAML,
        "SELECT concat(email, '-', phone) AS contact FROM customer", "trino")
        .get(0).rewrittenSql());
    assertTrue(out.startsWith("SELECT mask_email(r.contact) AS contact FROM ("), out);
    assertFalse(out.contains("mask_phone"), out);
  }

  @Test
  void trinoNoPolicyStatementPassesThrough() {
    String sql = "SELECT id FROM customer";
    assertEquals(sql, flat(engine.rewrite(TRINO_YAML, sql, "trino").get(0).rewrittenSql()));
  }

  @Test
  void trinoInsertValuesPassesThrough() {
    String sql = "INSERT INTO crm.public.archive (id) VALUES (1)";
    assertEquals(sql, flat(engine.rewrite(TRINO_YAML, sql, "trino").get(0).rewrittenSql()));
  }

  @Test
  void trinoInsertValuesWithHiddenSubqueryFails() {
    // a scalar subquery inside VALUES reads columns we cannot trace
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> engine.rewrite(TRINO_YAML,
        "INSERT INTO crm.public.archive (id) VALUES ((SELECT id FROM customer))", "trino"));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode());
  }

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

  @Test
  void mysqlCteLineageAndShadowing() {
    // spec §10.2 item 7 (CteExpander 方言等价性): the CTE name shadows the base
    // table — the outer FROM customer must resolve to the CTE projection, where
    // p exists; a base-table resolution would fail validation with
    // "Column 'p' not found", so the assertions below pin both shadowing and
    // lineage through the expanded CTE. The CTE body itself references the base
    // table by qualified name (like the trino variant): an unqualified in-body
    // self-reference is refused by the expander (see the fail-closed loop test)
    String out = flat(engine.rewrite(MYSQL_YAML,
        "WITH customer AS (SELECT phone AS p FROM app.customer) SELECT p FROM customer",
        "mysql").get(0).rewrittenSql());
    assertTrue(out.startsWith("SELECT `mask_phone`(`r`.`p`, 3, 4) AS `p` FROM ("), out);
    assertTrue(out.toUpperCase().contains("WITH CUSTOMER AS"), out);
  }

  @Test
  void mysqlMultiSourcePolicyIsDeterministic() {
    // contact depends on email and phone; 'email' < 'phone' lexicographically
    // so mask_email wins regardless of YAML declaration order
    String out = flat(engine.rewrite(MYSQL_YAML,
        "SELECT concat(email, '-', phone) AS contact FROM customer", "mysql")
        .get(0).rewrittenSql());
    assertTrue(out.startsWith("SELECT `mask_email`(`r`.`contact`) AS `contact` FROM ("), out);
    assertFalse(out.contains("`mask_phone`"), out);
  }

  @Test
  void mysqlNoPolicyStatementPassesThrough() {
    String sql = "SELECT id FROM customer";
    assertEquals(sql, flat(engine.rewrite(MYSQL_YAML, sql, "mysql").get(0).rewrittenSql()));
  }

  @Test
  void mysqlInsertValuesPassesThrough() {
    String sql = "INSERT INTO app.archive (id) VALUES (1)";
    assertEquals(sql, flat(engine.rewrite(MYSQL_YAML, sql, "mysql").get(0).rewrittenSql()));
  }

  @Test
  void mysqlInsertValuesWithHiddenSubqueryFails() {
    // a scalar subquery inside VALUES reads columns we cannot trace
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> engine.rewrite(MYSQL_YAML,
        "INSERT INTO app.archive (id) VALUES ((SELECT id FROM customer))", "mysql"));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode());
  }

  @Test
  void mysqlOutputReparsesUnderMysqlConfig() throws Exception {
    // spec §6 item 5: MySQL 没有官方独立 parser，改写输出的每一句都必须通过
    // MySQL 配置的 Calcite round-trip 再解析兜底
    String metadata = Files.readString(
        Path.of("src/test/resources/metadata/mysql-integration.yaml"), StandardCharsets.UTF_8);
    String queries = Files.readString(
        Path.of("src/test/resources/queries/with-and-nested.sql"), StandardCharsets.UTF_8);
    List<RewriteEngine.StatementRewrite> statements = engine.rewrite(metadata, queries, "mysql");
    assertEquals(4, statements.size(), "fixture statements must all rewrite under mysql");
    MysqlDialectAdapter adapter = new MysqlDialectAdapter();
    for (RewriteEngine.StatementRewrite statement : statements) {
      adapter.parse(statement.rewrittenSql(), 0);
    }
    assertTrue(statements.get(0).rewrittenSql().startsWith("SELECT `r`.`id`"),
        statements.get(0).rewrittenSql());
  }

  @Test
  void mysqlStringArgumentsRenderPerDialectEscaping() {
    // spec §10.2 item 3（2026-09-06 实测钉定）：YAML 单引号写法 'a''b\c' 的值是
    // a'b\c；MysqlSqlDialect 渲染字符串字面量时只双写单引号、不双写反斜杠——
    // 在 MySQL 默认（反斜杠为转义符）模式下 '\c' 会被目标引擎还原成 c，实参值
    // 与配置出现偏差。这是 fail-open 的已知边界（README 安全失败/限制清单，
    // Task 11），此处钉死现状：完整输出逐字节锁定。
    String yaml = MYSQL_YAML.replace("arguments: [3, 4]", "arguments: ['a''b\\c', 3]");
    String out = flat(engine.rewrite(yaml, "SELECT phone FROM customer", "mysql")
        .get(0).rewrittenSql());
    assertEquals("SELECT `mask_phone`(`r`.`phone`, 'a''b\\c', 3) AS `phone` FROM ( "
        + "SELECT phone FROM customer ) AS `r`", out);
  }

  @Test
  void mysqlCtasWithColumnDefinitionsHasPinnedOutcome() throws Exception {
    // spec §10.2 item 4（2026-09-06 实测钉定，成功分支）：带列定义的 CTAS 能解析；
    // 重组时列定义被 Calcite 规范化为大写类型名（bigint→BIGINT、varchar(20)→
    // VARCHAR(20)），目标表按书写原样不加反引号渲染（quoteAllIdentifiers=false），
    // 重组结果仍是合法 MySQL——用 MySQL 配置的 Calcite round-trip 再解析兜底。
    String out = flat(engine.rewrite(MYSQL_YAML,
        "CREATE TABLE app.masked (id bigint, phone varchar(20)) AS SELECT id, phone FROM customer",
        "mysql").get(0).rewrittenSql());
    assertEquals("CREATE TABLE app.masked (id BIGINT, phone VARCHAR(20)) AS "
        + "SELECT `r`.`id`, `mask_phone`(`r`.`phone`, 3, 4) AS `phone` FROM ( "
        + "SELECT id, phone FROM customer ) AS `r`", out);
    new MysqlDialectAdapter().parse(out, 0);
  }

  @Test
  void mysqlBetweenFormsRenderFaithfully() throws Exception {
    // Calcite 的 SqlBetweenOperator 渲染时总是带 flag 关键字（getName() 返回
    // "BETWEEN ASYMMETRIC"），真实 MySQL 的 BETWEEN 后面没有 ASYMMETRIC——
    // MysqlUnparseDialect 按原义渲染 plain/NOT BETWEEN（丢掉 NOT 会静默反转
    // 谓词）；两种形式都必须能通过 MySQL 配置的 Calcite round-trip 再解析兜底
    String between = flat(engine.rewrite(MYSQL_YAML,
        "SELECT id FROM customer WHERE id BETWEEN 1 AND 5", "mysql").get(0).rewrittenSql());
    assertTrue(between.contains(" id BETWEEN 1 AND 5"), between);
    String notBetween = flat(engine.rewrite(MYSQL_YAML,
        "SELECT id FROM customer WHERE id NOT BETWEEN 1 AND 5", "mysql").get(0).rewrittenSql());
    assertTrue(notBetween.contains(" id NOT BETWEEN 1 AND 5"), notBetween);
    new MysqlDialectAdapter().parse(between, 0);
    new MysqlDialectAdapter().parse(notBetween, 0);
  }

  @Test
  void postgresqlCteNameShadowsBaseTableName() {
    // spec §10.2 item 7: PG 是 CteExpander 的参考语义——CTE 名遮蔽同名基础表，
    // 与 trino/mysql 用例共同钉死三方言的 CteExpander 等价性。crm.public 元数据
    // 形状对 postgresql 与 trino 两个 profile 均合法（同 CATALOG_SCHEMA 路径风格）
    String out = flat(engine.rewrite(TRINO_YAML,
        "WITH customer AS (SELECT phone AS p FROM crm.public.customer) SELECT p FROM customer",
        "postgresql").get(0).rewrittenSql());
    assertTrue(out.startsWith("SELECT mask_phone(r.p, 3, 4) AS p FROM ("), out);
    assertTrue(out.toUpperCase().contains("WITH CUSTOMER AS"), out);
  }

  /** crm.public 形状对 postgresql/trino 均合法；mysql 用 shop.app 元数据。 */
  private static String yamlFor(String dialect) {
    return "mysql".equals(dialect) ? MYSQL_YAML : TRINO_YAML;
  }

  @Test
  void writeStatementsFailOnEveryDialect() {
    for (String dialect : List.of("postgresql", "trino", "mysql")) {
      SqlMaskException update = assertThrows(SqlMaskException.class,
          () -> engine.rewrite(yamlFor(dialect), "UPDATE customer SET phone = 'x'", dialect),
          dialect);
      assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, update.getCode(), dialect);
      SqlMaskException delete = assertThrows(SqlMaskException.class,
          () -> engine.rewrite(yamlFor(dialect), "DELETE FROM customer WHERE id = 1", dialect),
          dialect);
      assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, delete.getCode(), dialect);
    }
  }

  @Test
  void failingStatementAbortsWholeRunOnEveryDialect() {
    // 一条失败语句让整批改写中止：不会产出部分结果，报错带失败语句序号
    String sql = "SELECT phone FROM customer;\nDELETE FROM customer;\n";
    for (String dialect : List.of("postgresql", "trino", "mysql")) {
      SqlMaskException e = assertThrows(SqlMaskException.class,
          () -> engine.rewrite(yamlFor(dialect), sql, dialect), dialect);
      assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode(), dialect);
      assertTrue(e.getMessage().contains("statement 2"),
          () -> dialect + ": " + e.getMessage());
    }
  }

  @Test
  void cteBodySelfReferenceFailsClosedOnEveryDialect() {
    // spec §10.2 item 7 的另一结论（2026-09-06 观察）：CteExpander 对 CTE 体内
    // 的裸自引用一律按"递归 CTE"拒绝（真实 PostgreSQL/MySQL 的非递归 WITH 语义
    // 会把体内的名字解析为基础表）。该行为发生在方言无关的展开层，三方言一致
    // fail-closed，不存在脱敏泄漏面。
    String sql = "WITH customer AS (SELECT phone FROM customer) SELECT phone FROM customer";
    for (String dialect : List.of("postgresql", "trino", "mysql")) {
      SqlMaskException e = assertThrows(SqlMaskException.class,
          () -> engine.rewrite(yamlFor(dialect), sql, dialect), dialect);
      assertEquals(SqlMaskException.Code.LINEAGE_UNKNOWN, e.getCode(), dialect);
      assertTrue(e.getMessage().contains("recursive CTE 'customer'"),
          () -> dialect + ": " + e.getMessage());
    }
  }
}
