package io.sqlmask.integration;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.rewrite.RewriteEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composition semantics when column masking AND row filtering are enabled on
 * the same metadata. Single-feature behavior is covered by
 * {@link RowFilterIntegrationTest} and {@link SqlMaskIntegrationTest}; here
 * every case turns both features on and pins the layering contract:
 * the row filter is injected as the innermost derived table (over plaintext
 * columns, before aggregation/distinct/ordering/pagination/writing) and the
 * masking UDFs appear only in the outermost projection.
 *
 * <p>Shared fixture {@code metadata/masked-row-filter.yaml}: customer and
 * orders are filtered + masked, product is masked only, audit is filtered
 * only — so a statement can hit any combination from one configuration.
 */
class MaskedRowFilterCombinedTest {

  private static final String YAML = loadYaml();

  private static String loadYaml() {
    try {
      return Files.readString(
          Path.of("src/test/resources/metadata/masked-row-filter.yaml"), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private final RewriteEngine engine = new RewriteEngine();

  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  // ---------------------------------------------------------------- read statements

  @Test
  void maskWrapperSitsOutsideRowFilterAndPredicateStaysPlaintext() {
    // the user WHERE filters on the masked column: it must compare the raw
    // values inside, while only the outer projection sees the UDF
    var result = one("""
        SELECT c.id, c.phone
        FROM crm.public.customer AS c
        WHERE c.phone = '13800138000'""");
    assertTrue(result.masked());
    assertTrue(result.rowFiltered());
    assertFalse(result.unchanged());
    assertEquals(flat("""
            SELECT r.id, mask_phone(r.phone, 3, 4) AS phone FROM (
            SELECT c.id, c.phone FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS c
            WHERE c.phone = '13800138000'
            ) AS r"""),
        flat(result.rewrittenSql()));
    assertEquals(1, count(result.rewrittenSql(), "mask_phone("), () -> result.rewrittenSql());
  }

  @Test
  void filteredColumnAlsoOutputPassesThroughWrapperUnmasked() {
    // status drives the filter but carries no policy: plaintext reference in
    // the wrapper, phone next to it masked
    var result = one("SELECT status, phone FROM crm.public.customer");
    assertEquals(flat("""
            SELECT r.status, mask_phone(r.phone, 3, 4) AS phone FROM (
            SELECT status, phone FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer
            ) AS r"""),
        flat(result.rewrittenSql()));
  }

  @Test
  void selectStarExpandsThroughInjectedDerivedTable() {
    var result = one("SELECT * FROM crm.public.customer");
    assertEquals(flat("""
            SELECT r.id, mask_phone(r.phone, 3, 4) AS phone, mask_email(r.email) AS email, r.status FROM (
            SELECT * FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer
            ) AS r"""),
        flat(result.rewrittenSql()));
  }

  @Test
  void aggregationSeesOnlyFilteredRowsWhileMaskStaysOutermost() {
    var result = one(
        "SELECT region, COUNT(*) AS cnt, SUM(amount) AS total FROM crm.public.orders GROUP BY region");
    assertEquals(flat("""
            SELECT r.region, r.cnt, mask_amount(r.total) AS total FROM (
            SELECT region, COUNT(*) AS cnt, SUM(amount) AS total
            FROM (SELECT * FROM crm.public.orders WHERE region = 'north') AS orders
            GROUP BY region
            ) AS r"""),
        flat(result.rewrittenSql()));
  }

  @Test
  void distinctAndPaginationCompose() {
    var result = one("SELECT DISTINCT phone FROM crm.public.customer LIMIT 3");
    assertEquals(flat("""
            SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
            SELECT DISTINCT phone FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer
            FETCH NEXT 3 ROWS ONLY
            ) AS r"""),
        flat(result.rewrittenSql()));
  }

  @Test
  void orderByMaskedColumnStaysInnerOnPlaintextValues() {
    var result = one("SELECT phone FROM crm.public.customer WHERE id < 100 ORDER BY phone LIMIT 5");
    String rendered = flat(result.rewrittenSql());
    assertEquals(flat("""
            SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
            SELECT phone FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer
            WHERE id < 100
            ORDER BY phone
            FETCH NEXT 5 ROWS ONLY
            ) AS r"""),
        rendered);
    // the ordering happened inside over raw values, exactly once — the
    // wrapper never re-orders (the full equality above pins the layering)
    assertEquals(1, count(rendered, "ORDER BY phone"), () -> rendered);
  }

  @Test
  void joinFiltersBothSidesIndependentlyAndMasksEachLineage() {
    var result = one("""
        SELECT c.phone, o.amount
        FROM crm.public.customer c JOIN crm.public.orders o ON o.customer_id = c.id""");
    assertEquals(flat("""
            SELECT mask_phone(r.phone, 3, 4) AS phone, mask_amount(r.amount) AS amount FROM (
            SELECT c.phone, o.amount
            FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS c
            INNER JOIN (SELECT * FROM crm.public.orders WHERE region = 'north') AS o
            ON o.customer_id = c.id
            ) AS r"""),
        flat(result.rewrittenSql()));
  }

  @Test
  void filteredUnmaskedPartnerTableInjectsWithoutMasking() {
    var result = one(
        "SELECT c.phone, a.action FROM crm.public.customer c JOIN crm.public.audit a ON a.id = c.id");
    assertEquals(flat("""
            SELECT mask_phone(r.phone, 3, 4) AS phone, r.action FROM (
            SELECT c.phone, a.action
            FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS c
            INNER JOIN (SELECT * FROM crm.public.audit WHERE action <> 'secret') AS a
            ON a.id = c.id
            ) AS r"""),
        flat(result.rewrittenSql()));
    assertFalse(result.rewrittenSql().contains("mask_action"), () -> result.rewrittenSql());
  }

  @Test
  void unfilteredMaskedPartnerTableIsNotInjected() {
    var result = one(
        "SELECT c.phone, p.name FROM crm.public.customer c JOIN crm.public.product p ON p.id = c.id");
    assertEquals(flat("""
            SELECT mask_phone(r.phone, 3, 4) AS phone, mask_name(r.name) AS name FROM (
            SELECT c.phone, p.name
            FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS c
            INNER JOIN crm.public.product AS p ON p.id = c.id
            ) AS r"""),
        flat(result.rewrittenSql()));
    assertEquals(1, count(result.rewrittenSql(), "WHERE status = 'active'"),
        () -> result.rewrittenSql());
    assertEquals(0, count(result.rewrittenSql(), "WHERE region ="), () -> result.rewrittenSql());
  }

  @Test
  void selfJoinInjectsOneFilterPerReferenceAndOneMaskPerOutput() {
    var result = one("""
        SELECT c1.phone AS p1, c2.phone AS p2
        FROM crm.public.customer c1 JOIN crm.public.customer c2 ON c1.id = c2.id""");
    assertEquals(flat("""
            SELECT mask_phone(r.p1, 3, 4) AS p1, mask_phone(r.p2, 3, 4) AS p2 FROM (
            SELECT c1.phone AS p1, c2.phone AS p2
            FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS c1
            INNER JOIN (SELECT * FROM crm.public.customer WHERE status = 'active') AS c2
            ON c1.id = c2.id
            ) AS r"""),
        flat(result.rewrittenSql()));
    assertEquals(2, count(result.rewrittenSql(), "WHERE status = 'active'"),
        () -> result.rewrittenSql());
    assertEquals(2, count(result.rewrittenSql(), "mask_phone("), () -> result.rewrittenSql());
  }

  @Test
  void commaJoinInjectsBothSides() {
    var result = one("SELECT c.phone, o.region FROM crm.public.customer c, crm.public.orders o");
    assertEquals(flat("""
            SELECT mask_phone(r.phone, 3, 4) AS phone, r.region FROM (
            SELECT c.phone, o.region
            FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS c,
            (SELECT * FROM crm.public.orders WHERE region = 'north') AS o
            ) AS r"""),
        flat(result.rewrittenSql()));
  }

  @Test
  void cteBodyIsFilteredAndOuterProjectionIsMasked() {
    var result = one("""
        WITH active AS (SELECT phone FROM crm.public.customer WHERE email IS NOT NULL)
        SELECT phone FROM active""");
    assertEquals(flat("""
            SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
            WITH active AS (SELECT phone FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer
            WHERE email IS NOT NULL) SELECT phone FROM active
            ) AS r"""),
        flat(result.rewrittenSql()));
  }

  @Test
  void cteShadowingFilteredBaseTableIsMaskedButNotFiltered() {
    // the only `customer` reference resolves to the CTE over product, so no
    // injection may fire — yet the output still masks through product's policy
    var result = one("WITH customer AS (SELECT name FROM crm.public.product) SELECT name FROM customer");
    assertTrue(result.masked());
    assertFalse(result.rowFiltered());
    assertFalse(result.rewrittenSql().contains("status = 'active'"), () -> result.rewrittenSql());
    assertEquals(flat("""
            SELECT mask_name(r.name) AS name FROM (
            WITH customer AS (SELECT name FROM crm.public.product) SELECT name FROM customer
            ) AS r"""),
        flat(result.rewrittenSql()));
  }

  @Test
  void derivedTableInFromComposesBothFeatures() {
    var result = one("SELECT d.phone FROM (SELECT phone, status FROM crm.public.customer) AS d");
    assertEquals(flat("""
            SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
            SELECT d.phone FROM (SELECT phone, status
            FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer) AS d
            ) AS r"""),
        flat(result.rewrittenSql()));
  }

  @Test
  void predicateSubqueriesAreFilteredButNeverMaskedInside() {
    var inSubquery = one("""
        SELECT c.phone FROM crm.public.customer c
        WHERE c.id IN (SELECT o.customer_id FROM crm.public.orders o)""");
    assertEquals(flat("""
            SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
            SELECT c.phone FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS c
            WHERE c.id IN (SELECT o.customer_id
            FROM (SELECT * FROM crm.public.orders WHERE region = 'north') AS o)
            ) AS r"""),
        flat(inSubquery.rewrittenSql()));
    assertFalse(inSubquery.rewrittenSql().contains("mask_amount"), () -> inSubquery.rewrittenSql());

    var existsSubquery = one("""
        SELECT c.phone FROM crm.public.customer c
        WHERE EXISTS (SELECT 1 FROM crm.public.orders o WHERE o.customer_id = c.id)""");
    assertEquals(flat("""
            SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
            SELECT c.phone FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS c
            WHERE EXISTS (SELECT 1
            FROM (SELECT * FROM crm.public.orders WHERE region = 'north') AS o
            WHERE o.customer_id = c.id)
            ) AS r"""),
        flat(existsSubquery.rewrittenSql()));
  }

  @Test
  void nestedSetOperationBranchesAreEachFilteredUnderOneOuterMask() {
    var result = one("""
        SELECT phone FROM (SELECT phone FROM crm.public.customer
        UNION ALL SELECT phone FROM crm.public.customer) AS t""");
    String rendered = flat(result.rewrittenSql());
    assertEquals(flat("""
            SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
            SELECT phone FROM (SELECT phone
            FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer
            UNION ALL
            SELECT phone
            FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer) AS t
            ) AS r"""),
        rendered);
    assertEquals(2, count(rendered, "WHERE status = 'active'"), () -> rendered);
    assertEquals(1, count(rendered, "mask_phone("), () -> rendered);
  }

  @Test
  void multiSourceExpressionKeepsStablePolicySelectionUnderFilter() {
    var result = one("SELECT concat(email, '-', phone) AS contact FROM crm.public.customer");
    assertEquals(flat("""
            SELECT mask_email(r.contact) AS contact FROM (
            SELECT concat(email, '-', phone) AS contact
            FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer
            ) AS r"""),
        flat(result.rewrittenSql()));
  }

  @Test
  void filterPredicateOnTheMaskedColumnItselfUsesPlaintextColumn() {
    // dedicated config: the row filter is declared on the very column whose
    // output is masked — the predicate must run on raw values inside the
    // injected derived table while the UDF wraps only the outer projection
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              rowFilter: "phone = '13800138000'"
              columns:
                - {name: id, type: bigint}
                - {name: phone, type: varchar}
        columns:
          - catalog: crm
            schema: public
            table: customer
            column: phone
            policy: mask_phone
        policies:
          mask_phone:
            udf: mask_phone
            arguments: [3, 4]
        """;
    var result = engine.rewrite(yaml, "SELECT id, phone FROM crm.public.customer", "postgresql")
        .get(0);
    assertTrue(result.masked() && result.rowFiltered());
    assertEquals(flat("""
            SELECT r.id, mask_phone(r.phone, 3, 4) AS phone FROM (
            SELECT id, phone FROM (SELECT * FROM crm.public.customer WHERE phone = '13800138000') AS customer
            ) AS r"""),
        flat(result.rewrittenSql()));
    assertEquals(1, count(result.rewrittenSql(), "mask_phone("), () -> result.rewrittenSql());
  }

  // ---------------------------------------------------------------- write statements

  @Test
  void insertSelectSourceIsFilteredInsideAndMaskedOutside() {
    var result = one("INSERT INTO archive SELECT c.id, c.phone FROM crm.public.customer c");
    assertTrue(result.masked());
    assertTrue(result.rowFiltered());
    assertEquals(flat("""
            INSERT INTO archive SELECT r.id, mask_phone(r.phone, 3, 4) AS phone FROM (
            SELECT c.id, c.phone FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS c
            ) AS r"""),
        flat(result.rewrittenSql()));
    assertEquals("INSERT INTO archive SELECT c.id, c.phone FROM crm.public.customer c",
        result.originalSql(), "write statements keep the raw input as originalSql");
  }

  @Test
  void ctasSourceIsFilteredInsideAndMaskedOutside() {
    var result = one("CREATE TABLE IF NOT EXISTS archive AS SELECT phone FROM crm.public.customer");
    assertEquals(flat("""
            CREATE TABLE IF NOT EXISTS archive AS SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
            SELECT phone FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer
            ) AS r"""),
        flat(result.rewrittenSql()));
  }

  @Test
  void insertTargetNamedLikeFilteredTableIsNeverFiltered() {
    // only the source query is filtered; the INSERT target must accept every
    // row the (already filtered) source produces
    var result = one("INSERT INTO customer (phone) SELECT phone FROM crm.public.customer");
    assertEquals(flat("""
            INSERT INTO customer (phone) SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
            SELECT phone FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer
            ) AS r"""),
        flat(result.rewrittenSql()));
    assertEquals(1, count(result.rewrittenSql(), "WHERE status = 'active'"),
        () -> result.rewrittenSql());
  }

  @Test
  void insertValuesStillPassesThroughWithBothFeaturesConfigured() {
    var result = one("INSERT INTO archive VALUES (1, 'x')");
    assertFalse(result.masked());
    assertFalse(result.rowFiltered());
    assertTrue(result.unchanged());
  }

  // ---------------------------------------------------------------- flags and originalSql

  @Test
  void allFourFlagCombinationsInOneScript() {
    var results = engine.rewrite(YAML, """
        SELECT 1 AS constant;
        SELECT name FROM crm.public.product;
        SELECT action FROM crm.public.audit;
        SELECT phone FROM crm.public.customer""", "postgresql");
    assertEquals(4, results.size());

    assertFalse(results.get(0).masked() || results.get(0).rowFiltered());
    assertTrue(results.get(0).unchanged());

    assertTrue(results.get(1).masked());
    assertFalse(results.get(1).rowFiltered());
    assertFalse(results.get(1).unchanged());

    assertFalse(results.get(2).masked());
    assertTrue(results.get(2).rowFiltered());
    assertTrue(flat(results.get(2).rewrittenSql()).contains(
            "(SELECT * FROM crm.public.audit WHERE action <> 'secret') AS audit"),
        () -> results.get(2).rewrittenSql());
    assertFalse(results.get(2).rewrittenSql().contains("mask_"), () -> results.get(2).rewrittenSql());

    assertTrue(results.get(3).masked() && results.get(3).rowFiltered());
    assertTrue(flat(results.get(3).rewrittenSql()).startsWith("SELECT mask_phone(r.phone, 3, 4)"),
        () -> results.get(3).rewrittenSql());
  }

  @Test
  void readOriginalSqlIsThePreInjectionRendering() {
    // no injected condition and no UDF may leak into originalSql; the
    // rendering is dialect-normalized (LIMIT becomes FETCH NEXT) but still
    // filter-free so callers can always diff input vs output
    // ORDER BY may only reference selected columns (a pre-existing masking
    // pipeline restriction, independent of row filters), hence id in the list
    var result = one("SELECT id, phone FROM crm.public.customer WHERE id < 100 ORDER BY id LIMIT 5");
    String original = flat(result.originalSql());
    assertFalse(original.contains("status = 'active'"), () -> original);
    assertFalse(original.contains("mask_"), () -> original);
    assertTrue(original.contains("ORDER BY id"), () -> original);
    assertTrue(original.contains("FETCH NEXT 5 ROWS ONLY"), () -> original);
  }

  // ---------------------------------------------------------------- fail-closed under both features

  @Test
  void qualifiedColumnOfFilteredTableFailsEvenWhenOutputIsMaskable() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> one("SELECT crm.public.customer.phone FROM crm.public.customer"));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode());
    assertTrue(e.getMessage().contains("fully qualified columns"), () -> e.getMessage());
  }

  @Test
  void rootSetOperationStillFailsUnderBothFeatures() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> one("SELECT phone FROM crm.public.customer UNION SELECT phone FROM crm.public.customer"));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode());
  }

  @Test
  void duplicateOutputAliasStillFailsWhenFilterAlsoInjects() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> one("SELECT phone, phone FROM crm.public.customer"));
    assertEquals(SqlMaskException.Code.REWRITE_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("duplicate column name 'phone'"), () -> e.getMessage());
  }

  @Test
  void correlatedScalarSubqueryOutputStillFailsClosed() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> one("""
        SELECT c.id, (SELECT o.amount FROM crm.public.orders o WHERE o.customer_id = c.id) AS amt
        FROM crm.public.customer c"""));
    assertEquals(SqlMaskException.Code.LINEAGE_UNKNOWN, e.getCode());
  }

  // ---------------------------------------------------------------- helpers

  private RewriteEngine.StatementRewrite one(String sql) {
    var results = engine.rewrite(YAML, sql, "postgresql");
    assertEquals(1, results.size(), () -> sql);
    return results.get(0);
  }

  private static int count(String text, String needle) {
    int occurrences = 0;
    int index = 0;
    while ((index = text.indexOf(needle, index)) >= 0) {
      occurrences++;
      index += needle.length();
    }
    return occurrences;
  }
}
