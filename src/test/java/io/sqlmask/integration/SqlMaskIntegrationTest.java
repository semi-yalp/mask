package io.sqlmask.integration;

import io.sqlmask.cli.CliOptions;
import io.sqlmask.cli.SqlMaskRunner;
import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end regression over the documented semantics: reads the fixture
 * file the same way the CLI does, rewrites all statements in order and
 * asserts every guarantee the README/spec promise.
 */
class SqlMaskIntegrationTest {

  private static final Path METADATA = Path.of("src/test/resources/metadata/integration.yaml");
  private static final Path QUERIES = Path.of("src/test/resources/queries/with-and-nested.sql");

  private final SqlMaskRunner runner = new SqlMaskRunner();

  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  private String rewriteFile() {
    return runner.run(new CliOptions(METADATA, null, QUERIES, null, "postgresql"));
  }

  private String rewriteSql(String sql) {
    return runner.run(new CliOptions(METADATA, sql, null, null, "postgresql"));
  }

  @Test
  void rewritesAllFixtureStatementsInOrder() {
    String result = rewriteFile();
    List<String> statements = List.of(result.split("\n\n"));
    assertEquals(4, statements.size(), () -> result);

    String first = flat(statements.get(0));
    assertTrue(first.startsWith("SELECT r.id, mask_phone(r.phone, 3, 4) AS phone, "
            + "mask_email(r.email) AS email FROM ("), () -> result);
    assertTrue(first.contains("WHERE status = 'ACTIVE'"), () -> result);
    assertTrue(first.endsWith(") AS r;"), () -> result);

    String second = flat(statements.get(1));
    assertTrue(second.toUpperCase().startsWith(
        "SELECT MASK_PHONE(R.PHONE, 3, 4) AS PHONE FROM ( WITH ACTIVE AS "), () -> result);

    String third = flat(statements.get(2));
    assertTrue(third.startsWith("SELECT mask_email(r.contact) AS contact FROM ("), () -> result);

    String fourth = flat(statements.get(3));
    assertEquals("SELECT id, name FROM customer;", fourth);
  }

  @Test
  void partialPolicyInputGetsExactlyOneOuterWrapper() {
    String out = flat(rewriteSql(
        "SELECT id, phone, email FROM customer WHERE status = 'ACTIVE'"));
    assertTrue(out.startsWith("SELECT r.id, mask_phone(r.phone, 3, 4) AS phone, "
        + "mask_email(r.email) AS email FROM ("), out);
    assertTrue(out.endsWith(") AS r;"), out);
    assertEquals(1, out.split("\\) AS r;", -1).length - 1, () -> out);
    assertEquals(2, out.split("mask_", -1).length - 1, () -> out);
  }

  @Test
  void innerWhereStaysUnmasked() {
    String out = flat(rewriteSql(
        "SELECT phone FROM customer WHERE phone = '13800138000'"));
    assertTrue(out.contains("WHERE phone = '13800138000'"), out);
    assertFalse(out.contains("mask_phone(WHERE"), out);
    assertFalse(out.contains("mask_phone(phone"), out);
  }

  @Test
  void cteOutputResolvesToBaseColumn() {
    String out = flat(rewriteSql(
        "WITH active AS (SELECT phone FROM customer WHERE status = 'ACTIVE') "
            + "SELECT phone FROM active"));
    assertTrue(out.startsWith("SELECT mask_phone(r.phone, 3, 4) AS phone FROM ("), out);
    assertTrue(out.toUpperCase().contains("WITH ACTIVE AS (SELECT PHONE"), out);
    assertTrue(out.toUpperCase().contains("FROM CUSTOMER"), out);
  }

  @Test
  void multiSourceSelectionIsDeterministicByNormalizedKey() {
    // contact depends on email and phone; 'email' < 'phone' lexicographically
    // so mask_email wins regardless of YAML declaration order
    String out = flat(rewriteSql(
        "SELECT concat(email, '-', phone) AS contact FROM customer"));
    assertTrue(out.startsWith("SELECT mask_email(r.contact) AS contact FROM ("), out);
    assertFalse(out.contains("mask_phone"), out);
  }

  @Test
  void udfCalledOncePerOutputColumn() {
    // two phone-derived outputs -> exactly one mask_phone call each
    String out = flat(rewriteSql(
        "SELECT phone, email, phone AS phone2 FROM customer"));
    assertEquals(2, out.split("mask_phone\\(", -1).length - 1, () -> out);
    assertEquals(1, out.split("mask_email\\(", -1).length - 1, () -> out);
    assertTrue(out.contains("mask_phone(r.phone, 3, 4) AS phone"), out);
    assertTrue(out.contains("mask_phone(r.phone2, 3, 4) AS phone2"), out);
    assertFalse(out.contains("mask_phone(r.mask_"), out);
  }

  @Test
  void noPolicyInputIsReturnedUnchanged() {
    String sql = "SELECT id, name FROM customer";
    // passthrough keeps the query exactly as written, plus the trailing ';'
    assertEquals("SELECT id, name FROM customer;", flat(rewriteSql(sql)));
  }

  @Test
  void unknownTableFailsAtomically() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> rewriteSql("SELECT phone FROM crm.public.nope"));
    assertTrue(e.getMessage().contains("statement 1"), () -> e.getMessage());
  }

  @Test
  void ambiguousColumnFails() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> rewriteSql("SELECT phone FROM customer c1, customer c2"));
    assertTrue(e.getMessage().contains("statement 1"), () -> e.getMessage());
  }

  @Test
  void insertSelectGetsMaskedSourceQuery() {
    String out = flat(rewriteSql(
        "INSERT INTO crm.customer_archive (id, phone) SELECT id, phone FROM customer"));
    assertTrue(out.startsWith("INSERT INTO crm.customer_archive (id, phone) SELECT r.id, "
        + "mask_phone(r.phone, 3, 4) AS phone FROM ("), () -> out);
    assertTrue(out.endsWith(") AS r;"), out);
    // the target table does not need to be declared in the metadata
  }

  @Test
  void createTableAsSelectGetsMaskedSourceQuery() {
    String out = flat(rewriteSql(
        "CREATE TABLE masked_customer AS SELECT id, phone FROM customer WHERE status = 'ACTIVE'"));
    assertTrue(out.startsWith("CREATE TABLE masked_customer AS SELECT r.id, "
        + "mask_phone(r.phone, 3, 4) AS phone FROM ("), () -> out);
    assertTrue(out.contains("WHERE status = 'ACTIVE'"), out);
    assertTrue(out.endsWith(") AS r;"), out);
  }

  @Test
  void ctasWithCteKeepsCteInsideWrapper() {
    String out = flat(rewriteSql(
        "CREATE TABLE t2 AS WITH active AS (SELECT phone FROM customer) SELECT phone FROM active"));
    assertTrue(out.startsWith("CREATE TABLE t2 AS SELECT mask_phone(r.phone, 3, 4) AS phone FROM ("),
        out);
    assertTrue(out.toUpperCase().contains("WITH ACTIVE AS"), out);
  }

  @Test
  void insertSelectWithoutPolicyPassesThroughUnchanged() {
    String sql = "INSERT INTO crm.customer_archive SELECT id, name FROM customer";
    assertEquals(sql + ";", flat(rewriteSql(sql)));
  }

  @Test
  void insertValuesPassesThroughUnchanged() {
    String sql = "INSERT INTO crm.customer_archive (id, phone) VALUES (1, '138')";
    assertEquals(sql + ";", flat(rewriteSql(sql)));
  }

  @Test
  void insertValuesWithHiddenSubqueryFails() {
    // a scalar subquery inside VALUES reads columns we cannot trace
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> rewriteSql(
        "INSERT INTO crm.customer_archive (id) VALUES ((SELECT id FROM customer))"));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode());
    assertTrue(e.getMessage().contains("statement 1"), () -> e.getMessage());
  }

  @Test
  void insertIfNotExistsCtasKeepsIfNotExists() {
    String out = flat(rewriteSql(
        "CREATE TABLE IF NOT EXISTS t3 AS SELECT phone FROM customer"));
    assertTrue(out.startsWith("CREATE TABLE IF NOT EXISTS t3 AS SELECT mask_phone("), out);
  }

  @Test
  void updateAndDeleteStillFail() {
    assertThrows(SqlMaskException.class,
        () -> rewriteSql("UPDATE customer SET phone = 'x'"));
    assertThrows(SqlMaskException.class,
        () -> rewriteSql("DELETE FROM customer WHERE id = 1"));
  }

  @Test
  void failingStatementAbortsWholeRun() {
    String sql = """
        SELECT phone FROM customer;
        DELETE FROM customer;
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> rewriteSql(sql));
    assertTrue(e.getMessage().contains("statement 2"), () -> e.getMessage());
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode());
  }

  @Test
  void mixedQueryAndWriteStatementsKeepOrder() {
    String out = rewriteSql("""
        SELECT phone FROM customer;
        INSERT INTO crm.customer_archive SELECT id, phone FROM customer;
        """);
    List<String> statements = List.of(out.split("\n\n"));
    assertEquals(2, statements.size(), () -> out);
    assertTrue(flat(statements.get(0)).startsWith("SELECT mask_phone(r.phone, 3, 4) AS phone FROM ("),
        out);
    assertTrue(flat(statements.get(1)).startsWith("INSERT INTO crm.customer_archive SELECT r.id,"),
        out);
  }
}
