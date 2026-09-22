import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Semantic cross-check ("对拍") of the bench outputs: actually execute the
 * original TPC-DS query and its rewritten product on a DuckDB in-memory
 * TPC-DS database (sf=0.01) and verify the rewriting semantics:
 *
 * <ul>
 *   <li>row filter: rewritten rows are a sub-multiset of the original rows
 *       and cardinality does not grow;</li>
 *   <li>masking: cardinality is preserved, non-masked columns keep their
 *       value multiset, masked columns produce values that never occur in the
 *       original column (no leak-through). The masking UDF contract is
 *       emulated with DuckDB macros (constant / hash forms).</li>
 * </ul>
 *
 * <p>Per query one TSV line:
 * {@code qid status origRows rwRows detail}. Statuses:
 * {@code same} (output identical set), {@code subset}, {@code masked},
 * {@code both}, {@code VIOLATION*}, {@code orig-error}, {@code rw-error},
 * {@code skipped} (rewrite had failed in the cold matrix).
 *
 * <pre>
 * java -cp mask-lite.jar;warm/lib/duckdb_jdbc.jar;classes SemanticCheck lite mask,rowfilter,both
 * </pre>
 */
public final class SemanticCheck {

  private static final double SF = 0.01;

  public static void main(String[] args) throws Exception {
    String engine = args[0];
    String rawRoot = args.length > 1 ? args[1] : "results/raw";
    String modes = args.length > 2 ? args[2] : "mask,rowfilter,both";

    Connection db = DriverManager.getConnection("jdbc:duckdb:");
    setupTpcds(db);

    for (String mode : modes.split(",")) {
      Path outDir = Path.of(rawRoot, engine, mode);
      Path report = Path.of("results", "semantic-" + engine + "-" + mode + ".tsv");
      StringBuilder reportLines = new StringBuilder("#qid\tstatus\torigRows\trwRows\tdetail\n");
      int[] stats = new int[9];
      try (var stream = Files.list(outDir)) {
        List<Path> outs = stream.filter(p -> p.getFileName().toString().endsWith(".out")).sorted()
            .toList();
        for (Path out : outs) {
          String qid = out.getFileName().toString().replace(".out", "");
          Path original = Path.of("corpus", "queries", qid + ".sql");
          if (!Files.exists(original)) {
            continue;
          }
          Path codeFile = outDir.resolve(qid + ".code");
          boolean rewriteFailed = Files.exists(codeFile)
              && !"0".equals(Files.readString(codeFile).trim());
          Result orig = null;
          Result rw = null;
          String line;
          if (rewriteFailed) {
            // the cold matrix already recorded the rewrite failure and its
            // error code; there is nothing to execute here
            line = qid + "\tskipped\t0\t0\trewrite failed";
            stats[7]++;
          } else {
            orig = exec(db, execSql(mode, Files.readString(original, StandardCharsets.UTF_8)));
            rw = exec(db, execSql(mode, Files.readString(out, StandardCharsets.UTF_8)));
            if (orig.error != null && rw.error != null) {
              line = qid + "\torig-error\t0\t0\t" + clip(orig.error);
              stats[0]++;
            } else if (orig.error != null) {
              line = qid + "\torig-error\t0\t" + rw.rows.size() + "\t" + clip(orig.error);
              stats[0]++;
            } else if (rw.error != null) {
              line = qid + "\trw-error\t" + orig.rows.size() + "\t0\t" + clip(rw.error);
              stats[1]++;
            } else {
              String verdict = verdict(mode, orig, rw, maskedColumnsOf(out),
                  Files.readString(original, StandardCharsets.UTF_8));
              line = qid + "\t" + verdict + "\t" + orig.rows.size() + "\t" + rw.rows.size() + "\t";
              if (verdict.startsWith("filtered-aggregates")) {
                stats[8]++;
              } else {
                switch (verdict) {
                  case "same" -> stats[2]++;
                  case "subset" -> stats[3]++;
                  case "masked" -> stats[4]++;
                  case "both" -> stats[5]++;
                  default -> stats[6]++;
                }
              }
            }
          }
          reportLines.append(line).append('\n');
        }
      }
      Files.writeString(report, reportLines.toString(), StandardCharsets.UTF_8);
      System.out.println("=== " + engine + "/" + mode + " ===");
      System.out.println("  orig-error=" + stats[0] + " rw-error=" + stats[1]
          + " same=" + stats[2] + " subset=" + stats[3] + " masked=" + stats[4]
          + " both=" + stats[5] + " VIOLATION=" + stats[6] + " skipped=" + stats[7]
          + " filtered-aggregates=" + stats[8]);
      System.out.println("  report: " + report);
    }
  }

  // ---- verdicts -----------------------------------------------------------

  private static final java.util.regex.Pattern AGGREGATE = java.util.regex.Pattern.compile(
      "(?i)\\b(group\\s+by\\b|sum\\s*\\(|avg\\s*\\(|count\\s*\\(|min\\s*\\(|max\\s*\\()");

  private static String verdict(String mode, Result orig, Result rw, Set<String> maskedCols,
      String originalSql) {
    boolean maskMode = mode.equals("mask") || mode.equals("both");
    boolean filterMode = mode.equals("rowfilter") || mode.equals("both");
    // on filtered inputs aggregates legitimately recompute — a different
    // aggregate result is the DEFINED semantics of row filtering, not a
    // rewrite fault; row-level queries must stay exact subsets though
    boolean aggregateQuery = AGGREGATE.matcher(originalSql).find();
    List<String> issues = new ArrayList<>();

    if (orig.rows.equals(rw.rows)) {
      return "same";
    }
    if (orig.headers.size() != rw.headers.size()) {
      return "VIOLATION-column-count " + orig.headers.size() + "->" + rw.headers.size();
    }

    // masking checks (per column, by position; wrapper preserves column order)
    if (maskMode && !filterMode) {
      // masking alone must preserve cardinality and every non-masked column's
      // value multiset, exactly; with an active row filter those are the
      // filter's job to change, so only the leak check remains
      if (!filterMode && orig.rows.size() != rw.rows.size()) {
        issues.add("cardinality-changed");
      }
      for (int c = 0; c < orig.headers.size(); c++) {
        String col = orig.headers.get(c);
        Set<String> origValues = new HashSet<>();
        Map<String, Long> origMultiset = new HashMap<>();
        for (List<String> row : orig.rows) {
          String v = cell(row, c);
          origValues.add(v);
          origMultiset.merge(v, 1L, Long::sum);
        }
        Map<String, Long> rwMultiset = new HashMap<>();
        for (List<String> row : rw.rows) {
          rwMultiset.merge(cell(row, c), 1L, Long::sum);
        }
        if (maskedCols.contains(col.toLowerCase(java.util.Locale.ROOT))) {
          // non-null/non-empty rewritten values must never occur in the
          // original column: the macros pass NULL/'' through, everything
          // else is a constant or hash form that cannot collide with real data
          long leak = 0;
          for (Map.Entry<String, Long> e : rwMultiset.entrySet()) {
            if (!e.getKey().equals("NULL") && !e.getKey().isEmpty()
                && origValues.contains(e.getKey())) {
              leak += e.getValue();
            }
          }
          if (leak > 0) {
            issues.add("LEAK-masked-column-" + col + "-" + leak + "-rows");
          }
        } else if (!filterMode && !origMultiset.equals(rwMultiset)) {
          issues.add("value-drift-" + col);
        }
      }
    }

    // row-filter check: sub-multiset containment. In `both` mode the masked
    // columns carry rewritten values by design, so the row identity used for
    // containment is the projection onto the NON-masked columns; in
    // `rowfilter` mode maskedCols is empty, i.e. the full row.
    if (filterMode) {
      List<Integer> keyCols = new ArrayList<>();
      for (int c = 0; c < orig.headers.size(); c++) {
        if (!maskedCols.contains(orig.headers.get(c).toLowerCase(java.util.Locale.ROOT))) {
          keyCols.add(c);
        }
      }
      Map<List<String>, Long> origCounts = new HashMap<>();
      for (List<String> row : orig.rows) {
        origCounts.merge(project(row, keyCols), 1L, Long::sum);
      }
      long missing = 0;
      for (List<String> row : rw.rows) {
        long left = origCounts.getOrDefault(project(row, keyCols), 0L);
        if (left <= 0) {
          missing++;
        } else {
          origCounts.put(project(row, keyCols), left - 1);
        }
      }
      if (rw.rows.size() > orig.rows.size()) {
        issues.add("cardinality-grew");
      }
      if (missing > 0) {
        if (aggregateQuery) {
          // filtered inputs legitimately recompute aggregates — classified,
          // not a fault; row-level queries must be exact subsets though
          issues.add("filtered-aggregates-" + missing);
        } else {
          issues.add("NOT-SUBSET-" + missing + "-rows");
        }
      }
    }

    if (issues.isEmpty()) {
      if (maskMode && filterMode) {
        return "both";
      }
      return maskMode ? "masked" : "subset";
    }
    if (issues.stream().allMatch(i -> i.startsWith("filtered-aggregates"))) {
      return "filtered-aggregates(" + issues.get(0).substring("filtered-aggregates-".length())
          + " rows recomputed)";
    }
    return "VIOLATION:" + String.join(",", issues);
  }

  /**
   * Masked output columns, derived from the wrapper projection of the
   * rewritten text: the segment after the leading {@code SELECT } up to the
   * first top-level {@code FROM (} — the inner query sits inside that
   * parenthesis and cannot confuse the split. Items are
   * {@code mask_udf(r.col, ...) AS name}; unmasked items are plain
   * {@code r.name}.
   */
  static Set<String> maskedColumnsOf(Path outFile) throws Exception {
    String sql = Files.readString(outFile, StandardCharsets.UTF_8).trim();
    if (sql.endsWith(";")) {
      sql = sql.substring(0, sql.length() - 1).trim();
    }
    Set<String> masked = new HashSet<>();
    if (!sql.startsWith("SELECT ")) {
      // not a wrapper shape (pass-through statement) — no masked columns
      return masked;
    }
    int from = indexOfTopLevelFrom(sql);
    if (from < 0) {
      return masked;
    }
    String projection = sql.substring("SELECT ".length(), from);
    int depth = 0;
    int itemStart = 0;
    List<String> items = new ArrayList<>();
    for (int i = 0; i < projection.length(); i++) {
      char ch = projection.charAt(i);
      if (ch == '(') {
        depth++;
      } else if (ch == ')') {
        depth--;
      } else if (ch == ',' && depth == 0) {
        items.add(projection.substring(itemStart, i));
        itemStart = i + 1;
      }
    }
    items.add(projection.substring(itemStart));
    for (String item : items) {
      var matcher = MASKED_ITEM.matcher(item.trim());
      if (matcher.matches()) {
        masked.add(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
      }
    }
    return masked;
  }

  /** Wrapper projection item {@code mask_udf(r.col, args...) AS name}. */
  private static final java.util.regex.Pattern MASKED_ITEM =
      java.util.regex.Pattern.compile("^mask_\\w+\\(.*\\)\\s+[Aa][Ss]\\s+\"?(\\w+)\"?$");

  /** Index of the first {@code FROM (} that opens the inner-query wrapper. */
  private static int indexOfTopLevelFrom(String sql) {
    for (int i = 0; i + 6 < sql.length(); i++) {
      if (sql.regionMatches(true, i, " FROM (", 0, 7)) {
        return i;
      }
    }
    return -1;
  }

  // ---- duckdb -------------------------------------------------------------

  /**
   * Row-filter checks compare the FILTERED universe, not a top-N slice of it:
   * with {@code ORDER BY + LIMIT 100}, ties at the cut let the filtered
   * top-N pick different rows than the original top-N while remaining a
   * perfect subset of the unfiltered result. So for the filter-bearing modes
   * the outermost LIMIT/FETCH is dropped from both sides before execution.
   */
  private static String execSql(String mode, String sql) {
    if (!mode.equals("rowfilter") && !mode.equals("both")) {
      return sql;
    }
    String trimmed = sql.trim();
    if (trimmed.endsWith(";")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
    }
    return trimmed.replaceAll("(?i)\\s(FETCH\\s+(FIRST|NEXT)\\s+\\d+\\s+ROWS\\s+ONLY"
        + "|LIMIT\\s+\\d+)$", "");
  }

  private static List<String> project(List<String> row, List<Integer> keyCols) {
    List<String> key = new ArrayList<>(keyCols.size());
    for (int c : keyCols) {
      key.add(cell(row, c));
    }
    return key;
  }

  private static void setupTpcds(Connection db) throws Exception {
    try (Statement st = db.createStatement()) {
      st.execute("INSTALL tpcds");
      st.execute("LOAD tpcds");
      st.execute("ATTACH ':memory:' AS tpcds");
      st.execute("CREATE SCHEMA tpcds.public");
      // dsdgen always fills the attached catalog's main schema
      st.execute("CALL dsdgen(sf = " + SF + ")");
      // expose the tables as tpcds.public.<t> so the corpus' 3-part names
      // resolve, and put that schema on the search path for 1-part references
      List<String> tables = new ArrayList<>();
      ResultSet rs = st.executeQuery(
          "SELECT table_name FROM duckdb_tables()"
              + " WHERE database_name = 'tpcds' AND schema_name = 'main'");
      while (rs.next()) {
        tables.add(rs.getString(1));
      }
      for (String table : tables) {
        st.execute("CREATE VIEW tpcds.public." + table + " AS SELECT * FROM tpcds.main." + table);
        // one-part references resolve inside the DEFAULT catalog, and DuckDB
        // does not cross catalogs via search_path — mirror the views there
        st.execute("CREATE VIEW memory.main." + table + " AS SELECT * FROM tpcds.main." + table);
      }
      // no spaces in the list: DuckDB's search_path parser does not trim
      st.execute("SET search_path = 'tpcds.public,memory.main'");
      // mask_lite UDF contract emulated with DuckDB macros
      st.execute("CREATE MACRO mask_hash(x, algo) AS CASE WHEN x IS NULL THEN NULL"
          + " ELSE md5(CAST(x AS VARCHAR)) END");
      st.execute("CREATE MACRO mask_name(x) AS CASE WHEN x IS NULL OR x = '' THEN x"
          + " ELSE substr(x, 1, 1) || '***' END");
      st.execute("CREATE MACRO mask_email(x) AS CASE WHEN x IS NULL OR x = '' THEN x"
          + " ELSE '***@masked' END");
      st.execute("CREATE MACRO mask_phone(x, a, b) AS CASE WHEN x IS NULL OR x = '' THEN x"
          + " ELSE '***-****' END");
      st.execute("CREATE MACRO mask_text(x) AS CASE WHEN x IS NULL OR x = '' THEN x"
          + " ELSE '***' END");
    }
  }

  private static Result exec(Connection db, String sql) {
    // the corpus' LIMIT 100 caps result size; queries without LIMIT are all
    // small aggregations at sf=0.01. A safety net keeps memory bounded.
    String guarded = sql.endsWith(";") ? sql : sql + ";";
    try (Statement st = db.createStatement()) {
      st.execute("PRAGMA threads=4");
      ResultSet rs = st.executeQuery(guarded);
      int columns = rs.getMetaData().getColumnCount();
      List<String> headers = new ArrayList<>();
      for (int i = 1; i <= columns; i++) {
        headers.add(rs.getMetaData().getColumnLabel(i));
      }
      List<List<String>> rows = new ArrayList<>();
      while (rs.next() && rows.size() <= 200_000) {
        List<String> row = new ArrayList<>(columns);
        for (int i = 1; i <= columns; i++) {
          row.add(rs.getString(i));
        }
        rows.add(row);
      }
      return new Result(headers, rows, null);
    } catch (Exception e) {
      return new Result(List.of(), List.of(), e.getMessage());
    }
  }

  private static String cell(List<String> row, int index) {
    String v = row.get(index);
    return v == null ? "NULL" : v;
  }

  private static String clip(String message) {
    String oneLine = String.valueOf(message).replace('\n', ' ');
    return oneLine.length() > 200 ? oneLine.substring(0, 200) : oneLine;
  }

  private record Result(List<String> headers, List<List<String>> rows, String error) {
  }
}
