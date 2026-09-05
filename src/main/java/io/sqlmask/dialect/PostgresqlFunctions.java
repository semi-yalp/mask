package io.sqlmask.dialect;

import org.apache.calcite.sql.SqlBasicFunction;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.fun.SqlLibraryOperatorTableFactory;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandCountRanges;
import org.apache.calcite.sql.util.ListSqlOperatorTable;
import org.apache.calcite.sql.util.SqlOperatorTables;
import org.apache.calcite.sql.validate.SqlNameMatcher;
import org.apache.calcite.sql.validate.SqlNameMatchers;

import java.util.List;

/**
 * PostgreSQL-flavored operator table: Calcite's PostgreSQL library functions
 * plus common PostgreSQL scalar functions missing from the libraries (varargs
 * {@code concat}).
 *
 * <p>Routine names resolve case-insensitively (as PostgreSQL does) even
 * though the table/column matcher of the session is case-sensitive: with
 * {@code unquotedCasing=TO_LOWER} the parser hands routine names over in
 * lower case while Calcite registers them in upper case.
 */
public final class PostgresqlFunctions {

  /** {@code concat(arg, ...)}: PostgreSQL concatenates strings. */
  public static final SqlFunction CONCAT = SqlBasicFunction.create("CONCAT",
      ReturnTypes.MULTIVALENT_STRING_SUM_PRECISION_NULLABLE,
      OperandTypes.repeat(SqlOperandCountRanges.from(1), OperandTypes.STRING),
      SqlFunctionCategory.STRING)
      .withOperandTypeInference(InferTypes.RETURN_TYPE);

  public static final SqlOperatorTable TABLE = SqlOperatorTables.chain(
      SqlStdOperatorTable.instance(),
      caseInsensitive(buildOperators()));

  private PostgresqlFunctions() {
  }

  private static List<SqlOperator> buildOperators() {
    List<SqlOperator> operators = new java.util.ArrayList<>();
    operators.addAll(SqlLibraryOperatorTableFactory.INSTANCE
        .getOperatorTable(SqlLibrary.POSTGRESQL).getOperatorList());
    operators.add(CONCAT);
    return operators;
  }

  private static SqlOperatorTable caseInsensitive(List<SqlOperator> operators) {
    return new ListSqlOperatorTable(operators) {
      @Override
      public void lookupOperatorOverloads(SqlIdentifier opName,
          SqlFunctionCategory category, SqlSyntax syntax,
          List<SqlOperator> operatorList,
          SqlNameMatcher nameMatcher) {
        super.lookupOperatorOverloads(opName, category, syntax, operatorList,
            SqlNameMatchers.withCaseSensitive(false));
      }
    };
  }
}
