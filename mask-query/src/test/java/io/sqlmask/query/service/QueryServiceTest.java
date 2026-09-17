package io.sqlmask.query.service;

import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.executors.QueryEngine;
import io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView;
import io.sqlmask.query.metadata.MetadataServiceClient.InstanceView;
import io.sqlmask.query.rewrite.RewriteServiceClient;
import io.sqlmask.query.rewrite.RewriteServiceClient.StatementView;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryServiceTest {

  private static final InstanceView PG = new InstanceView("pg", "postgresql", "postgresql", 1,
      new ConnectionView("h", 5432, "db", "u", "REF", "disable", 10));

  private final RewriteServiceClient rewrites = mock(RewriteServiceClient.class);
  private final QueryService.InstanceDirectory directory = mock(QueryService.InstanceDirectory.class);
  private final Connection connection = mock(Connection.class);
  private final Statement statement = mock(Statement.class);
  private final ResultSet resultSet = mock(ResultSet.class);
  private final ResultSetMetaData meta = mock(ResultSetMetaData.class);

  private QueryService service(QueryProperties props) throws SQLException {
    when(connection.createStatement()).thenReturn(statement);
    return new QueryService(directory, rewrites, props,
        (engine, c, password) -> connection, ref -> "pw");
  }

  private void stubOneSelect(String rewrittenSql, boolean masked, boolean rowFiltered)
      throws SQLException {
    when(directory.fetch("pg")).thenReturn(PG);
    when(rewrites.rewrite(any(), any(), any(), any()))
        .thenReturn(new RewriteServiceClient.RewrittenQuery(List.of(
            new StatementView(1, "in", rewrittenSql, masked, rowFiltered, "SELECT"))));
    when(statement.executeQuery(any())).thenReturn(resultSet);
    when(resultSet.getMetaData()).thenReturn(meta);
  }

  @Test
  void executesWithGuardsAndTruncationFlag() throws Exception {
    stubOneSelect("SELECT mask_phone(r.phone,3,4) FROM ...", true, true);
    when(meta.getColumnCount()).thenReturn(1);
    when(meta.getColumnLabel(1)).thenReturn("phone");
    when(meta.getColumnTypeName(1)).thenReturn("varchar");
    when(resultSet.next()).thenReturn(true, true, true, false); // max(2)+1 行可读 → truncated
    when(resultSet.getObject(1)).thenReturn("138****0001", "138****0002", "138****0003");

    QueryModels.QueryResult result = service(new QueryProperties(30, 2, 10000, 500, 10))
        .execute(new QueryModels.QueryRequest("pg", "SELECT phone FROM t", null, List.of(), null, false),
            new CancelRegistry().begin());

    verify(connection).setReadOnly(true);
    verify(statement).setQueryTimeout(30);
    verify(statement).setMaxRows(3);          // effectiveMax(2) + 1
    verify(statement).setFetchSize(500);
    verify(connection).rollback();            // PG 流式读取的只读事务收尾
    assertThat(result.truncated()).isTrue();
    assertThat(result.rowCount()).isEqualTo(2);
    assertThat(result.rows().get(0)).containsExactly("138****0001");
    assertThat(result.masked()).isTrue();
    assertThat(result.rowFiltered()).isTrue();
  }

  @Test
  void rejectsMultiStatementAndWrites() {
    when(directory.fetch("pg")).thenReturn(PG);
    when(rewrites.rewrite(any(), any(), any(), any()))
        .thenReturn(new RewriteServiceClient.RewrittenQuery(List.of(
            new StatementView(1, "a", "b", false, false, "SELECT"),
            new StatementView(2, "c", "d", false, false, "SELECT"))));
    QueryService svc;
    try {
      svc = service(new QueryProperties(null, null, null, null, null));
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    assertThatThrownBy(() -> svc.execute(
            new QueryModels.QueryRequest("pg", "SELECT 1; SELECT 2", null, List.of(), null, false),
            new CancelRegistry().begin()))
        .hasFieldOrPropertyWithValue("code", "MULTI_STATEMENT");

    when(rewrites.rewrite(any(), any(), any(), any()))
        .thenReturn(new RewriteServiceClient.RewrittenQuery(List.of(
            new StatementView(1, "a", "b", true, false, "INSERT_SELECT"))));
    assertThatThrownBy(() -> svc.execute(
            new QueryModels.QueryRequest("pg", "INSERT ...", null, List.of(), null, false),
            new CancelRegistry().begin()))
        .hasFieldOrPropertyWithValue("code", "WRITE_STATEMENT");
  }

  @Test
  void emptyRewriteResultBecomesConfigError() {
    when(directory.fetch("pg")).thenReturn(PG);
    when(rewrites.rewrite(any(), any(), any(), any()))
        .thenReturn(new RewriteServiceClient.RewrittenQuery(List.of()));
    QueryService svc;
    try {
      svc = service(new QueryProperties(null, null, null, null, null));
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    assertThatThrownBy(() -> svc.execute(
            new QueryModels.QueryRequest("pg", "SELECT 1", null, List.of(), null, false),
            new CancelRegistry().begin()))
        .hasFieldOrPropertyWithValue("code", "CONFIG_ERROR")
        .hasMessageContaining("no executable statement");
  }

  @Test
  void busyFailsFastWithoutRewrite() throws SQLException {
    when(directory.fetch("pg")).thenReturn(PG);
    QueryService svc = new QueryService(directory, rewrites,
        new QueryProperties(30, 1000, 10000, 500, 1), (e, c, p) -> connection, ref -> "pw");
    svc.holdPermitForTest("pg");   // 测试钩子：占满 1 个许可
    assertThatThrownBy(() -> svc.execute(
            new QueryModels.QueryRequest("pg", "SELECT 1", null, List.of(), null, false),
            new CancelRegistry().begin()))
        .hasFieldOrPropertyWithValue("code", "QUERY_BUSY");
    svc.releasePermitForTest("pg");
  }

  @Test
  void timeoutIsClassified() throws Exception {
    stubOneSelect("SELECT 1", false, false);
    when(meta.getColumnCount()).thenReturn(1);
    when(meta.getColumnLabel(1)).thenReturn("x");
    when(meta.getColumnTypeName(1)).thenReturn("int4");
    when(resultSet.next()).thenThrow(new SQLException(
        "canceling statement due to statement timeout", "57014"));
    assertThatThrownBy(() -> service(new QueryProperties(null, null, null, null, null)).execute(
            new QueryModels.QueryRequest("pg", "SELECT 1", null, List.of(), null, false),
            new CancelRegistry().begin()))
        .hasFieldOrPropertyWithValue("code", "QUERY_TIMEOUT");
  }
}
