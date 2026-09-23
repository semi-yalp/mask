package io.sqlmask.dialect;

/** Spark SQL quoting: always backtick-quote wrapper identifiers (same as Hive/MySQL). */
public final class SparkSqlIdentifierPolicy implements IdentifierPolicy {

  @Override
  public String render(String name) {
    return "`" + name.replace("`", "``") + "`";
  }
}
