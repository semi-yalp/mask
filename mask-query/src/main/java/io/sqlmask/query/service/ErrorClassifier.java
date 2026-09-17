package io.sqlmask.query.service;

import io.sqlmask.query.error.QueryException;

import java.sql.SQLException;
import java.util.Locale;

/** Maps driver failures to QUERY_TIMEOUT vs QUERY_ERROR. Timeout signatures:
 * PG SQLState 57014 / "statement timeout"; Connector/J "due to timeout";
 * generic "timed out". */
final class ErrorClassifier {

  private ErrorClassifier() {}

  static QueryException classify(SQLException e) {
    String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
    boolean timeout = "57014".equals(e.getSQLState())
        || message.contains("statement timeout")
        || message.contains("due to timeout")
        || message.contains("timed out");
    if (timeout) {
      return new QueryException(QueryException.QUERY_TIMEOUT,
          "query exceeded the statement timeout: " + describe(e), e);
    }
    return new QueryException(QueryException.QUERY_ERROR, describe(e), e);
  }

  private static String describe(SQLException e) {
    return (e.getMessage() == null ? "" : e.getMessage())
        + (e.getSQLState() == null ? "" : " (SQLState " + e.getSQLState() + ")");
  }
}
