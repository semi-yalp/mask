package io.masklite;

import io.masklite.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rewrite behavior at the edges: projections, clauses, literals, failures. */
class RewriteEdgeCasesTest {

  private final MaskLite mask = MaskLite.fromYamlFile(
      Path.of("src/test/resources/metadata/lineage.yaml"));

  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  @Test
  void starExpansionMasksEachProjectedColumn() {
    String result = mask.rewrite("SELECT * FROM crm.public.customer");
    assertEquals("SELECT r.id, mask_phone(r.phone, 3, 4) AS phone, mask_email(r.email) AS email,"
            + " r.status, r.name, r.address, mask_name(r.\"DisplayName\", '*') AS \"DisplayName\""
            + " FROM ( SELECT * FROM crm.public.customer ) AS r;",
        flat(result));
  }

  @Test
  void columnAliasKeepsTheUserAlias() {
    String result = mask.rewrite("SELECT phone AS contact FROM crm.public.customer");
    assertEquals("SELECT mask_phone(r.contact, 3, 4) AS contact"
        + " FROM ( SELECT phone AS contact FROM crm.public.customer ) AS r;", flat(result));
  }

  @Test
  void aggregateOverMaskedColumnStillMasks() {
    // count(phone) has a derived origin on phone; a derived origin still
    // counts — the aggregate output must not leak the raw distribution
    // (Calcite renders the aggregate name in upper case)
    String result = mask.rewrite("SELECT count(phone) AS c FROM crm.public.customer");
    assertEquals("SELECT mask_phone(r.c, 3, 4) AS c"
        + " FROM ( SELECT COUNT(phone) AS c FROM crm.public.customer ) AS r;", flat(result));
  }

  @Test
  void aliasedExpressionOverTwoColumnsPicksOneDeterministicPolicy() {
    // origins email + phone; the lexicographically smaller key (email) wins
    String result = mask.rewrite("SELECT phone || email AS contact FROM crm.public.customer");
    assertEquals("SELECT mask_email(r.contact) AS contact"
        + " FROM ( SELECT phone || email AS contact FROM crm.public.customer ) AS r;",
        flat(result));
  }

  @Test
  void unaliasedExpressionIsNamedThroughTheWrapperAliasList() {
    // the inner query stays byte-for-byte the user's text; the unnamed
    // expression (PostgreSQL's unnameable "?column?") is named positionally
    // by the derived-table column alias list on the wrapper's FROM clause
    String result = mask.rewrite("SELECT phone || email FROM crm.public.customer");
    assertEquals("SELECT mask_email(r.mask_col_1) AS mask_col_1"
        + " FROM ( SELECT phone || email FROM crm.public.customer ) AS r (mask_col_1);",
        flat(result));
  }

  @Test
  void unaliasedConstantBesideMaskedColumnIsNamedToo() {
    // constants the wrapper merely passes through also need a name; the
    // alias list names every output column, existing names included
    String result = mask.rewrite("SELECT phone, 1 + 1 FROM crm.public.customer");
    assertEquals("SELECT mask_phone(r.phone, 3, 4) AS phone, r.mask_col_1"
        + " FROM ( SELECT phone, 1 + 1 FROM crm.public.customer ) AS r (phone, mask_col_1);",
        flat(result));
  }

  @Test
  void duplicateOutputNamesBecomeReferenceable() {
    // Calcite keeps the duplicate validated names; the positional alias
    // list renames later occurrences with a suffix, so duplicate output
    // names no longer block the rewrite
    String result = mask.rewrite("SELECT phone, phone FROM crm.public.customer");
    assertEquals("SELECT mask_phone(r.phone, 3, 4) AS phone, mask_phone(r.phone_2, 3, 4) AS phone_2"
        + " FROM ( SELECT phone, phone FROM crm.public.customer ) AS r (phone, phone_2);",
        flat(result));
  }

  @Test
  void generatedNameCollidingWithUserAliasIsRenamed() {
    // a user alias equal to a generated name is handled the same way:
    // the later occurrence gets a suffix instead of failing
    String result = mask.rewrite(
        "SELECT phone || email AS mask_col_1, phone || email || phone FROM crm.public.customer");
    assertEquals("SELECT mask_email(r.mask_col_1) AS mask_col_1, mask_email(r.mask_col_1_2) AS mask_col_1_2"
        + " FROM ( SELECT phone || email AS mask_col_1, phone || email || phone"
        + " FROM crm.public.customer ) AS r (mask_col_1, mask_col_1_2);",
        flat(result));
  }

  @Test
  void unaliasedExpressionWithoutAnyMaskPassesThrough() {
    // no policy hit -> no wrapper -> statement untouched, original
    // ?column? naming preserved
    String result = mask.rewrite("SELECT 1 + 1 FROM crm.public.customer");
    assertEquals("SELECT 1 + 1 FROM crm.public.customer;", flat(result));
  }

  @Test
  void distinctStaysInsideTheWrapper() {
    String result = mask.rewrite("SELECT DISTINCT phone FROM crm.public.customer");
    String flat = flat(result);
    assertTrue(flat.startsWith("SELECT mask_phone(r.phone, 3, 4) AS phone FROM ( SELECT DISTINCT"),
        () -> result);
    assertTrue(flat.contains("DISTINCT phone FROM crm.public.customer") && flat.endsWith("AS r;"),
        () -> result);
  }

  @Test
  void orderByStaysInsideTheWrapper() {
    String result = mask.rewrite("SELECT phone FROM crm.public.customer ORDER BY phone DESC");
    String flat = flat(result);
    assertTrue(flat.contains("ORDER BY phone DESC") && flat.endsWith(") AS r;"), () -> result);
  }

  @Test
  void noPolicyStatementPassesThroughAsTheParsedSnapshot() {
    // passthrough returns the pre-validation unparse, which normalizes the
    // statement: count -> COUNT, LIMIT n -> FETCH NEXT n ROWS ONLY
    String result = mask.rewrite("""
        SELECT status, count(*) FROM crm.public.customer
        GROUP BY status ORDER BY status LIMIT 5""");
    assertEquals("SELECT status, COUNT(*) FROM crm.public.customer"
        + " GROUP BY status ORDER BY status FETCH NEXT 5 ROWS ONLY;", flat(result));
  }

  @Test
  void whereOnMaskedColumnWithoutProjectionPassesThrough() {
    // masking wraps outputs only; a masked column that never reaches the
    // projection leaves the statement untouched (including its WHERE)
    String sql = "SELECT id FROM crm.public.customer WHERE phone LIKE '138%'";
    assertEquals(flat(sql) + ";", flat(mask.rewrite(sql)));
  }

  @Test
  void orderByNonProjectedMaskedColumnPassesThrough() {
    String sql = "SELECT id FROM crm.public.customer ORDER BY phone";
    assertEquals(flat(sql) + ";", flat(mask.rewrite(sql)));
  }

  @Test
  void stringArgumentsRenderAsSqlLiterals() {
    // '@corp' quoted, true -> TRUE, 2.5 as an approximate numeric renders
    // in Calcite's scientific form 2.5E0 (valid PostgreSQL float syntax)
    String result = mask.rewrite("SELECT email FROM crm.vip.member");
    String flat = flat(result);
    assertTrue(flat.startsWith(
        "SELECT partial_mask(r.email, '@corp', TRUE, 2.5E0) AS email FROM ("), () -> result);
  }

  @Test
  void semicolonInsideStringLiteralDoesNotSplit() {
    String sql = "SELECT note FROM crm.public.orders WHERE note = 'it''s;a;b'";
    assertEquals(flat(sql) + ";", flat(mask.rewrite(sql)));
  }

  @Test
  void dollarQuotedStringFailsAtParse() {
    // the statement splitter knows $$...$$ (it will not split on the inner
    // semicolon, see SqlStatementSplitterTest), but the Calcite babel
    // parser has no dollar-quoted string syntax, so such SQL cannot be
    // processed at all
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> mask.rewrite("SELECT note FROM crm.public.orders WHERE note = $$a;b$$"));
    assertEquals(SqlMaskException.Code.PARSE_ERROR, e.getCode());
  }

  @Test
  void commentsAreDroppedFromOutput() {
    String result = mask.rewrite("""
        SELECT id -- trailing ; comment
        FROM crm.public.customer /* block ; comment */""");
    assertEquals("SELECT id FROM crm.public.customer;", flat(result));
  }

  @Test
  void unionIsRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> mask.rewrite(
        "SELECT phone FROM crm.public.customer UNION SELECT email FROM crm.public.customer"));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode());
  }

  @Test
  void recursiveCteIsRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> mask.rewrite("WITH cte AS (SELECT id FROM cte) SELECT id FROM cte"));
    assertEquals(SqlMaskException.Code.LINEAGE_UNKNOWN, e.getCode());
    assertTrue(e.getMessage().contains("recursive CTE"), () -> e.getMessage());
  }

  @Test
  void scalarSubqueryInProjectionIsRejectedAsUntraceable() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> mask.rewrite(
        "SELECT id, (SELECT max(id) FROM crm.public.customer) AS m FROM crm.public.customer"));
    assertEquals(SqlMaskException.Code.LINEAGE_UNKNOWN, e.getCode());
    assertTrue(e.getMessage().contains("no safely traceable origin"), () -> e.getMessage());
  }
}
