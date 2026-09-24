package io.sqlmask.dialect;

import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;

import java.util.Locale;
import java.util.Set;

/**
 * PostgreSQL quoting rules: plain lower-case identifiers stay unquoted,
 * everything else (mixed case, special characters, reserved words) is
 * double-quoted.
 */
public final class PostgresqlIdentifierPolicy implements IdentifierPolicy {

  /** PostgreSQL fully reserved keywords that cannot appear unquoted. */
  private static final Set<String> RESERVED = Set.of(
      "ALL", "ANALYSE", "ANALYZE", "AND", "ANY", "ARRAY", "AS", "ASC",
      "ASYMMETRIC", "BOTH", "CASE", "CAST", "CHECK", "COLLATE", "COLUMN",
      "CONSTRAINT", "CREATE", "CURRENT_CATALOG", "CURRENT_DATE",
      "CURRENT_ROLE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER",
      "DEFAULT", "DEFERRABLE", "DESC", "DISTINCT", "DO", "ELSE", "END",
      "EXCEPT", "FALSE", "FETCH", "FOR", "FOREIGN", "FROM", "GRANT", "GROUP",
      "HAVING", "IN", "INITIALLY", "INTERSECT", "INTO", "LATERAL", "LEADING",
      "LIMIT", "LOCALTIME", "LOCALTIMESTAMP", "NOT", "NULL", "OFFSET", "ON",
      "ONLY", "OR", "ORDER", "PLACING", "PRIMARY", "REFERENCES", "RETURNING",
      "SELECT", "SESSION_USER", "SOME", "SYMMETRIC", "TABLE", "THEN", "TO",
      "TRAILING", "TRUE", "UNION", "UNIQUE", "USER", "USING", "VARIADIC",
      "WHEN", "WHERE", "WINDOW", "WITH");

  private static final String PLAIN_IDENTIFIER = "[a-z_][a-z0-9_$]*";

  @Override
  public String render(String name) {
    if (!name.matches(PLAIN_IDENTIFIER)
        || RESERVED.contains(name.toUpperCase(Locale.ROOT))) {
      return PostgresqlSqlDialect.DEFAULT.quoteIdentifier(name);
    }
    return name;
  }
}
