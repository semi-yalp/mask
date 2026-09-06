package io.sqlmask.dialect;

import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.fun.SqlLibraryOperatorTableFactory;
import org.apache.calcite.sql.util.ListSqlOperatorTable;
import org.apache.calcite.sql.validate.SqlNameMatcher;
import org.apache.calcite.sql.validate.SqlNameMatchers;

import java.util.List;

/** Library operator lookup that is case-insensitive regardless of session matching. */
public final class CaseInsensitiveOperatorTable {

  /** Wraps the operator list of a Calcite {@link SqlLibrary}. */
  public static SqlOperatorTable of(SqlLibrary library) {
    return of(SqlLibraryOperatorTableFactory.INSTANCE
        .getOperatorTable(library).getOperatorList());
  }

  /** Wraps an explicit operator list (dialect extras such as PG {@code concat}). */
  public static SqlOperatorTable of(List<SqlOperator> operators) {
    return new ListSqlOperatorTable(operators) {
      @Override
      public void lookupOperatorOverloads(SqlIdentifier opName,
          SqlFunctionCategory category, SqlSyntax syntax,
          List<SqlOperator> operatorList, SqlNameMatcher nameMatcher) {
        super.lookupOperatorOverloads(opName, category, syntax, operatorList,
            SqlNameMatchers.withCaseSensitive(false));
      }
    };
  }

  private CaseInsensitiveOperatorTable() {
  }
}
