package io.sqlmask.rewrite;

import io.sqlmask.dialect.DialectAdapter;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.dialect.PostgresqlSqlDialect;

import java.util.Locale;
import java.util.Set;

/**
 * Renders identifiers of the outer projection with PostgreSQL rules: plain
 * lower-case identifiers are emitted unquoted, everything else (mixed case,
 * special characters, reserved words) is double-quoted so the original
 * spelling and reserved-word names survive the round trip.
 */
public final class SqlIdentifierRenderer {

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

  private final SqlDialect dialect;

  public SqlIdentifierRenderer() {
    this(PostgresqlSqlDialect.DEFAULT);
  }

  public SqlIdentifierRenderer(SqlDialect dialect) {
    this.dialect = dialect;
  }

  /** Renders a single identifier, quoting when required. */
  public String render(String name) {
    if (needsQuote(name)) {
      return dialect.quoteIdentifier(name);
    }
    return name;
  }

  /** Renders {@code alias.name}. */
  public String renderQualified(String alias, String name) {
    return render(alias) + "." + render(name);
  }

  private boolean needsQuote(String name) {
    if (!name.matches(PLAIN_IDENTIFIER)) {
      return true;
    }
    return RESERVED.contains(name.toUpperCase(Locale.ROOT));
  }
}
