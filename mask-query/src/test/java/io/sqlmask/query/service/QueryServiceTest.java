package io.sqlmask.query.service;

import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.executors.QueryEngine;
import io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView;
import io.sqlmask.query.metadata.MetadataServiceClient.InstanceView;
import io.sqlmask.query.rewrite.QueryRewriter;
import io.sqlmask.query.rewrite.RewrittenQuery;
import io.sqlmask.query.rewrite.StatementView;
import io.sqlmask.query.submit.JdbcQuerySubmitter;
import io.sqlmask.query.submit.SubmitterRegistry;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryServiceTest {

  private static final InstanceView PG = new InstanceView("pg", "postgresql", "postgresql", 1,
      new ConnectionView("h", 5432, "db", "u", "REF", "disable", 10));

  private final QueryRewriter rewrites = mock(QueryRewriter.class);
  private final QueryService.InstanceDirectory directory = mock(QueryService.InstanceDirectory.class);
  private final Connection connection = mock(Connection.class);
  private final Statement statement = mock(Statement.class);
  private final ResultSet resultSet = mock(ResultSet.class);
  private final ResultSetMetaData meta = mock(ResultSetMetaData.class);

  private JdbcQuerySubmitter submitter(QueryProperties props) throws SQLException {
    when(connection.createStatement()).thenReturn(statement);
    return new JdbcQuerySubmitter((engine, c, password) -> connection, ref -> "pw", props);
  }

  private QueryService service(QueryProperties props) throws SQLException {
    when(connection.createStatement()).thenReturn(statement);
    return new QueryService(directory, rewrites, props,
        new SubmitterRegistry(List.of(submitter(props))), null);
  }

  private void stubOneSelect(String rewrittenSql, boolean masked, boolean rowFiltered)
      throws SQLException {
    when(directory.fetch("pg")).thenReturn(PG);
    when(rewrites.rewrite(any(), any(), any(), any()))
        .thenReturn(new RewrittenQuery(List.of(
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
  void rejectsMultiStatementAndWrites() throws SQLException {
    when(directory.fetch("pg")).thenReturn(PG);
    when(rewrites.rewrite(any(), any(), any(), any()))
        .thenReturn(new RewrittenQuery(List.of(
            new StatementView(1, "a", "b", false, false, "SELECT"),
            new StatementView(2, "c", "d", false, false, "SELECT"))));
    QueryService svc = service(new QueryProperties(null, null, null, null, null));
    assertThatThrownBy(() -> svc.execute(
            new QueryModels.QueryRequest("pg", "SELECT 1; SELECT 2", null, List.of(), null, false),
            new CancelRegistry().begin()))
        .hasFieldOrPropertyWithValue("code", "MULTI_STATEMENT");

    when(rewrites.rewrite(any(), any(), any(), any()))
        .thenReturn(new RewrittenQuery(List.of(
            new StatementView(1, "a", "b", true, false, "INSERT_SELECT"))));
    assertThatThrownBy(() -> svc.execute(
            new QueryModels.QueryRequest("pg", "INSERT ...", null, List.of(), null, false),
            new CancelRegistry().begin()))
        .hasFieldOrPropertyWithValue("code", "WRITE_STATEMENT");
  }

  @Test
  void emptyRewriteResultBecomesConfigError() throws SQLException {
    when(directory.fetch("pg")).thenReturn(PG);
    when(rewrites.rewrite(any(), any(), any(), any()))
        .thenReturn(new RewrittenQuery(List.of()));
    QueryService svc = service(new QueryProperties(null, null, null, null, null));
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
        new QueryProperties(30, 1000, 10000, 500, 1),
        new SubmitterRegistry(List.of(submitter(new QueryProperties(30, 1000, 10000, 500, 1)))),
        null);
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

  // ---- rewrite-failure posture ----

  @Test
  void rewriteFailureRejectsByDefault() {
    when(directory.fetch("pg")).thenReturn(PG); // no onRewriteFailure → REJECT
    when(rewrites.rewrite(any(), any(), any(), any()))
        .thenThrow(new QueryException(QueryException.CONFIG_ERROR, "broken policy"));
    assertThatThrownBy(() -> service(new QueryProperties(null, null, null, null, null)).execute(
            new QueryModels.QueryRequest("pg", "SELECT 1", null, List.of(), null, false),
            new CancelRegistry().begin()))
        .hasFieldOrPropertyWithValue("code", "CONFIG_ERROR");
  }

  @Test
  void passthroughRunsOriginalReadWhenRewriteFails() throws Exception {
    InstanceView passthrough = new InstanceView("pg", "postgresql", "postgresql", 1,
        new ConnectionView("h", 5432, "db", "u", "REF", "disable", 10), null, "PASSTHROUGH",
        null, null);
    when(directory.fetch("pg")).thenReturn(passthrough);
    when(rewrites.rewrite(any(), any(), any(), any()))
        .thenThrow(new QueryException(QueryException.CONFIG_ERROR, "broken policy"));
    when(statement.executeQuery(any())).thenReturn(resultSet);
    when(resultSet.getMetaData()).thenReturn(meta);
    when(meta.getColumnCount()).thenReturn(1);
    when(meta.getColumnLabel(1)).thenReturn("id");
    when(meta.getColumnTypeName(1)).thenReturn("bigint");
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getObject(1)).thenReturn(7L);

    QueryModels.QueryResult result = service(new QueryProperties(null, null, null, null, null))
        .execute(new QueryModels.QueryRequest("pg", "SELECT id FROM t", null, List.of(), null, false),
            new CancelRegistry().begin());

    // the ORIGINAL (unrewritten) sql reached the engine, marked as bypassed
    verify(statement).executeQuery("SELECT id FROM t");
    assertThat(result.rewrittenBypassed()).isTrue();
    assertThat(result.masked()).isFalse();
    assertThat(result.rowFiltered()).isFalse();
  }

  @Test
  void passthroughNeverLetsWritesThrough() {
    InstanceView passthrough = new InstanceView("pg", "postgresql", "postgresql", 1,
        new ConnectionView("h", 5432, "db", "u", "REF", "disable", 10), null, "PASSTHROUGH",
        null, null);
    when(directory.fetch("pg")).thenReturn(passthrough);
    when(rewrites.rewrite(any(), any(), any(), any()))
        .thenThrow(new QueryException(QueryException.CONFIG_ERROR, "broken policy"));
    // the raw text parses as a read prefix? no — INSERT fails the plain-read gate
    assertThatThrownBy(() -> service(new QueryProperties(null, null, null, null, null)).execute(
            new QueryModels.QueryRequest("pg", "INSERT INTO t VALUES (1)", null, List.of(), null,
                false),
            new CancelRegistry().begin()))
        .hasFieldOrPropertyWithValue("code", "CONFIG_ERROR");
  }

  @Test
  void instanceTypeSelectsRegisteredSubmitter() throws Exception {
    // an instance with submitter=http but no http endpoint configured fails
    // with the submitter's structured CONFIG_ERROR
    InstanceView httpInstance = new InstanceView("pg", "postgresql", "postgresql", 1,
        new ConnectionView("h", 5432, "db", "u", "REF", "disable", 10), "http", null, null, null);
    when(directory.fetch("pg")).thenReturn(httpInstance);
    when(rewrites.rewrite(any(), any(), any(), any()))
        .thenReturn(new RewrittenQuery(List.of(
            new StatementView(1, "in", "SELECT 1", false, false, "SELECT"))));
    QueryService svc = service(new QueryProperties(null, null, null, null, null));
    assertThatThrownBy(() -> svc.execute(
            new QueryModels.QueryRequest("pg", "SELECT 1", null, List.of(), null, false),
            new CancelRegistry().begin()))
        .hasFieldOrPropertyWithValue("code", QueryException.CONFIG_ERROR);
  }
}
