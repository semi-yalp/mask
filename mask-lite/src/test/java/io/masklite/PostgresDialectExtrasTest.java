package io.masklite;

import io.masklite.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL dialect extensions that the TPC-DS benchmark surfaced: the
 * variadic {@code concat()} function, {@code date ± integer} day arithmetic
 * and scalar sub-queries in the output projection.
 */
class PostgresDialectExtrasTest {

  private static final Path METADATA = Path.of("src/test/resources/metadata/pgdialect.yaml");

  private final MaskLite mask = MaskLite.fromYamlFile(METADATA);

  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  // --- concat(): PG library function, validation and lineage ---

  @Test
  void concatOfMaskedColumnIsMasked() {
    String result = mask.rewrite("SELECT concat('tel:', phone) AS label FROM crm.public.customer");
    assertEquals("SELECT mask_phone(r.label, 3, 4) AS label"
            + " FROM ( SELECT concat('tel:', phone) AS label FROM crm.public.customer ) AS r;",
        flat(result));
  }

  @Test
  void concatOfUnmaskedColumnsPassesThroughUnchanged() {
    String sql = "SELECT concat('order-', id) AS ref FROM crm.public.orders";
    assertEquals(flat(sql) + ";", flat(mask.rewrite(sql)));
  }

  @Test
  void concatOfConstantsHasNoOriginAndPassesThrough() {
    String sql = "SELECT concat('a', 'b') AS c FROM crm.public.orders";
    assertEquals(flat(sql) + ";", flat(mask.rewrite(sql)));
  }

  @Test
  void concatResolvesCaseInsensitively() {
    String result = mask.rewrite(
        "SELECT CONCAT('tel:', phone) AS label FROM crm.public.customer");
    assertTrue(flat(result).startsWith("SELECT mask_phone(r.label, 3, 4) AS label"), () -> result);
  }

  // --- date ± integer: PG day arithmetic ---

  @Test
  void datePlusIntegerLiteralValidatesAndPassesThrough() {
    String sql = "SELECT id FROM crm.public.orders WHERE created + 5 > DATE '2020-01-01'";
    assertEquals(flat(sql) + ";", flat(mask.rewrite(sql)));
  }

  @Test
  void dateMinusIntegerLiteralValidatesAndPassesThrough() {
    String sql = "SELECT id FROM crm.public.orders WHERE created - 3 < DATE '2019-12-31'";
    assertEquals(flat(sql) + ";", flat(mask.rewrite(sql)));
  }

  @Test
  void integerPlusDateLiteralValidatesAndPassesThrough() {
    String sql = "SELECT id FROM crm.public.orders WHERE 5 + created > DATE '2020-01-01'";
    assertEquals(flat(sql) + ";", flat(mask.rewrite(sql)));
  }

  @Test
  void dateArithmeticOnMaskedColumnStillMasksTheOutput() {
    String result = mask.rewrite(
        "SELECT birth_date + 1 AS next_day FROM crm.public.customer");
    String flat = flat(result);
    assertTrue(flat.startsWith("SELECT mask_date(r.next_day) AS next_day FROM ("), () -> result);
    assertTrue(flat.contains("birth_date + 1"), () -> result);
  }

  @Test
  void numericPlusIntegerIsUnaffected() {
    String sql = "SELECT id + 5 FROM crm.public.orders";
    assertEquals(flat(sql) + ";", flat(mask.rewrite(sql)));
  }

  @Test
  void timestampPlusIntegerStillFailsClosed() {
    // PG itself rejects timestamp + integer; the rewrite must not loosen that
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> mask.rewrite("SELECT id FROM crm.public.orders WHERE created_at + 5 > DATE '2020-01-01'"));
    assertEquals(SqlMaskException.Code.VALIDATION_ERROR, e.getCode());
  }

  // --- scalar sub-queries in the projection: traced lineage ---

  @Test
  void scalarSubQueryOverMaskedColumnMasksTheOutput() {
    String result = mask.rewrite(
        "SELECT (SELECT max(phone) FROM crm.public.customer) AS p FROM crm.public.orders");
    assertEquals("SELECT mask_phone(r.p, 3, 4) AS p"
            + " FROM ( SELECT (SELECT MAX(phone) FROM crm.public.customer) AS p"
            + " FROM crm.public.orders ) AS r;",
        flat(result));
  }

  @Test
  void scalarSubQueryOverUnmaskedColumnPassesThrough() {
    // parser-bound builtins (MAX) unparse in upper case, as everywhere else
    String sql = "SELECT (SELECT max(id) FROM crm.public.customer) AS m FROM crm.public.orders";
    assertEquals("SELECT (SELECT MAX(id) FROM crm.public.customer) AS m FROM crm.public.orders;",
        flat(mask.rewrite(sql)));
  }

  @Test
  void scalarSubQueryWithoutColumnsHasNoOrigin() {
    String sql = "SELECT (SELECT count(*) FROM crm.public.customer) AS c FROM crm.public.orders";
    assertEquals("SELECT (SELECT COUNT(*) FROM crm.public.customer) AS c FROM crm.public.orders;",
        flat(mask.rewrite(sql)));
  }

  @Test
  void caseExpressionOfScalarSubQueriesTracesBothBranches() {
    // TPC-DS q09 shape: CASE WHEN (count sub-query) > k THEN (avg sub-query) ELSE (avg sub-query) END
    String result = mask.rewrite("""
        SELECT CASE WHEN (SELECT count(*) FROM crm.public.orders) > 10
               THEN (SELECT max(phone) FROM crm.public.customer)
               ELSE (SELECT min(phone) FROM crm.public.customer) END AS bucket
        FROM crm.public.orders""");
    String flat = flat(result);
    assertTrue(flat.startsWith("SELECT mask_phone(r.bucket, 3, 4) AS bucket FROM ( SELECT CASE"), () -> result);
    assertTrue(flat.contains("FROM crm.public.orders ) AS r;"), () -> result);
  }

  @Test
  void nestedScalarSubQueryTracesToTheInnerColumn() {
    String result = mask.rewrite(
        "SELECT (SELECT (SELECT max(phone) FROM crm.public.customer)) AS p FROM crm.public.orders");
    assertTrue(flat(result).startsWith("SELECT mask_phone(r.p, 3, 4) AS p"), () -> result);
  }

  @Test
  void existsSubQueryInProjectionStillFailsClosed() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> mask.rewrite("SELECT EXISTS (SELECT 1 FROM crm.public.customer) FROM crm.public.orders"));
    assertEquals(SqlMaskException.Code.LINEAGE_UNKNOWN, e.getCode());
  }

  @Test
  void inSubQueryInProjectionStillFailsClosed() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> mask.rewrite("SELECT id IN (SELECT customer_id FROM crm.public.customer) AS hit FROM crm.public.orders"));
    assertEquals(SqlMaskException.Code.LINEAGE_UNKNOWN, e.getCode());
  }

  // --- unknown functions: opaque catch-all; std ∩ PG-library overlap: dedup ---

  @Test
  void unknownFunctionTracesArgumentsForMasking() {
    String result = mask.rewrite("SELECT concat2('tel:', phone) AS label FROM crm.public.customer");
    assertEquals("SELECT mask_phone(r.label, 3, 4) AS label"
            + " FROM ( SELECT concat2('tel:', phone) AS label FROM crm.public.customer ) AS r;",
        flat(result));
  }

  @Test
  void unknownFunctionWithoutPolicyPassesThroughUnchanged() {
    String sql = "SELECT concat2(id, id) AS d FROM crm.public.orders";
    assertEquals(flat(sql) + ";", flat(mask.rewrite(sql)));
  }

  @Test
  void unknownFunctionNameResolvesCaseInsensitively() {
    String sql = "SELECT CONCAT2('x', id) AS d FROM crm.public.orders";
    assertEquals("SELECT concat2('x', id) AS d FROM crm.public.orders;", flat(mask.rewrite(sql)));
  }

  @Test
  void powerResolvesThroughTheStandardOperator() {
    String sql = "SELECT power(id, 2) AS s FROM crm.public.orders";
    assertEquals("SELECT POWER(id, 2) AS s FROM crm.public.orders;", flat(mask.rewrite(sql)));
  }

  @Test
  void postgresLibraryDropsNamesTheStandardTableAlreadyRegisters() {
    // the duplicate-overload trap (q05/q80 root cause) needs exactly one
    // candidate per name: anything the std table provides (power today) must
    // not survive in the library layer
    long powerCount = io.masklite.dialect.PostgresqlFunctions.TABLE.getOperatorList().stream()
        .filter(op -> op.getName().equalsIgnoreCase("power"))
        .count();
    assertEquals(1L, powerCount);
  }
}
