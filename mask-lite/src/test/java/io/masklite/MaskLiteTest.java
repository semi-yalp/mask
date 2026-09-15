package io.masklite;

import io.masklite.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskLiteTest {

  private static final Path METADATA = Path.of("src/test/resources/metadata/lineage.yaml");

  private final MaskLite mask = MaskLite.fromYamlFile(METADATA);

  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  @Test
  void masksSimpleSelect() {
    String result = mask.rewrite("SELECT phone FROM crm.public.customer");
    assertEquals("SELECT mask_phone(r.phone, 3, 4) AS phone"
        + " FROM ( SELECT phone FROM crm.public.customer ) AS r;", flat(result));
  }

  @Test
  void masksOnlyColumnsWithPolicy() {
    String result = mask.rewrite("SELECT id, phone, status FROM crm.public.customer");
    assertEquals("SELECT r.id, mask_phone(r.phone, 3, 4) AS phone, r.status"
        + " FROM ( SELECT id, phone, status FROM crm.public.customer ) AS r;", flat(result));
  }

  @Test
  void noPolicyStatementPassesThroughUnchanged() {
    String sql = "SELECT id, name FROM crm.public.customer";
    assertEquals(flat(sql) + ";", flat(mask.rewrite(sql)));
  }

  @Test
  void masksCteOutput() {
    String result = mask.rewrite("""
        WITH active AS (SELECT email FROM crm.public.customer)
        SELECT email FROM active""");
    String flat = flat(result);
    assertTrue(flat.startsWith("SELECT mask_email(r.email) AS email FROM ( WITH active AS ("),
        () -> result);
    assertTrue(flat.contains("FROM active") && flat.endsWith("AS r;"), () -> result);
  }

  @Test
  void masksDerivedTableOutput() {
    String result = mask.rewrite(
        "SELECT p FROM (SELECT phone AS p FROM crm.public.customer) t");
    assertEquals("SELECT mask_phone(r.p, 3, 4) AS p"
        + " FROM ( SELECT p FROM (SELECT phone AS p FROM crm.public.customer) AS t ) AS r;",
        flat(result));
  }

  @Test
  void masksJoinColumnAndPassesTheRest() {
    String result = mask.rewrite("""
        SELECT c.phone, o.note FROM crm.public.customer c
        JOIN crm.public.orders o ON o.customer_id = c.id""");
    String flat = flat(result);
    assertTrue(flat.startsWith("SELECT mask_phone(r.phone, 3, 4) AS phone, r.note FROM ("), () -> result);
    assertTrue(flat.contains("JOIN crm.public.orders AS o ON o.customer_id = c.id"), () -> result);
  }

  @Test
  void expressionOnTwoMaskedColumnsPicksSmallestKeyPolicy() {
    // origins are customer.email and customer.phone; the lexicographically
    // smaller key (email) wins the tie-break
    String result = mask.rewrite("SELECT phone || email AS contact FROM crm.public.customer");
    String flat = flat(result);
    assertTrue(flat.startsWith("SELECT mask_email(r.contact) AS contact FROM ("),
        () -> result);
  }

  @Test
  void derivedExpressionStillMasksItsOrigin() {
    String result = mask.rewrite("SELECT upper(phone) AS p FROM crm.public.customer");
    assertTrue(flat(result).startsWith("SELECT mask_phone(r.p, 3, 4) AS p FROM ("), () -> result);
  }

  @Test
  void rewritesMultipleStatementsInOrder() {
    String result = mask.rewrite("""
        SELECT phone FROM crm.public.customer;
        WITH active AS (SELECT email FROM crm.public.customer)
        SELECT email FROM active;""");
    List<String> statements = List.of(result.split("\n\n"));
    assertEquals(2, statements.size());
    assertTrue(flat(statements.get(0)).startsWith("SELECT mask_phone(r.phone, 3, 4) AS phone FROM ("),
        () -> result);
    assertTrue(flat(statements.get(1)).toUpperCase().startsWith("SELECT MASK_EMAIL(R.EMAIL) AS EMAIL FROM ( WITH"),
        () -> result);
  }

  @Test
  void emptyInputProducesEmptyOutput() {
    assertEquals("", mask.rewrite("  "));
  }

  @Test
  void failsAtomicallyOnUnsupportedStatement() {
    String sql = """
        SELECT phone FROM crm.public.customer;
        UPDATE crm.public.customer SET phone = 'x';
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> mask.rewrite(sql));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode());
    assertTrue(e.getMessage().contains("statement 2"), () -> e.getMessage());
  }

  @Test
  void failsOnUnknownTable() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> mask.rewrite("SELECT phone FROM crm.public.unknown_table"));
    assertEquals(SqlMaskException.Code.VALIDATION_ERROR, e.getCode());
    assertTrue(e.getMessage().toLowerCase().contains("unknown_table"), () -> e.getMessage());
  }

  @Test
  void failsOnUnknownPolicyInYaml() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> MaskLite.fromYaml("""
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - name: phone
                  type: varchar
        columns:
          - catalog: crm
            schema: public
            table: customer
            column: phone
            policy: nope
        policies: {}
        """));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("unknown policy 'nope'"), () -> e.getMessage());
  }

  @Test
  void exposesPerStatementResults() {
    List<io.masklite.rewrite.RewriteEngine.StatementRewrite> results =
        mask.rewriteStatements("SELECT phone FROM crm.public.customer");
    assertEquals(1, results.size());
    assertTrue(results.get(0).masked());
    assertEquals("SELECT phone FROM crm.public.customer", flat(results.get(0).originalSql()));
  }
}
