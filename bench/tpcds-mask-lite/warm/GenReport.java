import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the per-query benchmark report: report/cases/qNN.md for every
 * TPC-DS query (original SQL, rewritten SQL in the three modes, verdicts,
 * timings, problems) plus report/CASES.md as the index table. Inputs are the
 * bench's own artifacts under results/. Authored content (problem analysis)
 * lives in the PROBLEMS map below; everything else is read from disk.
 */
public final class GenReport {

  /** qid → authored problem note, rendered into the per-query page. */
  private static final Map<String, String[]> PROBLEMS = new LinkedHashMap<>();

  static {
    PROBLEMS.put("q05", new String[]{
        "P1 · VALIDATION_ERROR：`concat('store', s_store_id)` 无法校验",
        "Calcite 标准函数表里 CONCAT 只是 `||` 的中缀形式；mask-lite 的 PostgreSQL 函数表"
            + "没有注册 PG 语义的 variadic `concat(VARIADIC text)`，类型推导找不到"
            + " `CONCAT(<CHAR>,<CHAR>)` 签名即报错（fail-closed，不静默放行）。",
        "在 PostgresqlFunctions 注册 PG 语义的 variadic CONCAT（或把 concat(a,b) 归一为 a || b）；"
            + "短期规避：用户 SQL 改写为 `||` 拼接。"});
    PROBLEMS.put("q09", new String[]{
        "P2 · LINEAGE_UNKNOWN：投影含标量子查询（bucket1..bucket5）",
        "输出列直接由 `(SELECT avg(...) ...)` 标量子查询构成，无法证明其输出列与被脱敏列的"
            + "血缘关系——按 v1 安全立场显式拒绝改写，绝不静默放行（这是设计行为，不是缺陷）。",
        "短期：把标量子查询移入 CTE 再 JOIN（重写 SQL 即可通过）；"
            + "长期：血缘分析器支持标量子查询内层列追溯后再放开。"});
    PROBLEMS.put("q72", new String[]{
        "P3 · VALIDATION_ERROR：`d1.d_date + 5`（DATE + INTEGER）",
        "PostgreSQL 允许 date + integer（加天数），Calcite 标准类型规则只认"
            + " DATETIME + INTERVAL，类型检查直接拒绝；语料中该行还留有"
            + " “SQL Server: DATEADD” 注释，属多引擎方言差异长尾。",
        "在 PG adapter 注册 `DATETIME + INTEGER` 的隐式转换（PG 语义 = 加 N 天）；"
            + "短期规避：用户 SQL 改写为 `d1.d_date + INTERVAL '5' DAY`。"});
    PROBLEMS.put("q80", new String[]{
        "P1 · VALIDATION_ERROR：`concat('store', store_id)` 无法校验（与 q05 同根因）",
        "TPC-DS q80 的 channel 统计同样使用 `concat()`，撞上同一个函数表缺口——"
            + "失败集合收敛为:concat×2（q05/q80）、DATE+INTEGER×1（q72）、"
            + "标量子查询血缘×1（q09，设计行为）。早期曾出现"
            + " NoClassDefFoundError: SetopOperandTypeChecker$1，是上次会话中断留下的坏 fat jar，"
            + "重建后消失，与内核无关。",
        "同 P1:注册 variadic CONCAT 或归一为 `||`；发布前做干净构建 + 产物完整性冒烟。"});
    PROBLEMS.put("q66", new String[]{
        "P4 · 语料缺陷（已修复）：归一化脚本残留孤儿 WITH",
        "normalize.awk 判断主查询体的正则 `/^SELECT/` 大小写敏感，q66 主查询用小写"
            + " `select`，导致 7 个包装 CTE 全部删除后 `keptCte` 误标，留下孤 `WITH`，"
            + "任何解析器都必然 PARSE_ERROR。已改大小写不敏感并重新生成（仅 q66 变化）。",
        "已修复：`[Ss][Ee][Ll][Ee][Cc][Tt]`；教训：corpus 生成脚本要有 round-trip 校验。"});
    PROBLEMS.put("q79", new String[]{
        "P5 · lite 与 core 产物分歧：合成列名策略，core 产物不可移植（lite 更优）",
        "未起别名的输出列，core 沿用 Calcite 合成名 `EXPR$2` 并在包装层引用 `r.\"EXPR$2\"`——"
            + "该名字是解析器的私有约定，DuckDB 实测 Binder 直接拒绝（Values list \"r\" does not"
            + " have a column named \"EXPR$2\"），真实 PostgreSQL 同样不保证该名字存在；"
            + "lite 按其 README 策略改名为 `mask_col_1` 并写进派生表别名表，产物可执行"
            + "（DuckDB 对拍 84→73 行，both 判定通过）。",
        "建议把 core 的合成列命名对齐 lite 的 mask_col_N 策略；对外发布口径以 lite 为准。"});
  }

  private static final String[] MODES = {"mask", "rowfilter", "both"};
  private static final String[] MODE_NAMES = {"仅脱敏", "仅行过滤", "脱敏+行过滤"};

  private record Cell(int code, String ms, String out, String err, String errcode) {
  }

  public static void main(String[] args) throws Exception {
    Path raw = Path.of(args.length > 0 ? args[0] : "results/raw");
    Path outRoot = Path.of(args.length > 1 ? args[1] : "report");
    String engine = args.length > 2 ? args[2] : "lite";

    List<String> qids = new ArrayList<>();
    try (var s = Files.list(raw.resolve(engine).resolve("mask"))) {
      s.filter(p -> p.getFileName().toString().endsWith(".out"))
          .map(p -> p.getFileName().toString().replace(".out", "")).sorted().forEach(qids::add);
    }

    Map<String, Map<String, String>> warm = loadWarm(Path.of("results"));
    Map<String, Map<String, String>> semantic = loadSemantic();
    Map<String, String> msCold = loadColdMs(raw, engine);

    Files.createDirectories(outRoot.resolve("cases"));
    List<String> index = new ArrayList<>();
    index.add("| 查询 | 仅脱敏 | 仅行过滤 | 脱敏+行过滤 | 语义对拍 | 热身p50(mask/rowfilter/both) | 问题 |");
    index.add("|---|---|---|---|---|---|---|");

    int[] counts = new int[4];
    for (String qid : qids) {
      Map<String, Cell> cells = new LinkedHashMap<>();
      for (String mode : MODES) {
        cells.put(mode, readCell(raw, engine, mode, qid));
      }
      boolean allOk = cells.values().stream().allMatch(c -> c.code() == 0);
      if (allOk) {
        counts[0]++;
      }
      String page = renderQuery(qid, cells, warm, semantic, msCold, engine);
      Files.writeString(outRoot.resolve("cases").resolve(qid + ".md"), page, StandardCharsets.UTF_8);

      String sem = semantic.getOrDefault(qid, new HashMap<>()).getOrDefault(engine + "/both", "");
      String wm = warmP50(warm, qid);
      String problems = problemsFor(qid);
      index.add("| " + linkQid(qid) + " | " + mark(cells.get("mask").code()) + " | "
          + mark(cells.get("rowfilter").code()) + " | " + mark(cells.get("both").code())
          + " | " + sem + " | " + wm + " | " + problems + " |");
    }

    StringBuilder indexDoc = new StringBuilder();
    indexDoc.append("# 逐查询索引（").append(qids.size()).append(" 条 TPC-DS 查询 × ")
        .append(engine).append("）\n\n");
    indexDoc.append("每行的三个 ✓/✗ 对应三种模式的改写是否成功；")
        .append("语义对拍列为 DuckDB 实测判定（both 模式）；")
        .append("热身 p50 为同 JVM 交错测量（详见 REPORT.md）。单查询详情见 cases/qNN.md。\n\n");
    indexDoc.append(String.join("\n", index)).append('\n');
    Files.writeString(outRoot.resolve("CASES.md"), indexDoc, StandardCharsets.UTF_8);
    System.out.println("ok=" + counts[0] + " queries=" + qids.size());
    System.out.println("wrote " + outRoot.resolve("CASES.md") + " and " + qids.size() + " case pages");
  }

  // ------------------------------------------------------------------ render

  private static String renderQuery(String qid, Map<String, Cell> cells,
      Map<String, Map<String, String>> warm, Map<String, Map<String, String>> semantic,
      Map<String, String> msCold, String engine) throws Exception {
    StringBuilder b = new StringBuilder();
    b.append("# ").append(qid).append(" — TPC-DS q").append(qid.substring(1)).append('\n');
    b.append('\n').append("结果概览（引擎 ").append(engine).append("）：\n\n");
    b.append("| 模式 | 改写 | 退出码 | 错误码 | 冷耗时(ms) | 热身p50(ms) | 语义对拍 |\n");
    b.append("|---|---|---|---|---|---|---|\n");
    for (int i = 0; i < MODES.length; i++) {
      String mode = MODES[i];
      Cell cell = cells.get(mode);
      String warmP50 = warmP50For(warm, qid, mode);
      String sem = semantic.getOrDefault(qid, new HashMap<>()).getOrDefault(engine + "/" + mode, "");
      b.append("| ").append(MODE_NAMES[i]).append(" | ")
          .append(cell.code() == 0 ? "✓" : "✗").append(" | ")
          .append(cell.code()).append(" | ")
          .append(cell.errcode().isEmpty() ? "—" : cell.errcode()).append(" | ")
          .append(msCold.getOrDefault(qid + "/" + mode, "—")).append(" | ")
          .append(warmP50).append(" | ")
          .append(sem.isEmpty() ? "—" : sem).append(" |\n");
    }
    b.append("\n## 原始 SQL\n\n```sql\n")
        .append(Files.readString(Path.of("corpus", "queries", qid + ".sql"), StandardCharsets.UTF_8)
            .trim())
        .append("\n```\n\n");
    for (int i = 0; i < MODES.length; i++) {
      String mode = MODES[i];
      Cell cell = cells.get(mode);
      b.append("## 改写产物 — ").append(MODE_NAMES[i]).append("\n\n");
      if (cell.code() == 0) {
        String out = cell.out().trim();
        boolean wrapper = out.startsWith("SELECT mask_");
        boolean injected = out.contains("WHERE ca_country = 'United States'")
            || out.contains("WHERE d_year <= 2002")
            || out.contains("WHERE c_birth_year >= 1930");
        if (wrapper) {
          b.append("命中脱敏策略，输出 UDF 包装层");
          b.append(injected ? "，且注入了行过滤谓词：\n\n" : "：\n\n");
        } else if (injected) {
          b.append("未命中脱敏策略，注入了行过滤谓词：\n\n");
        } else {
          b.append("未命中任何策略（解析后规范化快照，无包装/注入）：\n\n");
        }
        b.append("```sql\n").append(out).append("\n```\n\n");
      } else {
        b.append("改写失败（fail-closed，不产出 SQL）。\n\n```\n")
            .append(cell.err().trim()).append("\n```\n\n");
      }
    }
    String[] problem = PROBLEMS.get(qid);
    b.append("## 问题与解决思路\n\n");
    if (problem == null) {
      b.append("三种模式均成功改写，无策略/方言相关问题。")
          .append("共性说明见 REPORT.md「已知限制」：注释与原始排版不保真（内层为")
          .append("解析后快照）、`LIMIT` 规范化为 `FETCH NEXT … ROWS ONLY`、")
          .append("大小写折叠为小写等。\n");
    } else {
      b.append("**").append(problem[0]).append("**\n\n").append(problem[1]).append("\n\n")
          .append("**解决思路：** ").append(problem[2]).append('\n');
    }
    return b.toString();
  }

  private static String mark(int code) {
    return code == 0 ? "✓" : "✗";
  }

  private static String problemsFor(String qid) {
    String[] problem = PROBLEMS.get(qid);
    if (problem == null) {
      return "";
    }
    String shortTitle = problem[0].split("·")[1].split("：")[0].trim();
    return shortTitle.replaceAll("\\s*VALIDATION_ERROR", "VALIDATION_ERROR");
  }

  private static String linkQid(String qid) {
    return "[q" + qid.substring(1) + "](cases/" + qid + ".md)";
  }

  private static String warmP50(Map<String, Map<String, String>> warm, String qid) {
    List<String> parts = new ArrayList<>();
    for (String mode : MODES) {
      parts.add(warmP50For(warm, qid, mode));
    }
    return String.join(" / ", parts);
  }

  private static String warmP50For(Map<String, Map<String, String>> warm, String qid, String mode) {
    String v = warm.getOrDefault(mode, new HashMap<>()).get(qid);
    return v == null ? "—" : v;
  }

  // ------------------------------------------------------------------- input

  private static Cell readCell(Path raw, String engine, String mode, String qid) throws Exception {
    Path base = raw.resolve(engine).resolve(mode).resolve(qid);
    int code = Integer.parseInt(Files.readString(base.resolveSibling(qid + ".code")).trim());
    String ms = Files.readString(base.resolveSibling(qid + ".ms")).trim();
    String out = code == 0 ? Files.readString(base.resolveSibling(qid + ".out")) : "";
    String err = code == 0 ? "" : Files.readString(base.resolveSibling(qid + ".err"));
    String errcode = code == 0 ? "" : firstErrcode(err);
    return new Cell(code, ms, out, err, errcode);
  }

  private static String firstErrcode(String err) {
    for (String code : new String[]{"CONFIG_ERROR", "PARSE_ERROR", "VALIDATION_ERROR",
        "UNSUPPORTED_STATEMENT", "LINEAGE_UNKNOWN", "REWRITE_ERROR", "IO_ERROR",
        "INTERNAL_ERROR"}) {
      if (err.contains(code)) {
        return code;
      }
    }
    return "UNKNOWN";
  }

  private static Map<String, Map<String, String>> loadWarm(Path resultsDir) throws Exception {
    Map<String, Map<String, String>> warm = new HashMap<>();
    for (String mode : MODES) {
      Path file = resultsDir.resolve("warm-" + mode + ".tsv");
      if (!Files.exists(file)) {
        continue;
      }
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (line.startsWith("#") || line.isBlank()) {
          continue;
        }
        String[] parts = line.split("\t");
        if (parts.length >= 6 && "true".equals(parts[1])) {
          warm.computeIfAbsent(mode, k -> new HashMap<>()).put(parts[0], parts[4]);
        }
      }
    }
    return warm;
  }

  private static Map<String, Map<String, String>> loadSemantic() throws Exception {
    Map<String, Map<String, String>> semantic = new HashMap<>();
    for (String engine : new String[]{"lite", "core"}) {
      for (String mode : MODES) {
        Path file = Path.of("results", "semantic-" + engine + "-" + mode + ".tsv");
        if (!Files.exists(file)) {
          continue;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
          if (line.startsWith("#") || line.isBlank()) {
            continue;
          }
          String[] parts = line.split("\t", 5);
          if (parts.length >= 2) {
            semantic.computeIfAbsent(parts[0], k -> new HashMap<>())
                .put(engine + "/" + mode, parts[1]);
          }
        }
      }
    }
    return semantic;
  }

  private static Map<String, String> loadColdMs(Path raw, String engine) throws Exception {
    Map<String, String> ms = new HashMap<>();
    for (String mode : MODES) {
      Path dir = raw.resolve(engine).resolve(mode);
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (var s = Files.list(dir)) {
        for (Path file : s.filter(p -> p.getFileName().toString().endsWith(".ms")).toList()) {
          String qid = file.getFileName().toString().replace(".ms", "");
          ms.put(qid + "/" + mode, Files.readString(file).trim());
        }
      }
    }
    return ms;
  }
}
