package io.sqlmask.query.submit;

import io.sqlmask.query.error.QueryException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Type registry of the query submitters. Blank/unset resolves to "jdbc" (the
 * default execution path); an EXPLICIT unknown type is a configuration error
 * — silently running against the wrong backend would be worse than failing.
 */
@Component
public class SubmitterRegistry {

  public static final String DEFAULT_TYPE = "jdbc";

  private final Map<String, QuerySubmitter> byType = new LinkedHashMap<>();
  private final QuerySubmitter fallback;

  public SubmitterRegistry(List<QuerySubmitter> submitters) {
    QuerySubmitter jdbcFallback = null;
    for (QuerySubmitter submitter : submitters) {
      byType.put(submitter.type(), submitter);
      if (DEFAULT_TYPE.equals(submitter.type())) {
        jdbcFallback = submitter;
      }
    }
    this.fallback = jdbcFallback;
  }

  public QuerySubmitter forType(String type) {
    if (type == null || type.isBlank()) {
      return requireFallback();
    }
    QuerySubmitter submitter = byType.get(type);
    if (submitter == null) {
      throw new QueryException(QueryException.CONFIG_ERROR,
          "unknown submitter '" + type + "' (registered: " + String.join(", ", byType.keySet())
              + ")");
    }
    return submitter;
  }

  private QuerySubmitter requireFallback() {
    if (fallback == null) {
      throw new QueryException(QueryException.CONFIG_ERROR,
          "no jdbc submitter registered; the query gateway cannot execute anything");
    }
    return fallback;
  }
}
