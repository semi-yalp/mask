package io.sqlmask.integration;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.rewrite.RewriteEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end pipeline with row filters: injection composes with masking,
 * write statements filter their source only, and the result record keeps
 * {@code originalSql} as the statement as written while {@code rowFiltered}
 * marks every injection.
 */
class RowFilterIntegrationTest {

  private static final String MASKING_YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            rowFilter: "status = 'active'"
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
              - {name: email, type: varchar}
              - {name: status, type: varchar}
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

  private static final String ORDERS_YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: orders
            rowFilter: "region = 'north'"
            columns:
              - {name: id, type: bigint}
              - {name: region, type: varchar}
      policies: {}
      """;

  /** customer filtered, plus a second unfiltered table for CTE scoping shapes. */
  private static final String CTE_FORWARD_YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            rowFilter: "status = 'active'"
            columns:
              - {name: id, type: bigint}
              - {name: status, type: varchar}
          - catalog: crm
            schema: public
            name: other
            columns:
              - {name: id, type: bigint}
              - {name: tag, type: varchar}
      policies: {}
      """;

  private final RewriteEngine engine = new RewriteEngine();

  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  @Test
  void filterAndMaskingCompose() {
    var results = engine.rewrite(MASKING_YAML, """
        SELECT c.id, c.phone
        FROM crm.public.customer AS c
        WHERE c.id < 100
        ORDER BY c.id
        LIMIT 10""", "postgresql");
    assertEquals(1, results.size());
    var result = results.get(0);
    assertTrue(result.masked());
    assertTrue(result.rowFiltered());
    assertFalse(result.unchanged());
    assertEquals(flat("""
            SELECT r.id, mask_phone(r.phone, 3, 4) AS phone FROM (
            SELECT c.id, c.phone
            FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS c
            WHERE c.id < 100
            ORDER BY c.id
            FETCH NEXT 10 ROWS ONLY
            ) AS r"""),
        flat(result.rewrittenSql()));
    // originalSql keeps the statement as written: no injected filter inside
    assertFalse(flat(result.originalSql()).contains("status = 'active'"),
        () -> result.originalSql());
  }

  @Test
  void rowFilterOnlyReadStatement() {
    var results = engine.rewrite(MASKING_YAML,
        "SELECT id FROM crm.public.customer WHERE id > 5", "postgresql");
    var result = results.get(0);
    assertFalse(result.masked());
    assertTrue(result.rowFiltered());
    assertFalse(result.unchanged());
    assertTrue(flat(result.rewrittenSql()).contains(
            "FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer"),
        () -> result.rewrittenSql());
    assertEquals("SELECT id FROM crm.public.customer WHERE id > 5",
        flat(result.originalSql()), "originalSql is the statement as written");
  }

  @Test
  void statementWithoutHitsPassesThroughUntouched() {
    var results = engine.rewrite(MASKING_YAML, "SELECT 1 AS constant", "postgresql");
    var result = results.get(0);
    assertFalse(result.masked());
    assertFalse(result.rowFiltered());
    assertTrue(result.unchanged());
    assertEquals(result.originalSql(), result.rewrittenSql());
  }

  @Test
  void withStatementFiltersCteBody() {
    var results = engine.rewrite(MASKING_YAML,
        "WITH active AS (SELECT id FROM crm.public.customer) SELECT id FROM active",
        "postgresql");
    var result = results.get(0);
    assertTrue(result.rowFiltered());
    assertTrue(flat(result.rewrittenSql()).contains(
            "WITH active AS (SELECT id FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer)"),
        () -> result.rewrittenSql());
  }

  @Test
  void withForwardReferenceToFilteredTableInjectsBaseTableBinding() {
    // both engines bind a reference in an item body to a later sibling to the
    // BASE table (PostgreSQL: non-recursive WITH items only see earlier
    // siblings; Calcite probe-verified) — skipping the reference would leak
    // the filtered base table's rows unfiltered through item a
    var results = engine.rewrite(CTE_FORWARD_YAML,
        "WITH a AS (SELECT id FROM customer), customer AS (SELECT id FROM crm.public.other) "
            + "SELECT * FROM a", "postgresql");
    var result = results.get(0);
    assertTrue(result.rowFiltered());
    String rendered = flat(result.rewrittenSql());
    assertTrue(rendered.contains(
            "WITH a AS (SELECT id FROM (SELECT * FROM customer WHERE status = 'active') AS customer)"),
        () -> rendered);
  }

  @Test
  void withForwardReferenceFiltersBothBodiesWhenSiblingIsAlsoFiltered() {
    var results = engine.rewrite(CTE_FORWARD_YAML,
        "WITH a AS (SELECT id FROM customer), customer AS (SELECT id FROM crm.public.customer) "
            + "SELECT * FROM a", "postgresql");
    var result = results.get(0);
    assertTrue(result.rowFiltered());
    String rendered = flat(result.rewrittenSql());
    assertEquals(2, countOccurrences(rendered, "WHERE status = 'active'"), () -> rendered);
    // every bare `FROM customer` that remains sits inside a filtered derived
    // table — no unfiltered reference may survive outside them
    assertEquals(countOccurrences(rendered, "FROM customer"),
        countOccurrences(rendered, "FROM customer WHERE status = 'active'"), () -> rendered);
  }

  @Test
  void withSelfReferenceOfFilteredTableNameFailsClosed() {
    // the rewriter injects the self-reference's base-table binding; the
    // pipeline then fails closed at CteExpander's existing recursive-CTE
    // diagnostic — the cycle never produces output
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> engine.rewrite(CTE_FORWARD_YAML,
            "WITH customer AS (SELECT id FROM customer) SELECT * FROM customer", "postgresql"));
    assertEquals(SqlMaskException.Code.LINEAGE_UNKNOWN, e.getCode(), () -> e.getMessage());
    assertTrue(e.getMessage().contains("recursive CTE 'customer'"), () -> e.getMessage());
  }

  @Test
  void insertSelectFiltersSource() {
    var results = engine.rewrite(ORDERS_YAML,
        "INSERT INTO archive SELECT * FROM crm.public.orders", "postgresql");
    var result = results.get(0);
    assertFalse(result.masked());
    assertTrue(result.rowFiltered());
    assertFalse(result.unchanged());
    assertEquals(flat("INSERT INTO archive SELECT * FROM "
            + "(SELECT * FROM crm.public.orders WHERE region = 'north') AS orders"),
        flat(result.rewrittenSql()));
    assertEquals("INSERT INTO archive SELECT * FROM crm.public.orders",
        result.originalSql(), "write statements keep the raw input as originalSql");
  }

  @Test
  void ctasKeepsTargetShapeAndFiltersSource() {
    var results = engine.rewrite(ORDERS_YAML,
        "CREATE TABLE IF NOT EXISTS archive AS SELECT id, region FROM crm.public.orders",
        "postgresql");
    var result = results.get(0);
    assertTrue(result.rowFiltered());
    assertFalse(result.masked());
    assertEquals(flat("CREATE TABLE IF NOT EXISTS archive AS SELECT id, region FROM "
            + "(SELECT * FROM crm.public.orders WHERE region = 'north') AS orders"),
        flat(result.rewrittenSql()));
  }

  @Test
  void insertValuesPassesThrough() {
    var results = engine.rewrite(ORDERS_YAML,
        "INSERT INTO archive VALUES (1, 'north')", "postgresql");
    var result = results.get(0);
    assertFalse(result.masked());
    assertFalse(result.rowFiltered());
    assertTrue(result.unchanged());
  }

  @Test
  void writeSourceWithOrderAndLimitRendersOnce() {
    var results = engine.rewrite(ORDERS_YAML,
        "INSERT INTO archive SELECT * FROM crm.public.orders ORDER BY id LIMIT 3",
        "postgresql");
    var result = results.get(0);
    assertTrue(result.rowFiltered());
    String rewritten = flat(result.rewrittenSql());
    assertEquals(1, countOccurrences(rewritten, "ORDER BY id"), () -> rewritten);
    assertEquals(1, countOccurrences(rewritten, "FETCH NEXT 3 ROWS ONLY"), () -> rewritten);
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(needle, index)) >= 0) {
      count++;
      index += needle.length();
    }
    return count;
  }

  @Test
  void mixedStatementsCarryIndependentFlags() {
    var results = engine.rewrite(MASKING_YAML, """
        SELECT 1 AS constant;
        SELECT id, phone FROM crm.public.customer;
        SELECT id FROM crm.public.customer WHERE id > 5""", "postgresql");
    assertEquals(3, results.size());
    assertFalse(results.get(0).masked() || results.get(0).rowFiltered());
    assertTrue(results.get(1).masked() && results.get(1).rowFiltered());
    assertFalse(results.get(2).masked());
    assertTrue(results.get(2).rowFiltered());
  }

  @Test
  void ambiguousUnqualifiedReferenceFailsTheWholeRun() {
    String yaml = """
        metadata:
          tables:
            - catalog: a
              schema: public
              name: customer
              rowFilter: "id > 0"
              columns:
                - {name: id, type: bigint}
            - catalog: b
              schema: public
              name: customer
              columns:
                - {name: id, type: bigint}
        policies: {}
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> engine.rewrite(yaml, "SELECT id FROM customer", "postgresql"));
    assertEquals(SqlMaskException.Code.VALIDATION_ERROR, e.getCode());
  }
}
