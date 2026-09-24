package io.sqlmask.cli;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --strict} on {@code --pull-metadata}: a column type degradation
 * (real PostgreSQL type outside the supported set, e.g. jsonb) must abort
 * the export with exit code 1, a [STRICT_DEGRADED] diagnostic and no output
 * file; warnings that do not end in "degraded to varchar" (e.g. an empty
 * schema) must not trip strict, and without --strict the degraded export
 * still succeeds. Gap §4.1 item 3 (P0).
 */
class StrictMetadataExportCliTest {

  private static EmbeddedPostgres embedded;
  private static JdbcTemplate jdbc;

  @TempDir
  Path tempDir;

  @BeforeAll
  static void startDatabase() throws IOException {
    embedded = EmbeddedPostgres.builder().start();
    jdbc = new JdbcTemplate(new DriverManagerDataSource(
        embedded.getJdbcUrl("postgres", "postgres")));
    jdbc.execute("CREATE SCHEMA sales");
    // settings jsonb is outside the supported PG type set -> degrades to varchar
    jdbc.execute("CREATE TABLE sales.customer (id bigint, phone varchar(20), settings jsonb)");
  }

  @AfterAll
  static void stopDatabase() throws IOException {
    if (embedded != null) {
      embedded.close();
    }
  }

  private record RunResult(int code, String out, String err) {
  }

  private RunResult run(String... extraArgs) {
    Path output = tempDir.resolve("output.yaml");
    String[] args = new String[]{"--pull-metadata", "--engine", "postgresql",
        "--host", "127.0.0.1", "--port", String.valueOf(embedded.getPort()),
        "--database", "postgres", "--user", "postgres", "--password", "p",
        "--schema", "sales", "--output", output.toAbsolutePath().toString()};
    String[] all = new String[args.length + extraArgs.length];
    System.arraycopy(args, 0, all, 0, args.length);
    System.arraycopy(extraArgs, 0, all, args.length, extraArgs.length);
    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    int code = new SqlMaskApplication().run(all, new ByteArrayInputStream(new byte[0]),
        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
        new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    return new RunResult(code, outBytes.toString(StandardCharsets.UTF_8),
        errBytes.toString(StandardCharsets.UTF_8));
  }

  @Test
  void strictBlocksDegradedExportAndWritesNoOutput() throws IOException {
    Path output = tempDir.resolve("output.yaml");
    RunResult result = run("--strict");
    assertEquals(1, result.code(), () -> "stderr: " + result.err());
    assertTrue(result.err().contains("[STRICT_DEGRADED]"), () -> result.err());
    assertTrue(result.err().contains("degraded to varchar"), () -> result.err());
    assertFalse(Files.exists(output), "the output file must not be created on strict failure");
  }

  @Test
  void nonDegradedWarningsDoNotTripStrict() throws IOException {
    Path output = tempDir.resolve("output.yaml");
    String[] args = new String[]{"--pull-metadata", "--engine", "postgresql",
        "--host", "127.0.0.1", "--port", String.valueOf(embedded.getPort()),
        "--database", "postgres", "--user", "postgres", "--password", "p",
        "--schema", "no_such_schema", "--strict", "--output", output.toAbsolutePath().toString()};
    ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    int code = new SqlMaskApplication().run(args, new ByteArrayInputStream(new byte[0]),
        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
        new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    String err = errBytes.toString(StandardCharsets.UTF_8);
    String out = outBytes.toString(StandardCharsets.UTF_8);
    assertEquals(0, code, () -> "stderr: " + err);
    assertTrue(err.contains("未找到任何表"), () -> err);
    assertFalse(err.contains("STRICT_DEGRADED"), () -> err);
    assertTrue(Files.exists(output), "the output file must be written when strict passes");
    String yaml = Files.readString(output, StandardCharsets.UTF_8);
    assertTrue(yaml.contains("metadata:"), () -> yaml);
    assertTrue(out.contains("0 tables"), () -> out);
  }

  @Test
  void nonStrictExportWritesDegradedMetadataAnyway() throws IOException {
    Path output = tempDir.resolve("output.yaml");
    RunResult result = run(); // no --strict
    assertEquals(0, result.code(), () -> "stderr: " + result.err());
    assertTrue(result.err().contains("degraded to varchar"), () -> result.err());
    assertTrue(Files.exists(output), "without --strict the degraded export must succeed");
    String yaml = Files.readString(output, StandardCharsets.UTF_8);
    assertTrue(yaml.contains("metadata:"), () -> yaml);
    assertTrue(yaml.contains("settings"), () -> yaml);
    assertTrue(yaml.contains("varchar"), () -> yaml);
    assertTrue(result.out().contains("1 tables / 3 columns / 1 warnings"), () -> result.out());
  }
}