package io.sqlmask.cli;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlMaskRunnerTest {

  private static final Path METADATA = Path.of("src/test/resources/metadata/lineage.yaml");
  private static final Path MULTI = Path.of("src/test/resources/queries/multi.sql");

  private final SqlMaskRunner runner = new SqlMaskRunner();

  private static CliOptions options(String sql, Path input, Path output) {
    return new CliOptions(METADATA, sql, input, output, "postgresql");
  }

  @Test
  void rewritesMultipleStatementsInOrder() {
    String result = runner.run(options(null, MULTI, null));

    // one semicolon-terminated statement per line group, original order kept
    List<String> statements = List.of(result.split("\n\n"));
    assertEquals(2, statements.size());
    String first = flat(statements.get(0));
    String second = flat(statements.get(1));
    assertTrue(first.startsWith("SELECT mask_phone(r.phone, 3, 4) AS phone FROM ( SELECT phone"),
        () -> result);
    assertTrue(first.endsWith("AS r;"), () -> result);
    assertTrue(second.toUpperCase().startsWith("SELECT MASK_EMAIL(R.EMAIL) AS EMAIL FROM ( WITH ACTIVE AS "),
        () -> result);
    assertTrue(second.toUpperCase().contains("FROM ( WITH ACTIVE AS (SELECT EMAIL"),
        () -> result);
    assertTrue(second.endsWith("AS r;"), () -> result);
  }

  @Test
  void noPolicyStatementPassesThroughUnchanged() {
    String sql = "SELECT id, name FROM crm.public.customer";
    String result = runner.run(options(sql, null, null));
    assertEquals("SELECT id, name FROM crm.public.customer;", flat(result));
  }

  @Test
  void failsAtomicallyOnUnsupportedStatement() {
    String sql = """
        SELECT phone FROM crm.public.customer;
        UPDATE crm.public.customer SET phone = 'x';
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> runner.run(options(sql, null, null)));
    assertTrue(e.getMessage().contains("statement 2"), () -> e.getMessage());
  }

  @Test
  void failsOnUnknownTableWithStatementOrdinal() {
    String sql = "SELECT phone FROM crm.public.unknown_table";
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> runner.run(options(sql, null, null)));
    assertTrue(e.getMessage().contains("statement 1"), () -> e.getMessage());
  }

  @Test
  void emptyInputProducesEmptyOutput() {
    assertEquals("", runner.run(options(";;", null, null)));
  }

  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  @TempDir
  Path tempDir;

  @Test
  void applicationWritesOutputFileOnlyOnSuccess() throws Exception {
    Path output = tempDir.resolve("masked.sql");
    int code = app("--metadata", METADATA.toString(),
        "--input", MULTI.toString(), "--output", output.toString());
    assertEquals(0, code);
    assertTrue(Files.exists(output));
    String content = Files.readString(output, StandardCharsets.UTF_8);
    assertTrue(content.contains("mask_phone"), () -> content);
    assertTrue(content.contains("mask_email"), () -> content);
  }

  @Test
  void applicationDoesNotCreateOutputFileOnFailure() {
    Path output = tempDir.resolve("masked.sql");
    String sql = "SELECT phone FROM crm.public.customer; DELETE FROM crm.public.customer";
    int code = app("--metadata", METADATA.toString(), "--sql", sql,
        "--output", output.toString());
    assertTrue(code != 0);
    assertFalse(Files.exists(output), "output file must not be created after a failed run");
  }

  @Test
  void applicationRejectsSqlAndInputTogether() {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code = new SqlMaskApplication().run(
        new String[]{"--metadata", METADATA.toString(), "--sql", "SELECT 1",
            "--input", MULTI.toString()},
        System.in, new PrintStream(new ByteArrayOutputStream()),
        new PrintStream(err, true, StandardCharsets.UTF_8));
    assertTrue(code != 0);
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("exactly one"), () -> err.toString());
  }

  @Test
  void applicationWritesToStdoutWithoutOutputOption() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int code = appWithStdout("SELECT phone FROM crm.public.customer", out);
    assertEquals(0, code);
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("mask_phone(r.phone, 3, 4)"),
        () -> out.toString());
  }

  private int app(String... args) {
    return app(args, new ByteArrayOutputStream());
  }

  private int app(String[] args, ByteArrayOutputStream out) {
    return new SqlMaskApplication().run(args, System.in,
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
  }

  private int appWithStdout(String sql, ByteArrayOutputStream out) {
    return app(new String[]{"--metadata", METADATA.toString(), "--sql", sql}, out);
  }
}
