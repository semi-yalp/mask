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
}
