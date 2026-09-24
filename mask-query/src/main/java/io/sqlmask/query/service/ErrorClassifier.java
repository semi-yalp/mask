package io.sqlmask.query.service;

import io.sqlmask.query.error.QueryException;

import java.sql.SQLException;
import java.util.Locale;

/**
 * Maps driver failures to QUERY_TIMEOUT vs QUERY_ERROR. Timeout signatures:
 * PG SQLState 57014 / "statement timeout"; Connector/J "due to timeout";
 * generic "timed out".
 *
 * <p>API responses carry only the coarse code + SQLState: raw driver messages
 * leak hosts, schema details and query ids to the caller. The full driver
 * message stays server-side — QueryService logs it (with instance and engine)
 * and the audit trail records it.
 */
public final class ErrorClassifier {

  private ErrorClassifier() {}

public static QueryException classify(SQLException e) {
    String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
    boolean timeout = "57014".equals(e.getSQLState())
        || message.contains("statement timeout")
        || message.contains("due to timeout")
        || message.contains("timed out");
    if (timeout) {
      return new QueryException(QueryException.QUERY_TIMEOUT,
          "query exceeded the statement timeout" + sqlStateSuffix(e), e);
    }
    return new QueryException(QueryException.QUERY_ERROR,
        "query failed on the target database" + sqlStateSuffix(e), e);
  }

  private static String sqlStateSuffix(SQLException e) {
    return e.getSQLState() == null ? "" : " (SQLState " + e.getSQLState() + ")";
  }
}
