package io.sqlmask.dialect;

/**
 * MySQL quoting: always backtick-quote wrapper identifiers. MySQL doubles
 * embedded backticks; always-quoting removes the need for a reserved-word
 * list and is always legal.
 */
public final class MysqlIdentifierPolicy implements IdentifierPolicy {

  @Override
  public String render(String name) {
    return "`" + name.replace("`", "``") + "`";
  }
}
