package io.masklite;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 远程 PG 集成验证（47.100.166.158 PostgreSQL 16 的 tpcds 库，sf=0.01 数据 +
 * mask UDF；本机经 {@code ssh -N -L 15432:127.0.0.1:5432 root@…} 隧道访问）。
 *
 * <p>门控：仅当环境变量 {@code MASK_LITE_REMOTE_PG=1} 时执行，否则跳过。
 * 流程：99 条 TPC-DS 离线改写 → 改写产物逐条在远程 PG 上执行 → 输出报告；
 * 另有脱敏效果（改写输出 ≡ 手工调用 mask UDF）与行过滤效果
 * （行过滤注入后的行数 = 手工 WHERE 行数）两组点验。
 */
class TpcdsRemotePgIT {

  private static final String URL = env("MASK_LITE_PG_URL",
      "jdbc:postgresql://127.0.0.1:15432/tpcds");
  private static final String USER = env("MASK_LITE_PG_USER", "postgres");
  private static final String PASSWORD = env("MASK_LITE_PG_PASSWORD", "PgTest2026");

  private static final Path QUERIES_DIR = queriesDir();

  private static Path queriesDir() {
    try {
      return Path.of(
          TpcdsRemotePgIT.class.getResource("/tpcds/queries/q01.sql").toURI()).getParent();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
  private static final String CONFIG = resourceText("/tpcds/configs/tpcds-both.yaml");

  private static final Map<String, String> REWRITE_FAILURES = new TreeMap<>();
  private static final Map<String, String> EXECUTE_FAILURES = new LinkedHashMap<>();

  static String env(String key, String fallback) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  static String resourceText(String name) {
    try {
      return Files.readString(Path.of(
          TpcdsRemotePgIT.class.getResource(name).toURI()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @BeforeAll
  static void gate() {
    Assumptions.assumeTrue("1".equals(System.getenv("MASK_LITE_REMOTE_PG")),
        "remote PG campaign gated behind MASK_LITE_REMOTE_PG=1");
  }

  @Test
  void campaignReport() throws Exception {
    MaskLite mask = MaskLite.fromYaml(CONFIG);
    List<String> rewrittenSql = new ArrayList<>();
    List<String> names = new ArrayList<>();
    int rewritten = 0;
    try (Stream<Path> queries = Files.list(QUERIES_DIR)) {
      for (Path query : queries.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
        String name = query.getFileName().toString();
        try {
          rewrittenSql.add(mask.rewrite(Files.readString(query)));
          names.add(name);
          rewritten++;
        } catch (Exception e) {
          REWRITE_FAILURES.put(name, String.valueOf(e.getMessage()));
        }
      }
    }

    Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
    connection.setReadOnly(true);
    connection.setAutoCommit(false);
    try (Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(180);
      for (int i = 0; i < rewrittenSql.size(); i++) {
        String name = names.get(i);
        try (ResultSet rs = statement.executeQuery(rewrittenSql.get(i))) {
          rs.next(); // the rewritten query must be executable and consumable
        } catch (Exception e) {
          // clear the aborted transaction so later statements still run
          connection.rollback();
          EXECUTE_FAILURES.put(name, e.getMessage());
        }
      }
    } finally {
      connection.rollback();
      connection.close();
    }

    System.out.printf("TPC-DS remote campaign: rewritten %d/99, executed ok %d, failed %d%n",
        rewritten, rewrittenSql.size() - EXECUTE_FAILURES.size(), EXECUTE_FAILURES.size());
    REWRITE_FAILURES.forEach((name, message) -> System.out.println("  REWRITE_FAIL " + name + ": " + message));
    EXECUTE_FAILURES.forEach((name, message) -> System.out.println("  EXECUTE_FAIL " + name + ": " + message));

    assertTrue(rewritten >= 95, "expected >= 95/99 rewrites, got " + rewritten);

    // q70/q86 在 ORDER BY 表达式里引用输出别名（lochierarchy）——PG 严格禁止，
    // DuckDB 宽松。这是语料本身与 PG 的不兼容：原始查询必须报同样的错
    // （改写没有引入任何新的执行失败）。
    for (String expected : new String[] {"q70.sql", "q86.sql"}) {
      if (EXECUTE_FAILURES.containsKey(expected)) {
        assertTrue(EXECUTE_FAILURES.get(expected).contains("lochierarchy"),
            expected + " must fail for the documented pre-existing reason");
        String originalError = executeOriginal(expected);
        assertTrue(originalError.contains("lochierarchy"),
            expected + " original must fail on PG too, got: " + originalError);
        System.out.println("  expected fail " + expected
            + " (original fails identically on PG)");
      }
    }
    var unexpected = new TreeMap<>(EXECUTE_FAILURES);
    unexpected.keySet().removeAll(List.of("q70.sql", "q86.sql"));
    assertTrue(unexpected.isEmpty(),
        "rewritten SQL must execute on remote PG; unexpected failures: " + unexpected.keySet());
  }

  /** Executes the ORIGINAL corpus query; returns its error message or "" on success. */
  private String executeOriginal(String name) throws Exception {
    String sql = Files.readString(QUERIES_DIR.resolve(name));
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
         Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(180);
      try (ResultSet rs = statement.executeQuery(sql)) {
        rs.next();
        return "";
      } catch (Exception e) {
        return e.getMessage();
      }
    }
  }

  @Test
  void rowFilterReducesRowsExactlyLikeTheManualFilter() throws Exception {
    MaskLite mask = MaskLite.fromYaml(CONFIG);
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
         Statement statement = connection.createStatement()) {
      for (String[] spec : new String[][] {
          {"customer_address", "ca_country = 'United States'"},
          {"date_dim", "d_year <= 2002"}}) {
        String table = spec[0];
        String filter = spec[1];
        long total = count(statement, "SELECT count(*) FROM " + table);
        long manual = count(statement,
            "SELECT count(*) FROM " + table + " WHERE " + filter);
        String rewritten = mask.rewrite("SELECT count(*) FROM " + table);
        assertTrue(rewritten.contains("WHERE " + filter),
            table + " rewritten SQL should embed the filter:\n" + rewritten);
        long filtered = count(statement, rewritten);
        assertEquals(manual, filtered,
            table + ": injected row filter must match the manual WHERE count");
        assertTrue(filtered < total,
            table + ": row filter should reduce rows (total=" + total + ")");
        System.out.printf("rowfilter %s: total=%d manual=%d injected=%d%n",
            table, total, manual, filtered);
      }
    }
  }

  @Test
  void maskedOutputEqualsManuallyAppliedUdf() throws Exception {
    MaskLite mask = MaskLite.fromYaml(CONFIG);
    String query = "SELECT c_email_address, c_last_name FROM customer "
        + "ORDER BY c_customer_sk LIMIT 5";
    String rewritten = mask.rewrite(query);
    assertTrue(rewritten.contains("mask_email("));
    try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
         Statement statement = connection.createStatement()) {
      List<String> viaRewrite = twoColumns(statement, rewritten);
      List<String> viaManualUdf = twoColumns(statement,
          "SELECT mask_email(c_email_address), mask_name(c_last_name) FROM customer "
              + "ORDER BY c_customer_sk LIMIT 5");
      assertEquals(viaManualUdf, viaRewrite,
          "rewritten output must equal manual mask UDF application");
      System.out.println("masked sample: " + viaRewrite.get(0));
      assertTrue(viaRewrite.get(0).contains("*"),
          "masked output should carry masking asterisks");
    }
  }

  private static long count(Statement statement, String sql) throws Exception {
    try (ResultSet rs = statement.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static List<String> twoColumns(Statement statement, String sql) throws Exception {
    List<String> rows = new ArrayList<>();
    try (ResultSet rs = statement.executeQuery(sql)) {
      while (rs.next()) {
        rows.add(rs.getString(1) + "|" + rs.getString(2));
      }
    }
    return rows;
  }
}
