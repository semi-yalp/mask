package io.sqlmask.dialect;

import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.util.SqlOperatorTables;

/** MySQL operator table: standard built-ins plus Calcite's MySQL library. */
public final class MysqlFunctions {

  public static final SqlOperatorTable TABLE = SqlOperatorTables.chain(
      SqlStdOperatorTable.instance(),
      CaseInsensitiveOperatorTable.of(SqlLibrary.MYSQL));

  private MysqlFunctions() {
  }
}
