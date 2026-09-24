package io.sqlmask.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parameter-layer behaviour of the {@code --pull-metadata} export mode:
 * mutual exclusion with --sql/--input, required options and password
 * resolution. The CLI constructs its own introspector, so a real database
 * pull is covered by manual acceptance instead of these tests.
 */
class MetadataExportCliTest {

  private int run(SqlMaskApplication app, String[] args, StringBuilder out, StringBuilder err) {
    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    int code = app.run(args, new ByteArrayInputStream(new byte[0]),
        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
        new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    out.append(outBytes.toString(StandardCharsets.UTF_8));
    err.append(errBytes.toString(StandardCharsets.UTF_8));
    return code;
  }

  @Test
  void pullMetadataTogetherWithSqlIsUsageError() {
    SqlMaskApplication app = new SqlMaskApplication();
    StringBuilder out = new StringBuilder(), err = new StringBuilder();
    int code = run(app, new String[]{"--pull-metadata", "--database", "crm",
        "--user", "postgres", "--password", "x", "--output", "o.yaml", "--sql", "SELECT 1"}, out, err);
    assertEquals(2, code);
    assertTrue(err.toString().contains("--sql/--input"), () -> err.toString());
  }

  @Test
  void missingPasswordIsUsageError() {
    // assumes the test process environment has no PGPASSWORD variable
    SqlMaskApplication app = new SqlMaskApplication();
    StringBuilder out = new StringBuilder(), err = new StringBuilder();
    int code = run(app, new String[]{"--pull-metadata", "--database", "crm",
        "--user", "postgres", "--output", "o.yaml"}, out, err);
    assertEquals(2, code);
    assertTrue(err.toString().contains("password"), () -> err.toString());
  }

  @Test
  void blankDatabaseIsUsageError() {
    SqlMaskApplication app = new SqlMaskApplication();
    StringBuilder out = new StringBuilder(), err = new StringBuilder();
    int code = run(app, new String[]{"--pull-metadata", "--database", " ",
        "--user", "postgres", "--password", "x", "--output", "o.yaml"}, out, err);
    assertEquals(2, code);
    assertTrue(err.toString().contains("database"), () -> err.toString());
  }

  @Test
  void unknownEngineIsUsageError() {
    SqlMaskApplication app = new SqlMaskApplication();
    StringBuilder out = new StringBuilder(), err = new StringBuilder();
    int code = run(app, new String[]{"--pull-metadata", "--engine", "oracle",
        "--database", "d", "--user", "u", "--password", "x", "--output", "o.yaml"}, out, err);
    assertEquals(2, code);
    // "engine" alone also matches picocli's pre-implementation
    // "Unknown option: --engine", so assert the implemented wording
    assertTrue(err.toString().contains("unsupported engine"), () -> err.toString());
  }

  @Test
  void rewriteModeWithoutMetadataIsUsageError() {
    SqlMaskApplication app = new SqlMaskApplication();
    StringBuilder out = new StringBuilder(), err = new StringBuilder();
    int code = run(app, new String[]{"--sql", "SELECT 1"}, out, err);
    assertEquals(2, code);
    assertTrue(err.toString().contains("--metadata"), () -> err.toString());
  }
}
