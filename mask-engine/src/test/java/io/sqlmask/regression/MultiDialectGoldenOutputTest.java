package io.sqlmask.regression;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.rewrite.RewriteEngine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Per-dialect TPC-DS portable-subset output lock (spec §6 item 6). Regenerate
 * with -Dgolden.write=true and review the diff; PG goldens stay in
 * GoldenOutputTest and must not move.
 */
class MultiDialectGoldenOutputTest {

  private static final Path TPCDS_METADATA = Path.of("tpcds/metadata.yaml");
  private static final Path TPCDS_QUERIES_DIR = Path.of("tpcds/queries");

  /**
   * portable subset per dialect: files that rewrite cleanly under that
   * dialect. tpcds_common_cases.sql is trino-only (observation pin, not
   * forced): under mysql its statement [C3] selects a double-quoted
   * identifier ("c_email_address"), which the mysql parser (BACK_TICK
   * quoting + MYSQL_5 conformance) treats as a string literal and aborts
   * with "statement 3: parse error (mysql): Encountered \" c_email_address";
   * under trino and postgresql it rewrites cleanly.
   */
  private static final List<String> PORTABLE_TRINO = List.of(
      "tpcds_common_cases.sql", "tpcds_masking_test.sql",
      "tpcds_oneline.sql", "tpcds_crlf.sql", "tpcds_robustness.sql");

  private static final List<String> PORTABLE_MYSQL = List.of(
      "tpcds_masking_test.sql",
      "tpcds_oneline.sql", "tpcds_crlf.sql", "tpcds_robustness.sql");

  private final RewriteEngine engine = new RewriteEngine();

  @Test
  void tpcdsPortableSubsetMatchesGoldenPerDialect() throws Exception {
    String metadata = Files.readString(TPCDS_METADATA, StandardCharsets.UTF_8);
    for (String dialect : List.of("trino", "mysql")) {
      List<String> portable = "mysql".equals(dialect) ? PORTABLE_MYSQL : PORTABLE_TRINO;
      for (String file : portable) {
        Path golden = Path.of("src/test/resources/golden/tpcds-" + dialect + "-"
            + file.replace("tpcds_", ""));
        String actual;
        try {
          actual = engine.rewrite(metadata,
                  Files.readString(TPCDS_QUERIES_DIR.resolve(file), StandardCharsets.UTF_8),
                  dialect).stream()
              .map(s -> s.rewrittenSql() + ";")
              .reduce((a, b) -> a + "\n\n" + b)
              .orElse("");
        } catch (SqlMaskException e) {
          throw new IllegalStateException(
              file + " no longer rewrites under " + dialect
                  + "; move it out of the " + dialect + " PORTABLE list and record the failure: "
                  + e.getMessage(), e);
        }
        if (Boolean.getBoolean("golden.write")) {
          Files.createDirectories(golden.getParent());
          Files.writeString(golden, actual, StandardCharsets.UTF_8);
          continue;
        }
        assertEquals(normalize(Files.readString(golden, StandardCharsets.UTF_8)),
            normalize(actual), dialect + " / " + file);
      }
    }
  }

  @Test
  void pgClassifiedFailuresKeepFailingUnderOtherDialects() throws Exception {
    // 这些文件在 PG 下的失败分类（GoldenOutputTest）在 trino/mysql 不允许
    // 变成静默成功——重新分类的语句必须显式记录
    String metadata = Files.readString(TPCDS_METADATA, StandardCharsets.UTF_8);
    for (String dialect : List.of("trino", "mysql")) {
      assertThrows(SqlMaskException.class, () -> engine.rewrite(metadata,
          Files.readString(TPCDS_QUERIES_DIR.resolve("tpcds_edge_cases.sql"),
              StandardCharsets.UTF_8), dialect),
          "duplicate-alias refusal must hold for " + dialect);
      assertThrows(SqlMaskException.class, () -> engine.rewrite(metadata,
          Files.readString(TPCDS_QUERIES_DIR.resolve("tpcds_open_cases.sql"),
              StandardCharsets.UTF_8), dialect),
          "correlated-scalar-subquery refusal must hold for " + dialect);
    }
  }

  private static String normalize(String text) {
    return text.replace("\r\n", "\n");
  }
}
