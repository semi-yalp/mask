package io.sqlmask.dialect;

/** Hive quoting: always backtick-quote wrapper identifiers (same as MySQL). */
public final class HiveIdentifierPolicy implements IdentifierPolicy {

  @Override
  public String render(String name) {
    return "`" + name.replace("`", "``") + "`";
  }
}