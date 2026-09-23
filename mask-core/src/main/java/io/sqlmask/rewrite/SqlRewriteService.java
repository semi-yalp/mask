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
    boolean hasSynthetic = plan.outputs().stream()
        .anyMatch(output -> SYNTHETIC_NAME.matcher(output.outputName()).matches());
    if (hasSynthetic && !dialect.capabilities().supportsDerivedColumnAliasList()) {
      // Hive/SparkSQL cannot parse FROM (...) AS r (a, b): the renamed columns
      // would be unreferenceable, so refuse with an actionable hint instead
      throw new SqlMaskException(SqlMaskException.Code.REWRITE_ERROR,
          "cannot wrap a query whose output contains an unnamed computed column (EXPR$N) "
              + "in dialect '" + dialect.name() + "': add an explicit alias to the projection "
              + "so every output column has a name");
    }
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

  /**
   * Final referenceable names for the wrapper, one per output in order.
   * Calcite derives synthetic EXPR$N names for unnamed computed columns, but
   * the target engine names those columns differently (PG {@code ?column?},
   * Trino {@code _col0}), so referencing {@code r."EXPR$1"} would fail with
   * "column does not exist". Synthetics are renamed to generated
   * {@code mask_col_N} names and the derived table gets an explicit column
   * alias list ({@code FROM (...) AS r (a, b, c)}), keeping the user's inner
   * SQL byte-for-byte untouched.
   */
  private List<String> finalColumnNames(RewritePlan plan) {
    List<String> names = new ArrayList<>();
    int generated = 0;
    for (OutputRewrite output : plan.outputs()) {
      String name = output.outputName();
      if (SYNTHETIC_NAME.matcher(name).matches()) {
        name = GENERATED_PREFIX + ++generated;
      }
      names.add(name);
    }
    return names;
  }

  private String buildWrapper(ValidatedSql validated, RewritePlan plan, DialectAdapter dialect) {
    IdentifierPolicy ids = dialect.profile().identifierPolicy();
    List<String> columnNames = finalColumnNames(plan);
    boolean renamed = false;
    List<String> items = new ArrayList<>();
    for (int i = 0; i < plan.outputs().size(); i++) {
      OutputRewrite output = plan.outputs().get(i);
      String name = columnNames.get(i);
      renamed |= !name.equals(output.outputName());
      String reference = ids.renderQualified(WRAPPER_ALIAS, name);
      if (output.isMasked()) {
        String call = renderUdfCall(output.policy().orElseThrow(), reference,
            ids, dialect.profile().sqlDialect());
        items.add(call + " AS " + ids.render(name));
      } else {
        items.add(reference);
      }
    }
    StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", items))
        .append(" FROM (\n").append(validated.originalSql()).append("\n) AS ")
        .append(ids.render(WRAPPER_ALIAS));
    if (renamed) {
      // positionally renames the derived-table columns, including the renamed
      // synthetic ones; Hive/SparkSQL cannot parse this form, see below
      String aliases = columnNames.stream().map(ids::render)
          .collect(java.util.stream.Collectors.joining(", "));
      sql.append(" (").append(aliases).append(')');
    }
    return sql.toString();
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

  /** Calcite's synthetic names for projection items without an alias. */
  private static final java.util.regex.Pattern SYNTHETIC_NAME =
      java.util.regex.Pattern.compile("^EXPR\\$\\d+$");

  /** Prefix of names synthesized for those items at the wrapper's alias list. */
  private static final String GENERATED_PREFIX = "mask_col_";
}
