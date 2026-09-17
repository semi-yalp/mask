package io.sqlmask.query.service;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Wire models of the query data plane. */
public final class QueryModels {
  private QueryModels() {}

  public record QueryRequest(String instance, String sql, String user, List<String> groups,
      Integer maxRows, Boolean includeRewrittenSql) {}

  public record ColumnView(String name, String type) {}

  /** NON_NULL so includeRewrittenSql=false keeps the optional rewrittenSql
   * field out of the response body entirely. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record QueryResult(String instance, String engine, List<ColumnView> columns,
      List<List<Object>> rows, int rowCount, boolean truncated, boolean masked,
      boolean rowFiltered, long elapsedMs, String rewrittenSql) {}
}
