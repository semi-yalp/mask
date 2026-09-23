package io.masklite.dialect;

import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.fun.SqlLibraryOperatorTableFactory;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.util.SqlOperatorTables;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PostgreSQL-flavored operator table: Calcite's standard operators plus the
 * PostgreSQL library functions.
 *
 * <p>Routine names resolve case-insensitively (as PostgreSQL does) even
 * though the table/column matcher of the session is case-sensitive: with
 * {@code unquotedCasing=TO_LOWER} the parser hands routine names over in
 * lower case while Calcite registers them in upper case.
 *
 * <p>Two rules keep every function name at exactly one candidate:
 *
 * <ul>
 * <li>Do <em>not</em> add a hand-written {@code concat} here: the PostgreSQL
 *     library already ships one ({@code SqlLibraryOperators.CONCAT_FUNCTION_WITH_NULL},
 *     PG null-to-empty-string semantics). A second registration makes the name
 *     resolve to two overloads, and {@code SqlUtil.lookupRoutine}'s
 *     type-precedence pass then drops every variadic candidate, so re-derivation
 *     during conversion fails with "No match found for function signature
 *     CONCAT(...)" — exactly the q05/q80 benchmark failures.
 * <li>Library operators whose name the standard table already provides
 *     ({@code power} today, possibly more in future Calcite releases) are
 *     dropped: the standard version defines the same semantics, and keeping
 *     both would recreate the duplicate-overload trap above.
 * </ul>
 */
public final class PostgresqlFunctions {

  public static final SqlOperatorTable TABLE = SqlOperatorTables.chain(
      SqlStdOperatorTable.instance(),
      postgresqlLibraryWithoutStdDuplicates());

  /**
   * The PostgreSQL library (case-insensitive lookup) minus any operator whose
   * name the standard table already registers.
   */
  private static SqlOperatorTable postgresqlLibraryWithoutStdDuplicates() {
    List<SqlOperator> library = SqlLibraryOperatorTableFactory.INSTANCE
        .getOperatorTable(SqlLibrary.POSTGRESQL).getOperatorList();
    Set<String> standardNames = SqlStdOperatorTable.instance().getOperatorList().stream()
        .map(PostgresqlFunctions::lowerName)
        .collect(Collectors.toSet());
    List<SqlOperator> withoutDuplicates = library.stream()
        .filter(operator -> !standardNames.contains(lowerName(operator)))
        .toList();
    return CaseInsensitiveOperatorTable.of(withoutDuplicates);
  }

  private static String lowerName(SqlOperator operator) {
    return operator.getName().toLowerCase(Locale.ROOT);
  }

  private PostgresqlFunctions() {
  }
}
