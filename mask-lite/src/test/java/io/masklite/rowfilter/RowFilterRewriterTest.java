package io.masklite.rowfilter;

import io.masklite.MaskLite;
import io.masklite.error.SqlMaskException;
import io.masklite.rewrite.RewriteEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row-filter injection through the mask-lite pipeline: table reference
 * resolution (bare/aliased/joined, CTE shadowing), the combined
 * masking + row-filter output, and fail-closed refusals.
 */
class RowFilterRewriterTest {

  private static final String YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            rowFilter: "status = 'active'"
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
              - {name: status, type: varchar}
          - catalog: crm
            schema: public
            name: orders
            rowFilter: "region = 'north'"
            columns:
              - {name: id, type: bigint}
              - {name: region, type: varchar}
          - catalog: crm
            schema: public
            name: product
            columns:
              - {name: id, type: bigint}
      policies: {}
      """;

  private static final String MASKED_YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            rowFilter: "status = 'active'"
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
              - {name: status, type: varchar}
      policies:
        phone_mask:
          udf: mask_phone
          arguments: [3, 4]
      columns:
        - {catalog: crm, schema: public, table: customer, column: phone, policy: phone_mask}
      """;

  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  private static MaskLite mask(String yaml) {
    return MaskLite.fromYaml(yaml);
  }

  @Test
  void injectsFilterOnBareTableReference() {
    String result = mask(YAML).rewrite("SELECT id FROM crm.public.customer");
    assertEquals("SELECT id FROM (SELECT * FROM crm.public.customer WHERE status = 'active')"
        + " AS customer;", flat(result));
  }

  @Test
  void injectsFilterOnAliasedTableReferenceKeepingAlias() {
    String result = mask(YAML).rewrite("SELECT c.id FROM crm.public.customer c");
    assertEquals("SELECT c.id FROM (SELECT * FROM crm.public.customer WHERE status = 'active')"
        + " AS c;", flat(result));
  }

  @Test
  void injectsFiltersOnBothJoinSides() {
    String result = mask(YAML).rewrite(
        "SELECT c.id FROM crm.public.customer c JOIN crm.public.orders o ON c.id = o.id");
    String flat = flat(result);
    assertTrue(flat.contains(
        "FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS c"), () -> result);
    assertTrue(flat.contains(
        "JOIN (SELECT * FROM crm.public.orders WHERE region = 'north') AS o"), () -> result);
    assertTrue(flat.endsWith("ON c.id = o.id;"), () -> result);
  }

  @Test
  void cteNameShadowsFilteredBaseTable() {
    // the CTE named `customer` shadows the base table at the outer scope: no
    // injection there — but the CTE body's own reference to the base table
    // must still be filtered
    String result = mask(YAML).rewrite(
        "WITH customer AS (SELECT id, status FROM crm.public.customer WHERE id > 1) "
            + "SELECT id FROM customer");
    assertEquals("WITH customer AS (SELECT id, status FROM"
            + " (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer"
            + " WHERE id > 1) SELECT id FROM customer;",
        flat(result));
  }

  @Test
  void unfilteredStatementPassesThroughUnchanged() {
    String sql = "SELECT id FROM crm.public.product";
    assertEquals(flat(sql) + ";", flat(mask(YAML).rewrite(sql)));
  }

  @Test
  void filterOnlyConfigStillUnwrapsUnaffectedStatements() {
    RewriteEngine.StatementRewrite statement =
        mask(YAML).rewriteStatements("SELECT id FROM crm.public.product").get(0);
    assertFalse(statement.rowFiltered());
    assertFalse(statement.masked());
    assertTrue(statement.unchanged());
  }

  @Test
  void masksAndFiltersInOneStatement() {
    List<RewriteEngine.StatementRewrite> statements =
        mask(MASKED_YAML).rewriteStatements("SELECT id, phone FROM crm.public.customer");
    RewriteEngine.StatementRewrite statement = statements.get(0);
    assertTrue(statement.masked());
    assertTrue(statement.rowFiltered());
    assertEquals("SELECT r.id, mask_phone(r.phone, 3, 4) AS phone FROM"
            + " ( SELECT id, phone FROM (SELECT * FROM crm.public.customer"
            + " WHERE status = 'active') AS customer ) AS r",
        flat(statement.rewrittenSql()));
  }

  @Test
  void qualifiedColumnReferenceOfFilteredTableIsRefused() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> mask(YAML).rewrite("SELECT crm.public.customer.id FROM crm.public.customer"));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode());
  }

  @Test
  void filterWithSubqueryFailsConfigLoad() {
    String bad = YAML.replace("status = 'active'",
        "status IN (SELECT status FROM crm.public.orders)");
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> mask(bad).rewrite("SELECT id FROM crm.public.customer"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("must not contain subqueries"), e::getMessage);
  }

  @Test
  void filterWithFunctionFailsConfigLoad() {
    String bad = YAML.replace("status = 'active'", "upper(status) = 'ACTIVE'");
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> mask(bad).rewrite("SELECT id FROM crm.public.customer"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("is not a valid condition")
        || e.getMessage().contains("is not allowed"), e::getMessage);
  }

  @Test
  void filterWithUnknownColumnFailsConfigLoad() {
    String bad = YAML.replace("status = 'active'", "statuz = 'active'");
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> mask(bad).rewrite("SELECT id FROM crm.public.customer"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("'statuz' is not one"), e::getMessage);
  }

  @Test
  void blankRowFilterDeclarationFailsConfigLoad() {
    String bad = YAML.replace("status = 'active'", "");
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> mask(bad));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }
}
