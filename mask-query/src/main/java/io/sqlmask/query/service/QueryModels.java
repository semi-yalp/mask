package io.sqlmask.query.service;

import java.util.List;

/** Wire models of the query data plane. */
public final class QueryModels {
  private QueryModels() {}

  public record QueryRequest(String instance, String sql, String user, List<String> groups,
      Integer maxRows, Boolean includeRewrittenSql) {}

  public record ColumnView(String name, String type) {}

  public record QueryResult(String instance, String engine, List<ColumnView> columns,
      List<List<Object>> rows, int rowCount, boolean truncated, boolean masked,
      boolean rowFiltered, long elapsedMs, String rewrittenSql) {}
}
