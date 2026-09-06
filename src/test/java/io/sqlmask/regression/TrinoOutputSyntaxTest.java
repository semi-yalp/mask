package io.sqlmask.regression;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.rewrite.RewriteEngine;
import io.trino.sql.parser.SqlParser;
import io.trino.sql.tree.Statement;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that every SQL statement the trino dialect generates (wrapped or
 * passed through) is accepted by the real Trino parser — spec §6 item 4.
 */
class TrinoOutputSyntaxTest {

  private static final Path TRINO_METADATA =
      Path.of("src/test/resources/metadata/trino-integration.yaml");
  private static final Path TPCDS_METADATA = Path.of("tpcds/metadata.yaml");
  private static final Path TPCDS_QUERIES_DIR = Path.of("tpcds/queries");

  private final RewriteEngine engine = new RewriteEngine();

  private static void assertValidTrino(String sql) {
    try {
      Statement statement = new SqlParser().createStatement(sql);
      if (statement == null) {
        fail("parser returned null for: " + sql);
      }
    } catch (RuntimeException e) {
      fail("generated SQL is not valid Trino: " + sql + "\n" + e.getMessage());
    }
  }

  @Test
  void allCoreFixtureOutputsParseInRealTrino() throws Exception {
    String metadata = Files.readString(TRINO_METADATA, StandardCharsets.UTF_8);
    String queries = Files.readString(
        Path.of("src/test/resources/queries/with-and-nested.sql"), StandardCharsets.UTF_8);
    for (RewriteEngine.StatementRewrite statement : engine.rewrite(metadata, queries, "trino")) {
      assertValidTrino(statement.rewrittenSql());
    }
  }

  @Test
  void trinoTpcdsPortableSubsetParsesInRealTrino() throws Exception {
    String metadata = Files.readString(TPCDS_METADATA, StandardCharsets.UTF_8);
    for (String file : List.of("tpcds_common_cases.sql", "tpcds_masking_test.sql",
        "tpcds_oneline.sql", "tpcds_crlf.sql")) {
      String queries = Files.readString(
          TPCDS_QUERIES_DIR.resolve(file), StandardCharsets.UTF_8);
      try {
        for (RewriteEngine.StatementRewrite statement : engine.rewrite(metadata, queries, "trino")) {
          assertValidTrino(statement.rewrittenSql());
        }
      } catch (SqlMaskException e) {
        fail(file + " was expected to rewrite under trino but failed: " + e.getMessage());
      }
    }
  }
}
