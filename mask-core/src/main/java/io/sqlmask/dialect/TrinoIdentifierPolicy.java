package io.sqlmask.dialect;

import org.apache.calcite.sql.dialect.TrinoSqlDialect;

import java.util.Locale;
import java.util.Set;

/**
 * Trino quoting: quote-when-needed with a conservative superset of Trino's
 * reserved words (quoting a non-reserved word is always legal; failing to
 * quote a reserved one breaks SQL — hence the superset bias). Spec §10.1
 * item 2: verify against the official Trino reserved-word list and trim.
 */
public final class TrinoIdentifierPolicy implements IdentifierPolicy {

  private static final Set<String> RESERVED = Set.of(
      "ALL", "ALTER", "AND", "ANY", "ARRAY", "AS", "ASC", "BETWEEN", "BOTH",
      "CASE", "CAST", "CHECK", "COLLATE", "COLUMN", "CONSTRAINT", "CREATE",
      "CROSS", "CURRENT_CATALOG", "CURRENT_DATE", "CURRENT_ROLE",
      "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "DEFAULT",
      "DEFERRABLE", "DESC", "DISTINCT", "DO", "ELSE", "END", "EXCEPT",
      "FALSE", "FETCH", "FOR", "FOREIGN", "FROM", "GRANT", "GROUP", "HAVING",
      "IN", "INITIALLY", "INNER", "INTERSECT", "INTO", "JOIN", "LATERAL",
      "LEADING", "LEFT", "LIMIT", "LOCALTIME", "LOCALTIMESTAMP", "NATURAL",
      "NOT", "NULL", "OFFSET", "ON", "ONLY", "OR", "ORDER", "OUTER",
      "PLACING", "PRIMARY", "REFERENCES", "RIGHT", "ROW", "ROWS", "SELECT",
      "SESSION_USER", "SOME", "SYMMETRIC", "TABLE", "THEN", "TO", "TRAILING",
      "TRUE", "UNION", "UNIQUE", "USER", "USING", "VARIADIC", "WHEN",
      "WHERE", "WINDOW", "WITH", "VALUES");

  private static final String PLAIN_IDENTIFIER = "[a-z_][a-z0-9_]*";

  @Override
  public String render(String name) {
    if (!name.matches(PLAIN_IDENTIFIER)
        || RESERVED.contains(name.toUpperCase(Locale.ROOT))) {
      return TrinoSqlDialect.DEFAULT.quoteIdentifier(name);
    }
    return name;
  }
}
