package io.masklite;

import io.masklite.config.LoadedConfig;
import io.masklite.config.YamlConfigLoader;
import io.masklite.dialect.DialectRegistry;
import io.masklite.error.SqlMaskException;
import io.masklite.rewrite.RewriteEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * mask-lite 门面：脱敏 + 行过滤的最简嵌入入口，PostgreSQL 方言。
 *
 * <p>配置是 legacy 元数据 YAML（表结构 + {@code columns} 列策略绑定 +
 * {@code rowFilter} 行过滤 + {@code policies} UDF 声明），策略对所有人
 * 无条件生效；SQL 输入与输出同为 PostgreSQL 方言。引擎只做解析、校验、
 * 血缘分析与 SQL 输出，从不连接数据库。
 *
 * <pre>{@code
 * MaskLite mask = MaskLite.fromYamlFile(Path.of("metadata.yaml"));
 * String rewritten = mask.rewrite("SELECT c_phone FROM customer");
 * }</pre>
 *
 * <p>本类设计为可经 URLClassLoader 从自包含 shaded jar 加载后以反射调用
 * （宿主类路径上没有任何 io.sqlmask / io.masklite 依赖也能工作）。
 */
public final class MaskLite {

  /** 单条语句的改写结果。 */
  public record StatementRewrite(int ordinal, String originalSql, String rewrittenSql,
      boolean masked, boolean rowFiltered) {
  }

  private final RewriteEngine engine;
  private final LoadedConfig loaded;

  private MaskLite(LoadedConfig loaded) {
    this.loaded = loaded;
    this.engine = new RewriteEngine();
  }

  /** Loads and validates YAML configuration content; fails fast on errors. */
  public static MaskLite fromYaml(String metadataYaml) {
    return new MaskLite(
        new YamlConfigLoader().loadContent(metadataYaml, "metadata.yaml", DialectRegistry.POSTGRESQL));
  }

  /** Loads and validates a YAML configuration file; fails fast on errors. */
  public static MaskLite fromYamlFile(Path path) {
    return new MaskLite(new YamlConfigLoader().load(path, DialectRegistry.POSTGRESQL));
  }

  /** Rewrites every statement in {@code sqlText}; any failure aborts the whole run. */
  public List<StatementRewrite> rewriteStatements(String sqlText) {
    return engine.rewrite(loaded, sqlText).stream()
        .map(s -> new StatementRewrite(s.ordinal(), s.originalSql(), s.rewrittenSql(),
            s.masked(), s.rowFiltered()))
        .toList();
  }

  /** Rewrites and joins every statement into a single script (semicolon per statement). */
  public String rewrite(String sqlText) {
    return RewriteEngine.join(engine.rewrite(loaded, sqlText));
  }

  /** Loaded, validated configuration (read-only view). */
  public LoadedConfig config() {
    return loaded;
  }

  /**
   * Minimal CLI for smoke checks: {@code java -jar mask-lite.jar --metadata m.yaml
   * --sql 'SELECT ...'} (or {@code --input file}), rewritten SQL to stdout.
   * Exit codes: 0 ok, 1 rewrite failure, 2 usage error.
   */
  public static void main(String[] args) {
    Path metadata = null;
    String sql = null;
    Path input = null;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--metadata" -> metadata = Path.of(args[++i]);
        case "--sql" -> sql = args[++i];
        case "--input" -> input = Path.of(args[++i]);
        default -> {
          System.err.println("unknown argument: " + args[i]);
          System.exit(2);
        }
      }
    }
    if (metadata == null || (sql == null) == (input == null)) {
      System.err.println("usage: java -jar mask-lite.jar --metadata <yaml> (--sql <text> | --input <file>)");
      System.exit(2);
    }
    try {
      MaskLite mask = MaskLite.fromYamlFile(metadata);
      String text = sql != null ? sql : Files.readString(input);
      List<StatementRewrite> statements = mask.rewriteStatements(text);
      for (StatementRewrite statement : statements) {
        System.out.println(statement.rewrittenSql() + ";");
      }
    } catch (SqlMaskException e) {
      System.err.println(e.getMessage());
      System.exit(1);
    } catch (Exception e) {
      System.err.println("fatal: " + e.getMessage());
      System.exit(1);
    }
  }
}
