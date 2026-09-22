package io.masklite.rewrite;

import io.masklite.config.MaskingConfig;
import io.masklite.dialect.Dialect;
import io.masklite.dialect.DialectRegistry;
import io.masklite.error.SqlMaskException;
import io.masklite.lineage.LineageAnalyzer;
import io.masklite.metadata.YamlCalciteSchemaFactory;
import io.masklite.rowfilter.RowFilterRegistry;
import io.masklite.rowfilter.RowFilterRewriter;
import io.masklite.sql.SqlStatementSplitter;
import io.masklite.sql.ValidatedSql;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole SELECT-masking pipeline: validated configuration plus SQL text
 * in, per-statement rewriting results out. Every statement is parsed,
 * validated, analyzed and rewritten in order; any failure aborts the whole
 * run without producing a partial result.
 */
public final class RewriteEngine {

  /**
   * Result of one statement. {@code originalSql} is the statement as written
   * by the user (the pre-validation rendering; no trailing ';') — the inner
   * query of a wrapper lives only in {@code rewrittenSql} so callers can
   * always diff input vs output. {@code masked} is true when the statement
   * got an outer masking wrapper; {@code rowFiltered} is true when at least
   * one row-filter predicate was injected into its FROM clauses.
   */
  public record StatementRewrite(int ordinal, String originalSql, String rewrittenSql,
      boolean masked, boolean rowFiltered) {

    public StatementRewrite(int ordinal, String originalSql, String rewrittenSql, boolean masked) {
      this(ordinal, originalSql, rewrittenSql, masked, false);
    }

    public boolean unchanged() {
      return originalSql.equals(rewrittenSql);
    }
  }

  /**
   * Rewrites all statements in {@code sqlText} against {@code config}.
   *
   * @param config  validated configuration (tables, column policies)
   * @param sqlText one or more SQL statements separated by semicolons
   */
  public List<StatementRewrite> rewrite(MaskingConfig config, String sqlText) {
    SchemaPlus schema = YamlCalciteSchemaFactory.create(config);
    Dialect dialect = DialectRegistry.create(DialectRegistry.DEFAULT);
    LineageAnalyzer analyzer = new LineageAnalyzer();
    ColumnMaskSelector selector = ColumnMaskSelector.of(config);
    SqlRewriteService rewriteService = new SqlRewriteService();
    // an invalid row-filter condition fails the whole run before any
    // statement is touched (CONFIG_ERROR straight from the registry build)
    RowFilterRegistry rowFilters = RowFilterRegistry.build(config, dialect, schema);
    RowFilterRewriter rowFilterRewriter = new RowFilterRewriter(dialect);

    List<String> statements = new SqlStatementSplitter().split(sqlText == null ? "" : sqlText);
    List<StatementRewrite> results = new ArrayList<>();
    int ordinal = 0;
    for (String statement : statements) {
      ordinal++;
      try {
        results.add(rewriteOne(dialect, analyzer, selector, rewriteService, schema,
            config, rowFilters, rowFilterRewriter, statement, ordinal));
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

  private StatementRewrite rewriteOne(Dialect dialect, LineageAnalyzer analyzer,
      ColumnMaskSelector selector, SqlRewriteService rewriteService, SchemaPlus schema,
      MaskingConfig config, RowFilterRegistry rowFilters, RowFilterRewriter rowFilterRewriter,
      String statementText, int ordinal) {
    SqlNode parsed = dialect.parse(statementText, ordinal);
    // inject row filters between parse and validation: the injected derived
    // table must exist (and the pre-injection snapshot be captured) before
    // the validator's in-place edits touch the tree
    RowFilterRewriter.Result filtered = rowFilterRewriter.apply(parsed, config, rowFilters);
    String originalSql = filtered.injections() > 0 ? dialect.unparse(parsed) : null;
    ValidatedSql validated = dialect.validate(filtered.node(), schema);
    RewritePlan plan = RewritePlan.of(analyzer.analyze(validated), selector);
    String rewritten = rewriteService.rewrite(validated, plan, dialect);
    boolean rowFiltered = filtered.injections() > 0;
    return new StatementRewrite(ordinal,
        rowFiltered ? originalSql : validated.originalSql(),
        rewritten, plan.requiresWrapper(), rowFiltered);
  }

  /** Joins statement results into a single script (semicolon per statement). */
  public static String join(List<StatementRewrite> statements) {
    return statements.stream()
        .map(s -> s.rewrittenSql() + ";")
        .reduce((a, b) -> a + "\n\n" + b)
        .orElse("");
  }
}
