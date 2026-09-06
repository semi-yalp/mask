package io.sqlmask.dialect;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlBasicFunction;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.SqlOperandCountRanges;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlNameMatcher;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Catch-all operator table: any function name that is not a known built-in
 * or library function resolves as an opaque scalar UDF instead of failing
 * validation.
 *
 * <p>Target engines routinely expose custom scalar functions that Calcite
 * knows nothing about. Refusing to parse them would block queries the engine
 * executes happily, so unknown names become permissive functions: they
 * accept any number of arguments of any type and their return type is
 * approximated as the first argument's type (VARCHAR for zero-argument
 * calls). Lineage still traces through their arguments, so an output column
 * like {@code mask_idcard(c.phone)} is masked according to the policy on
 * {@code c.phone}.
 *
 * <p>The table sits last in the operator chain and stays silent for names
 * the known tables already provide, so built-ins keep their real semantics
 * (e.g. {@code count(phone)} is not degraded into an opaque call). Unknown
 * names used in aggregate position still fail, because pretending to be an
 * aggregate would silently change query semantics.
 */
public final class UnknownFunctionTable implements SqlOperatorTable {

  private static final SqlReturnTypeInference FIRST_ARG_TYPE = binding -> {
    if (binding.getOperandCount() > 0) {
      return binding.getOperandType(0);
    }
    return binding.getTypeFactory().createSqlType(SqlTypeName.VARCHAR);
  };

  private final Set<String> knownNames;

  public UnknownFunctionTable(SqlOperatorTable known) {
    this.knownNames = known.getOperatorList().stream()
        .map(op -> op.getName().toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public void lookupOperatorOverloads(SqlIdentifier opName,
      SqlFunctionCategory category, SqlSyntax syntax,
      List<SqlOperator> operatorList, SqlNameMatcher nameMatcher) {
    if (syntax != SqlSyntax.FUNCTION || !opName.isSimple()) {
      return;
    }
    String simpleName = opName.getSimple();
    if (knownNames.contains(simpleName.toLowerCase(Locale.ROOT))) {
      return;
    }
    operatorList.add(asFunction(simpleName));
  }

  @Override
  public List<SqlOperator> getOperatorList() {
    return List.of();
  }

  private static SqlFunction asFunction(String name) {
    return SqlBasicFunction.create(name, FIRST_ARG_TYPE,
            OperandTypes.repeat(SqlOperandCountRanges.from(0), OperandTypes.ANY),
            SqlFunctionCategory.USER_DEFINED_FUNCTION)
        .withOperandTypeInference(InferTypes.FIRST_KNOWN);
  }
}
