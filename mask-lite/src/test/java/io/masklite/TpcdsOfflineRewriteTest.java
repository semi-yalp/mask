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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
  private static final List<String> MASKED = new ArrayList<>();
  private static final List<String> ROW_FILTERED = new ArrayList<>();
  private static final Map<String, String> REWRITTEN_SAMPLE = new TreeMap<>();

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
          if (statements.stream().anyMatch(MaskLite.StatementRewrite::masked)) {
            MASKED.add(name);
          }
          if (statements.stream().anyMatch(MaskLite.StatementRewrite::rowFiltered)) {
            ROW_FILTERED.add(name);
          }
          statements.stream()
              .filter(s -> s.masked() || s.rowFiltered())
              .findFirst()
              .ifPresent(s -> REWRITTEN_SAMPLE.put(name, s.rewrittenSql()));
        } catch (Exception e) {
          FAILURES.put(name, String.valueOf(e.getMessage()));
        }
      }
    }
    System.out.printf("TPC-DS offline rewrite: %d/99 success, %d masked, %d rowFiltered%n",
        successCount, MASKED.size(), ROW_FILTERED.size());
    FAILURES.forEach((name, message) -> System.out.println("  FAIL " + name + ": " + message));
    System.out.println("  masked: " + MASKED);
    System.out.println("  rowFiltered: " + ROW_FILTERED);
  }

  /**
   * 2026-09-28 实测位图（review 后锁定）：脱敏面 = 15 条（7 个绑定列在语料
   * 投影中的命中），行过滤面 = 91 条（customer/date_dim/customer_address
   * 三表被引用即注入），二者交集恰为全部 15 条 masked。任何一条查询的
   * masked/rowFiltered 翻转都意味着策略匹配或注入行为变化。
   */
  private static final List<String> EXPECTED_MASKED = List.of(
      "q01.sql", "q04.sql", "q11.sql", "q23.sql", "q24.sql", "q30.sql", "q34.sql",
      "q46.sql", "q64.sql", "q68.sql", "q73.sql", "q74.sql", "q79.sql", "q81.sql",
      "q84.sql");

  private static final List<String> EXPECTED_ROW_FILTERED = List.of(
      "q01.sql", "q02.sql", "q03.sql", "q04.sql", "q05.sql", "q06.sql", "q07.sql",
      "q08.sql", "q10.sql", "q11.sql", "q12.sql", "q13.sql", "q14.sql", "q15.sql",
      "q16.sql", "q17.sql", "q18.sql", "q19.sql", "q20.sql", "q21.sql", "q22.sql",
      "q23.sql", "q24.sql", "q25.sql", "q26.sql", "q27.sql", "q29.sql", "q30.sql",
      "q31.sql", "q32.sql", "q33.sql", "q34.sql", "q35.sql", "q36.sql", "q37.sql",
      "q38.sql", "q39.sql", "q40.sql", "q42.sql", "q43.sql", "q45.sql", "q46.sql",
      "q47.sql", "q48.sql", "q49.sql", "q50.sql", "q51.sql", "q52.sql", "q53.sql",
      "q54.sql", "q55.sql", "q56.sql", "q57.sql", "q58.sql", "q59.sql", "q60.sql",
      "q61.sql", "q62.sql", "q63.sql", "q64.sql", "q65.sql", "q66.sql", "q67.sql",
      "q68.sql", "q69.sql", "q70.sql", "q71.sql", "q72.sql", "q73.sql", "q74.sql",
      "q75.sql", "q76.sql", "q77.sql", "q78.sql", "q79.sql", "q80.sql", "q81.sql",
      "q82.sql", "q83.sql", "q84.sql", "q85.sql", "q86.sql", "q87.sql", "q89.sql",
      "q91.sql", "q92.sql", "q94.sql", "q95.sql", "q97.sql", "q98.sql", "q99.sql");

  @Test
  void allQueriesRewrite() {
    assertTrue(successCount == 99, "expected 99/99 rewrites, got " + successCount);
  }

  @Test
  void maskedQueriesAreExactlyTheExpectedSet() {
    assertEquals(EXPECTED_MASKED, MASKED,
        "masked set changed: a policy match flipped (policy matching or "
            + "lineage resolution changed)");
  }

  @Test
  void rowFilteredQueriesAreExactlyTheExpectedSet() {
    assertEquals(EXPECTED_ROW_FILTERED, ROW_FILTERED,
        "row-filtered set changed: an injection flipped (filter matching or "
            + "derived-table injection changed)");
  }

  /** 改写成功不等于改写正确：脱敏查询的产物必须真的带着 UDF 包装。 */
  @Test
  void maskedQueryRewriteCarriesTheUdfWrapper() {
    String q01 = REWRITTEN_SAMPLE.get("q01.sql");
    assertTrue(q01 != null && q01.contains("mask_"),
        "q01 is masked, so its rewrite must call a mask_* UDF: " + q01);
  }

  /** 行过滤查询的产物必须嵌着过滤条件（改写不能丢注入）。 */
  @Test
  void rowFilteredQueryRewriteEmbedsTheFilter() {
    String q02 = REWRITTEN_SAMPLE.get("q02.sql");
    boolean embeds = q02 != null && (q02.contains("ca_country = 'United States'")
        || q02.contains("d_year <= 2002") || q02.contains("c_birth_year >= 1930"));
    assertTrue(embeds, "q02 is row-filtered, so its rewrite must embed a filter: " + q02);
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
