package io.masklite.rewrite;

import io.masklite.config.MaskingPolicy;
import io.masklite.dialect.IdentifierPolicy;
import io.masklite.dialect.PostgresDialect;
import io.masklite.error.SqlMaskException;
import io.masklite.sql.ValidatedSql;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

  public String rewrite(ValidatedSql validated, RewritePlan plan, PostgresDialect dialect) {
    if (!plan.requiresWrapper()) {
      // The validator mutates the parse tree in place (for example it copies
      // ORDER BY / FETCH of a top-level SqlOrderBy into the inner select), so
      // re-unparsing the original node would duplicate clauses. Use the
      // pre-validation snapshot instead, which is the same text used as the
      // inner query of a generated wrapper.
      return validated.originalSql();
    }
    return buildWrapper(validated, plan, dialect);
  }

  /**
   * Final referenceable names for the wrapper, one per output in order.
   * PostgreSQL derived-table column alias lists rename positionally
   * ({@code FROM (...) AS r (a, b, c)}), so Calcite's synthetic EXPR$N
   * names of unnamed expressions become generated ones, and duplicate
   * output names (Calcite keeps them duplicated) get a {@code _2, _3, ...}
   * suffix — the user's inner SQL stays byte-for-byte untouched.
   */
  private List<String> finalColumnNames(RewritePlan plan) {
    List<String> names = new ArrayList<>();
    Set<String> taken = new HashSet<>();
    int generated = 0;
    for (OutputRewrite output : plan.outputs()) {
      String name = output.outputName();
      if (SYNTHETIC_NAME.matcher(name).matches()) {
        name = GENERATED_PREFIX + ++generated;
      }
      if (taken.contains(name)) {
        String base = name;
        int k = 2;
        do {
          name = base + "_" + k++;
        } while (taken.contains(name));
      }
      taken.add(name);
      names.add(name);
    }
    return names;
  }

  private String buildWrapper(ValidatedSql validated, RewritePlan plan, PostgresDialect dialect) {
    IdentifierPolicy ids = dialect.identifiers();
    List<String> columnNames = finalColumnNames(plan);
    boolean renamed = false;
    List<String> items = new ArrayList<>();
    for (int i = 0; i < plan.outputs().size(); i++) {
      OutputRewrite output = plan.outputs().get(i);
      String name = columnNames.get(i);
      renamed |= !name.equals(output.outputName());
      String reference = ids.renderQualified(WRAPPER_ALIAS, name);
      if (output.isMasked()) {
        String call = renderUdfCall(output.policy().orElseThrow(), reference, ids);
        items.add(call + " AS " + ids.render(name));
      } else {
        items.add(reference);
      }
    }
    StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", items))
        .append(" FROM (\n").append(validated.originalSql()).append("\n) AS ")
        .append(ids.render(WRAPPER_ALIAS));
    if (renamed) {
      String aliases = columnNames.stream().map(ids::render).collect(Collectors.joining(", "));
      sql.append(" (").append(aliases).append(')');
    }
    return sql.toString();
  }

  /** Renders {@code udf(reference, arg1, arg2, ...)} with ordered scalar literals. */
  private String renderUdfCall(MaskingPolicy policy, String reference, IdentifierPolicy ids) {
    List<String> arguments = new ArrayList<>();
    arguments.add(reference);
    for (Object argument : policy.arguments()) {
      arguments.add(renderLiteral(argument, policy));
    }
    return ids.render(policy.udf()) + "(" + String.join(", ", arguments) + ")";
  }

  /** Renders arguments through Calcite literal nodes, never string concatenation of raw values. */
  private String renderLiteral(Object argument, MaskingPolicy policy) {
    SqlLiteral literal = toLiteral(argument, policy);
    return literal.toSqlString(config -> config
        .withDialect(PostgresqlSqlDialect.DEFAULT)
        .withQuoteAllIdentifiers(false)
        .withAlwaysUseParentheses(false)
        .withIndentation(0)).getSql();
  }

  private SqlLiteral toLiteral(Object argument, MaskingPolicy policy) {
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
        "policy '" + policy.name() + "' has an argument of unsupported type "
            + argument.getClass().getSimpleName() + ": " + argument);
  }

  private static final String WRAPPER_ALIAS = "r";

  /** Calcite's synthetic names for projection items without an alias. */
  private static final Pattern SYNTHETIC_NAME = Pattern.compile("^EXPR\\$\\d+$");

  /** Prefix of names synthesized for those items at the wrapper's alias list. */
  private static final String GENERATED_PREFIX = "mask_col_";
}
