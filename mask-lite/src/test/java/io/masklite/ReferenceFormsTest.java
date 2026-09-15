package io.masklite;

import io.masklite.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a declared {@code catalog.schema.table} can be referenced in the SQL:
 * fully qualified, schema-qualified, bare, aliased, and the PostgreSQL
 * quoting/case-folding rules around each.
 */
class ReferenceFormsTest {

  private final MaskLite mask = MaskLite.fromYamlFile(
      Path.of("src/test/resources/metadata/lineage.yaml"));

  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  @Test
  void threePartReference() {
    String result = mask.rewrite("SELECT phone FROM crm.public.customer");
    assertEquals("SELECT mask_phone(r.phone, 3, 4) AS phone"
        + " FROM ( SELECT phone FROM crm.public.customer ) AS r;", flat(result));
  }

  @Test
  void twoPartSchemaQualifiedReferenceIsNotSupported() {
    // Calcite resolves a qualified name by concatenating each search-path
    // entry with the name's leading schema parts; the PostgreSQL profile
    // only registers [catalog, schema] paths, so `public.customer` would
    // need a bare [catalog] entry it does not have. One-part and
    // three-part references work; schema-qualified two-part names fail.
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> mask.rewrite("SELECT phone FROM public.customer"));
    assertEquals(SqlMaskException.Code.VALIDATION_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("Object 'public' not found"), () -> e.getMessage());
  }

  @Test
  void onePartReferenceResolvesThroughSearchPath() {
    String result = mask.rewrite("SELECT phone FROM customer");
    String flat = flat(result);
    assertTrue(flat.startsWith("SELECT mask_phone(r.phone, 3, 4) AS phone FROM ( SELECT phone"),
        () -> result);
    assertTrue(flat.contains("FROM customer") && flat.endsWith("AS r;"), () -> result);
  }

  @Test
  void tableAliasAndColumnQualifier() {
    String result = mask.rewrite("SELECT c.phone FROM crm.public.customer c");
    String flat = flat(result);
    assertTrue(flat.startsWith("SELECT mask_phone(r.phone, 3, 4) AS phone FROM ("), () -> result);
    assertTrue(flat.contains("FROM crm.public.customer AS c"), () -> result);
  }

  @Test
  void unquotedUppercaseFoldsToLower() {
    // unquoted identifiers fold to lower case at parse time (PostgreSQL
    // convention), so CRM.PUBLIC.CUSTOMER.PHONE resolves and the rendered
    // inner query is the folded form; the output name is the declared
    // lower-case phone
    String result = mask.rewrite("SELECT PHONE FROM CRM.PUBLIC.CUSTOMER");
    assertEquals("SELECT mask_phone(r.phone, 3, 4) AS phone"
        + " FROM ( SELECT phone FROM crm.public.customer ) AS r;", flat(result));
  }

  @Test
  void quotedMixedCaseColumnKeepsItsSpelling() {
    // a declared mixed-case column is only reachable through double quotes,
    // and the wrapper must quote it on both reference and alias positions
    String result = mask.rewrite("SELECT \"DisplayName\" FROM crm.public.customer");
    String flat = flat(result);
    assertTrue(flat.startsWith("SELECT mask_name(r.\"DisplayName\", '*') AS \"DisplayName\" FROM ("),
        () -> result);
  }

  @Test
  void secondSchemaSameCatalog() {
    String result = mask.rewrite("SELECT phone FROM crm.vip.member");
    String flat = flat(result);
    assertTrue(flat.startsWith("SELECT mask_phone(r.phone, 3, 4) AS phone FROM ( SELECT phone"),
        () -> result);
    assertTrue(flat.contains("FROM crm.vip.member"), () -> result);
  }

  @Test
  void crossSchemaJoinAppliesEachColumnItsOwnPolicy() {
    String result = mask.rewrite("""
        SELECT c.phone, m.email FROM crm.public.customer c
        JOIN crm.vip.member m ON m.id = c.id""");
    String flat = flat(result);
    assertTrue(flat.startsWith(
        "SELECT mask_phone(r.phone, 3, 4) AS phone,"
            + " partial_mask(r.email, '@corp', TRUE, 2.5E0) AS email FROM ("),
        () -> result);
    assertTrue(flat.contains("JOIN crm.vip.member AS m ON m.id = c.id"), () -> result);
  }
}
