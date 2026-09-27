package io.masklite;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线全量改写回归：旧仓库基准的 99 条 TPC-DS（vanilla PostgreSQL 方言）+
 * mask+rowfilter 合并策略（25 表、7 个脱敏列、customer/date_dim/
 * customer_address 三个行过滤表）。历史基准结论为 95/99（q05/q09/q72/q80
 * 受方言限制失败）；本模块修复 PG 的 concat 可变参语义、date±integer 与
 * 标量子查询输出的血缘判定后，全量 99/99 改写成功——以 99 为硬性底线。
 */
class TpcdsOfflineRewriteTest {

  private static final Path QUERIES_DIR = resourceDir("tpcds/queries");
  private static final String CONFIG = resourceText("tpcds/configs/tpcds-both.yaml");

  private static int successCount;
  private static final Map<String, String> FAILURES = new TreeMap<>();
  private static int maskedCount;
  private static int rowFilteredCount;
  private static final List<String> MASKED_AND_FILTERED = new ArrayList<>();

  @BeforeAll
  static void rewriteCorpus() throws Exception {
    MaskLite mask = MaskLite.fromYaml(CONFIG);
    try (Stream<Path> queries = Files.list(QUERIES_DIR)) {
      for (Path query : queries.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
        String name = query.getFileName().toString();
        String sql = Files.readString(query);
        try {
          List<MaskLite.StatementRewrite> statements = mask.rewriteStatements(sql);
          successCount++;
          boolean masked = statements.stream().anyMatch(MaskLite.StatementRewrite::masked);
          boolean filtered = statements.stream().anyMatch(MaskLite.StatementRewrite::rowFiltered);
          if (masked) {
            maskedCount++;
          }
          if (filtered) {
            rowFilteredCount++;
          }
          if (masked && filtered) {
            MASKED_AND_FILTERED.add(name);
          }
        } catch (Exception e) {
          FAILURES.put(name, String.valueOf(e.getMessage()));
        }
      }
    }
    System.out.printf("TPC-DS offline rewrite: %d/99 success, %d masked, %d rowFiltered%n",
        successCount, maskedCount, rowFilteredCount);
    FAILURES.forEach((name, message) -> System.out.println("  FAIL " + name + ": " + message));
    System.out.println("  masked+filtered: " + MASKED_AND_FILTERED);
  }

  @Test
  void allQueriesRewrite() {
    assertTrue(successCount == 99, "expected 99/99 rewrites, got " + successCount);
  }

  @Test
  void maskingAndRowFilterBothTakeEffect() {
    assertTrue(maskedCount > 0, "no statement got a masking wrapper");
    assertTrue(rowFilteredCount > 0, "no statement got a row-filter injection");
    assertTrue(MASKED_AND_FILTERED.size() > 0,
        "no statement combines masking and row filtering");
  }

  private static Path resourceDir(String name) {
    URL url = TpcdsOfflineRewriteTest.class.getClassLoader().getResource(name);
    if (url == null) {
      throw new IllegalStateException("missing test resource " + name);
    }
    try {
      return Path.of(url.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String resourceText(String name) {
    try {
      return Files.readString(Path.of(
          TpcdsOfflineRewriteTest.class.getClassLoader().getResource(name).toURI()));
    } catch (IOException | URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
