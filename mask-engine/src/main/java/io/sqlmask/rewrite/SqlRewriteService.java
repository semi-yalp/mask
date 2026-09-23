package io.sqlmask.rewrite;

import io.sqlmask.dialect.DialectAdapter;
import io.sqlmask.dialect.IdentifierPolicy;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.MaskInstruction;
import io.sqlmask.sql.ValidatedSql;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Generates the final SQL: the original query as the inner query, wrapped by
 * an outer projection that calls the configured masking UDFs on the final
 * result columns. The wrapper is emitted only when at least one output
 * column matched a policy; otherwise the original query passes through.
 *
 * <p>The inner query is rendered once by the dialect's unparser and never
 * modified: WHERE, JOIN, GROUP BY, ORDER BY, LIMIT, DISTINCT and CTEs stay
 * inside. The outer projection adds no predicates, ordering, grouping or
 * pagination.
 */
public final class SqlRewriteService {

  public String rewrite(ValidatedSql validated, RewritePlan plan, DialectAdapter dialect) {
    if (!plan.requiresWrapper()) {
      // The validator mutates the parse tree in place (for example it copies
      // ORDER BY / FETCH of a top-level SqlOrderBy into the inner select), so
      // re-unparsing the original node would duplicate clauses. Use the
      // pre-validation snapshot instead, which is the same text used as the
      // inner query of a generated wrapper.
      return validated.originalSql();
    }
    ensureWrapperIsSafe(plan, dialect);
    return buildWrapper(validated, plan, dialect);
  }

  /**
   * Duplicate output names cannot be referenced unambiguously through a
   * derived table by name; unless the dialect declares a safe mechanism the
   * rewrite is refused instead of generating SQL that could mask the wrong
   * column.
   */
  private void ensureWrapperIsSafe(RewritePlan plan, DialectAdapter dialect) {
    if (dialect.capabilities().canWrapDuplicateOutputNames()) {
      return;
    }
    Set<String> seen = new HashSet<>();
    for (OutputRewrite output : plan.outputs()) {
      if (!seen.add(output.outputName().toLowerCase(Locale.ROOT))) {
        throw new SqlMaskException(SqlMaskException.Code.REWRITE_ERROR,
            "cannot wrap a query whose output contains duplicate column name '"
                + output.outputName() + "': the wrapper could not reference the right column "
                + "unambiguously in dialect '" + dialect.name() + "'");
      }
    }
  }

  private String buildWrapper(ValidatedSql validated, RewritePlan plan, DialectAdapter dialect) {
    IdentifierPolicy ids = dialect.profile().identifierPolicy();
    List<String> items = new ArrayList<>();
    for (OutputRewrite output : plan.outputs()) {
      String reference = ids.renderQualified(WRAPPER_ALIAS, output.outputName());
      if (output.isMasked()) {
        String call = renderUdfCall(output.policy().orElseThrow(), reference,
            ids, dialect.profile().sqlDialect());
        items.add(call + " AS " + ids.render(output.outputName()));
      } else {
        items.add(reference);
      }
    }
    return "SELECT " + String.join(", ", items)
        + " FROM (\n" + validated.originalSql() + "\n) AS " + ids.render(WRAPPER_ALIAS);
  }

  /** Renders {@code udf(reference, arg1, arg2, ...)} with ordered scalar literals. */
  private String renderUdfCall(MaskInstruction policy, String reference,
      IdentifierPolicy ids, SqlDialect sqlDialect) {
    List<String> arguments = new ArrayList<>();
    arguments.add(reference);
    for (Object argument : policy.arguments()) {
      arguments.add(renderLiteral(argument, policy, sqlDialect));
    }
    return ids.render(policy.udf()) + "(" + String.join(", ", arguments) + ")";
  }

  /** Renders arguments through Calcite literal nodes, never string concatenation of raw values. */
  private String renderLiteral(Object argument, MaskInstruction policy, SqlDialect sqlDialect) {
    SqlLiteral literal = toLiteral(argument, policy);
    return literal.toSqlString(config -> config
        .withDialect(sqlDialect)
        .withQuoteAllIdentifiers(false)
        .withAlwaysUseParentheses(false)
        .withIndentation(0)).getSql();
  }

  private SqlLiteral toLiteral(Object argument, MaskInstruction policy) {
    if (argument instanceof Boolean b) {
      return SqlLiteral.createBoolean(b, SqlParserPos.ZERO);
    }
    if (argument instanceof Integer || argument instanceof Long || argument instanceof BigDecimal) {
      return SqlLiteral.createExactNumeric(
          argument instanceof BigDecimal d ? d.toPlainString() : argument.toString(),
          SqlParserPos.ZERO);
    }
    if (argument instanceof Double || argument instanceof Float) {
      return SqlLiteral.createApproxNumeric(
          BigDecimal.valueOf(((Number) argument).doubleValue()).toPlainString(),
          SqlParserPos.ZERO);
    }
    if (argument instanceof String s) {
      return SqlLiteral.createCharString(s, SqlParserPos.ZERO);
    }
    throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "policy '" + policy.policyName() + "' has an argument of unsupported type "
            + argument.getClass().getSimpleName() + ": " + argument);
  }

  private static final String WRAPPER_ALIAS = "r";
}
