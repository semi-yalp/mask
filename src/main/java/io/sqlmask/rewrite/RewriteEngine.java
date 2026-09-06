package io.sqlmask.rewrite;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.dialect.DialectAdapter;
import io.sqlmask.dialect.PostgresqlDialectAdapter;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.lineage.LineageAnalyzer;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import io.sqlmask.policy.PolicySelector;
import io.sqlmask.rowfilter.RowFilterRegistry;
import io.sqlmask.rowfilter.RowFilterRewriter;
import io.sqlmask.sql.SqlStatementSplitter;
import io.sqlmask.sql.ValidatedSql;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole rewriting pipeline as a reusable engine: YAML metadata text plus
 * SQL text in, per-statement rewriting results out. Shared by the CLI and
 * the HTTP service. Every statement is parsed, validated, analyzed and
 * rewritten in order; any failure aborts the whole run without producing a
 * partial result.
 */
public final class RewriteEngine {

  /**
   * Result of one statement. {@code originalSql} is the statement as written
   * by the user (for read statements: the pre-validation, pre-row-filter
   * rendering; for write statements: the raw input; no trailing ';') — the
   * row filter, when applied, lives only in {@code rewrittenSql} so callers
   * can always diff input vs output. {@code masked} is true when the
   * statement got an outer masking wrapper; {@code rowFiltered} is true when
   * at least one row-filter condition was injected.
   */
  public record StatementRewrite(int ordinal, String originalSql, String rewrittenSql,
      boolean masked, boolean rowFiltered) {

    /** Convenience constructor for statements without a row filter. */
    public StatementRewrite(int ordinal, String originalSql, String rewrittenSql, boolean masked) {
      this(ordinal, originalSql, rewrittenSql, masked, false);
    }

    /** Serialized into API responses; the web UI keys the original-SQL view off it. */
    @com.fasterxml.jackson.annotation.JsonProperty("unchanged")
    public boolean unchanged() {
      return originalSql.equals(rewrittenSql);
    }
  }

  /**
   * Rewrites all statements in {@code sqlText} against {@code metadataYaml}.
   *
   * @param metadataYaml YAML configuration content (tables, columns, policies)
   * @param sqlText      one or more SQL statements separated by semicolons
   * @param dialectName  dialect name; only 'postgresql' in this version
   */
  public List<StatementRewrite> rewrite(String metadataYaml, String sqlText, String dialectName) {
    LoadedConfig loaded = new YamlConfigLoader().loadContent(metadataYaml, "metadata.yaml");
    SchemaPlus schema = YamlCalciteSchemaFactory.create(loaded);
    DialectAdapter dialect = createDialect(dialectName);
    // an invalid row-filter condition fails the whole run before any
    // statement is touched (CONFIG_ERROR straight from the registry build)
    RowFilterRegistry rowFilters = RowFilterRegistry.build(loaded, dialect, schema);
    RowFilterRewriter rowFilterRewriter = new RowFilterRewriter(dialect);
    LineageAnalyzer analyzer = new LineageAnalyzer();
    PolicySelector selector = new PolicySelector(loaded.policyRegistry());
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
      PolicySelector selector, SqlRewriteService rewriteService, SchemaPlus schema,
      LoadedConfig loaded, RowFilterRegistry rowFilters, RowFilterRewriter rowFilterRewriter,
      String statementText, int ordinal) {
    SqlNode parsed = dialect.parse(statementText, ordinal);

    // write statements: mask the rows being written by wrapping their source
    // query; the target name/column list stay untouched (targets are usually
    // new tables that need no metadata declaration)
    if (parsed.getKind() == org.apache.calcite.sql.SqlKind.INSERT
        || parsed.getKind() == org.apache.calcite.sql.SqlKind.CREATE_TABLE) {
      if (dialect.isPassThroughWrite(parsed)) {
        return new StatementRewrite(ordinal, statementText, statementText, false);
      }
      SqlNode source = dialect.querySourceOf(parsed);
      if (source == null) {
        // not pass-through, but also no traceable query: e.g. a scalar
        // subquery hidden inside INSERT ... VALUES would go unmasked
        throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
            "this write statement carries no traceable query source; masking cannot be "
                + "applied safely (queries hidden inside INSERT ... VALUES are not supported)");
      }
      // row filters apply to the source query only — never to the target —
      // and the filtered source text is rendered before validation so the
      // validator's in-place edits (ORDER BY/FETCH duplication) cannot leak
      // into the composed statement
      RowFilterRewriter.Result filtered = rowFilterRewriter.apply(source, loaded, rowFilters);
      String filteredSourceSql =
          filtered.injections() > 0 ? dialect.unparse(filtered.node()) : null;
      ValidatedSql validated = dialect.validate(filtered.node(), schema);
      RewritePlan plan = RewritePlan.of(analyzer.analyze(validated), selector);
      if (!plan.requiresWrapper()) {
        if (filtered.injections() > 0) {
          return new StatementRewrite(ordinal, statementText,
              dialect.composeWriteStatement(parsed, filteredSourceSql), false, true);
        }
        return new StatementRewrite(ordinal, statementText, statementText, false);
      }
      String wrappedSource = rewriteService.rewrite(validated, plan, dialect);
      String rewritten = dialect.composeWriteStatement(parsed, wrappedSource);
      return new StatementRewrite(ordinal, statementText, rewritten, true,
          filtered.injections() > 0);
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

  private DialectAdapter createDialect(String name) {
    if (PostgresqlDialectAdapter.NAME.equalsIgnoreCase(name)) {
      return new PostgresqlDialectAdapter();
    }
    throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "unsupported dialect '" + name + "'; only 'postgresql' is supported in this version");
  }
}
