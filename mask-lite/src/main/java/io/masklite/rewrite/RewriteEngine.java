package io.masklite.rewrite;

import io.masklite.config.LegacyPolicyAdapter;
import io.masklite.config.LoadedConfig;
import io.masklite.config.YamlConfigLoader;
import io.masklite.dialect.DialectAdapter;
import io.masklite.dialect.DialectRegistry;
import io.masklite.error.SqlMaskException;
import io.masklite.lineage.LineageAnalyzer;
import io.masklite.lineage.OutputLineage;
import io.masklite.metadata.YamlCalciteSchemaFactory;
import io.masklite.policy.match.PolicyEngine;
import io.masklite.policy.match.PolicyIndex;
import io.masklite.policy.model.Policy;
import io.masklite.policy.model.Subject;
import io.masklite.rowfilter.RowFilterRegistry;
import io.masklite.rowfilter.RowFilterRewriter;
import io.masklite.sql.SqlStatementSplitter;
import io.masklite.sql.ValidatedSql;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole rewriting pipeline as a reusable engine: YAML metadata text plus
 * SQL text in, per-statement rewriting results out. Every statement is
 * parsed, validated, analyzed and rewritten in order; any failure aborts the
 * whole run without producing a partial result.
 *
 * <p>mask-lite 裁剪版：PostgreSQL 方言、legacy 元数据内嵌策略
 * （{@code columns} / {@code rowFilter} / {@code policies}）、匿名主体、
 * 仅接受 SELECT/WITH 查询语句——写语句与其余方言一律 fail-closed。
 */
public final class RewriteEngine {

  /**
   * Result of one statement. {@code originalSql} is the statement as written
   * by the user (the pre-validation, pre-row-filter rendering; no trailing
   * ';') — the row filter, when applied, lives only in {@code rewrittenSql}
   * so callers can always diff input vs output. {@code masked} is true when
   * the statement got an outer masking wrapper; {@code rowFiltered} is true
   * when at least one row-filter condition was injected.
   */
  public record StatementRewrite(int ordinal, String originalSql, String rewrittenSql,
      boolean masked, boolean rowFiltered) {
  }

  /**
   * Rewrites all statements in {@code sqlText} against {@code metadataYaml}.
   * Policies come from the metadata's own sections, the subject is anonymous.
   *
   * @param metadataYaml YAML configuration content (tables, columns, policies)
   * @param sqlText      one or more SQL statements separated by semicolons
   */
  public List<StatementRewrite> rewrite(String metadataYaml, String sqlText) {
    LoadedConfig loaded =
        new YamlConfigLoader().loadContent(metadataYaml, "metadata.yaml", DialectRegistry.POSTGRESQL);
    return rewrite(loaded, sqlText);
  }

  /**
   * Rewrites against an already-resolved configuration (inline YAML).
   * Policies are the converted policy sections of the configuration
   * (byte-identical to the historical registry path).
   *
   * @param loaded validated configuration
   * @param sqlText one or more SQL statements separated by semicolons
   */
  public List<StatementRewrite> rewrite(LoadedConfig loaded, String sqlText) {
    List<Policy> policies = LegacyPolicyAdapter.convert(loaded.config());
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(policies));
    SchemaPlus schema = YamlCalciteSchemaFactory.create(loaded);
    DialectAdapter dialect = DialectRegistry.create(DialectRegistry.POSTGRESQL);
    // an invalid row-filter condition fails the whole run before any
    // statement is touched (CONFIG_ERROR straight from the registry build);
    // the legacy path keeps the table-prefixed messages byte-identical
    RowFilterRegistry rowFilters = RowFilterRegistry.build(loaded, dialect, schema);
    RowFilterRewriter rowFilterRewriter = new RowFilterRewriter(dialect);
    MaskSelector selector = new PdpMaskSelector(engine, Subject.anonymous());
    // 血缘层用选择器校验标量子查询的安全性（含子查询的表达式元数据拿不到
    // origins；子查询自身输出命中策略即 fail-closed）
    LineageAnalyzer analyzer = new LineageAnalyzer(selector);
    SqlRewriteService rewriteService = new SqlRewriteService();

    List<String> statements = new SqlStatementSplitter().split(sqlText == null ? "" : sqlText);
    List<StatementRewrite> results = new ArrayList<>();
    int ordinal = 0;
    for (String statement : statements) {
      ordinal++;
      try {
        results.add(rewriteOne(dialect, analyzer, selector, rewriteService, schema,
            loaded, rowFilters, rowFilterRewriter, statement, ordinal));
      } catch (SqlMaskException e) {
        // the dialect already prefixes its diagnostics with the statement
        // ordinal; avoid duplicating it
        String message = e.getMessage() != null && e.getMessage().startsWith("statement ")
            ? e.getMessage()
            : "statement " + ordinal + ": " + e.getMessage();
        throw new SqlMaskException(e.getCode(), message, e);
      }
    }
    return results;
  }

  private StatementRewrite rewriteOne(DialectAdapter dialect, LineageAnalyzer analyzer,
      MaskSelector selector, SqlRewriteService rewriteService, SchemaPlus schema,
      LoadedConfig loaded, RowFilterRegistry rowFilters, RowFilterRewriter rowFilterRewriter,
      String statementText, int ordinal) {
    SqlNode parsed = dialect.parse(statementText, ordinal);
    // mask-lite is read-only: plain queries only (SELECT / WITH … SELECT,
    // optionally with a top-level ORDER BY — the parser wraps those in an
    // SqlOrderBy node). Everything else is rejected fail-closed.
    if (parsed.getKind() != SqlKind.SELECT && parsed.getKind() != SqlKind.ORDER_BY) {
      throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
          "mask-lite only rewrites SELECT/WITH queries; statement kind '"
              + parsed.getKind().lowerName + "' is not supported");
    }
    // read statement: snapshot the statement as written before the row
    // filter rewriter runs, so originalSql never contains the injection
    RowFilterRewriter.Result filtered = rowFilterRewriter.apply(parsed, loaded, rowFilters);
    String originalSql = filtered.injections() > 0
        ? dialect.unparse(parsed)
        : null;
    ValidatedSql validated = dialect.validate(filtered.node(), schema);
    RewritePlan plan = RewritePlan.of(analyzer.analyze(validated), selector);
    String rewritten = rewriteService.rewrite(validated, plan, dialect);
    return new StatementRewrite(ordinal,
        filtered.injections() > 0 ? originalSql : validated.originalSql(),
        rewritten, plan.requiresWrapper(), filtered.injections() > 0);
  }

  /** Joins statement results into a single script (semicolon per statement). */
  public static String join(List<StatementRewrite> statements) {
    return statements.stream()
        .map(s -> s.rewrittenSql() + ";")
        .reduce((a, b) -> a + "\n\n" + b)
        .orElse("");
  }
}
