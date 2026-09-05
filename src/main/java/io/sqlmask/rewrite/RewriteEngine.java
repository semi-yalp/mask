package io.sqlmask.rewrite;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.dialect.DialectAdapter;
import io.sqlmask.dialect.PostgresqlDialectAdapter;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.lineage.LineageAnalyzer;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import io.sqlmask.policy.PolicySelector;
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
   * Result of one statement. {@code originalSql} is the pre-validation
   * rendering of the statement as written (no trailing ';'); {@code
   * rewrittenSql} is the final SQL (no trailing ';'); {@code masked} is true
   * when the statement got an outer wrapper.
   */
  public record StatementRewrite(int ordinal, String originalSql, String rewrittenSql,
      boolean masked) {

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
    LineageAnalyzer analyzer = new LineageAnalyzer();
    PolicySelector selector = new PolicySelector(loaded.policyRegistry());
    SqlRewriteService rewriteService = new SqlRewriteService();

    List<String> statements = new SqlStatementSplitter().split(sqlText == null ? "" : sqlText);
    List<StatementRewrite> results = new ArrayList<>();
    int ordinal = 0;
    for (String statement : statements) {
      ordinal++;
      try {
        results.add(rewriteOne(dialect, analyzer, selector, rewriteService, schema, statement, ordinal));
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
      ValidatedSql validated = dialect.validate(source, schema);
      RewritePlan plan = RewritePlan.of(analyzer.analyze(validated), selector);
      if (!plan.requiresWrapper()) {
        return new StatementRewrite(ordinal, statementText, statementText, false);
      }
      String wrappedSource = rewriteService.rewrite(validated, plan, dialect);
      String rewritten = dialect.composeWriteStatement(parsed, wrappedSource);
      return new StatementRewrite(ordinal, statementText, rewritten, true);
    }

    ValidatedSql validated = dialect.validate(parsed, schema);
    RewritePlan plan = RewritePlan.of(analyzer.analyze(validated), selector);
    String rewritten = rewriteService.rewrite(validated, plan, dialect);
    return new StatementRewrite(ordinal, validated.originalSql(), rewritten, plan.requiresWrapper());
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
