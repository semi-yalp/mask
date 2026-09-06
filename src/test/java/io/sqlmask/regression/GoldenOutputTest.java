package io.sqlmask.regression;

import io.sqlmask.rewrite.RewriteEngine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-level behavior lock for the pre-row-filter output: the metadata
 * declares no row filters at all, so every statement must render exactly the
 * bytes the pipeline produced before the feature existed. Unlike the rest of
 * the suite (which normalizes whitespace), these comparisons catch any
 * change in line breaks, quoting, spacing or clause order.
 *
 * <p>To regenerate after an intentional behavior change run with
 * {@code -Dgolden.write=true} and review the diff carefully — the golden
 * files are the regression contract.
 */
class GoldenOutputTest {

  private static final Path CORE_GOLDEN = Path.of("src/test/resources/golden/core-fixture.sql");
  private static final Path WRITE_GOLDEN = Path.of("src/test/resources/golden/write-statements.sql");
  private static final Path TPCDS_GOLDEN = Path.of("src/test/resources/golden/tpcds-common.sql");
  private static final Path METADATA = Path.of("src/test/resources/metadata/integration.yaml");
  private static final Path QUERIES = Path.of("src/test/resources/queries/with-and-nested.sql");
  private static final Path TPCDS_METADATA = Path.of("tpcds/metadata.yaml");
  private static final Path TPCDS_QUERIES = Path.of("tpcds/queries/tpcds_common_cases.sql");

  private static final String WRITE_STATEMENTS = String.join(";\n", List.of(
      "INSERT INTO archive SELECT id, phone FROM customer WHERE status = 'ACTIVE'",
      "CREATE TABLE masked_customer AS SELECT id, phone FROM customer",
      "CREATE TABLE IF NOT EXISTS t3 AS SELECT phone FROM customer",
      "SELECT id, name FROM customer ORDER BY id LIMIT 10")) + ";\n";

  private final RewriteEngine engine = new RewriteEngine();

  @Test
  void coreFixtureRendersIdenticallyByteForByte() throws Exception {
    assertMatchesGolden(CORE_GOLDEN, render(readFile(QUERIES)));
  }

  @Test
  void writeAndPassThroughStatementsRenderIdenticallyByteForByte() throws Exception {
    assertMatchesGolden(WRITE_GOLDEN, render(WRITE_STATEMENTS));
  }

  @Test
  void tpcdsCommonCasesRenderIdenticallyByteForByte() throws Exception {
    // representative real-world shapes (CTE + UNION ALL + self-joins,
    // EXISTS subqueries, aggregates) — locks the CteExpander rewrite and
    // engine wiring against the TPC-DS corpus
    assertMatchesGolden(TPCDS_GOLDEN,
        engine.rewrite(readFile(TPCDS_METADATA), readFile(TPCDS_QUERIES), "postgresql"));
  }
  private String readFile(Path path) throws Exception {
    return Files.readString(path, StandardCharsets.UTF_8);
  }

  private List<RewriteEngine.StatementRewrite> render(String sql) throws Exception {
    return engine.rewrite(readFile(METADATA), sql, "postgresql");
  }

  private void assertMatchesGolden(Path golden, List<RewriteEngine.StatementRewrite> statements)
      throws Exception {
    String actual = statements.stream()
        .map(s -> s.rewrittenSql() + ";")
        .reduce((a, b) -> a + "\n\n" + b)
        .orElse("");
    if (Boolean.getBoolean("golden.write")) {
      Files.createDirectories(golden.getParent());
      Files.writeString(golden, actual, StandardCharsets.UTF_8);
      return;
    }
    assertEquals(Files.readString(golden, StandardCharsets.UTF_8), actual,
        "output changed at byte level; if intentional, regenerate the golden file "
            + "and review the diff as a behavior change");
  }
}
