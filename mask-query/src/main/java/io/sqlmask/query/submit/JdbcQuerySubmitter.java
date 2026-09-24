package io.sqlmask.query.submit;

import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.executors.QueryEngine;
import io.sqlmask.query.service.CancelRegistry;
import io.sqlmask.query.service.CredentialSource;
import io.sqlmask.query.service.ErrorClassifier;
import io.sqlmask.query.service.QueryModels.ColumnView;
import io.sqlmask.query.service.QueryModels.QueryResult;
import io.sqlmask.query.service.QueryService;
import io.sqlmask.query.service.ValueJson;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The default submitter: read-only JDBC straight to the engine. Carries over,
 * unchanged, the guard rails of the standalone query service — statement
 * timeout, maxRows+1 truncation probe, streaming fetch size, the
 * PostgreSQL read-only transaction (rolled back so the driver streams), and
 * cancel-registry attach/detach.
 */
@Component
public class JdbcQuerySubmitter implements QuerySubmitter {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(JdbcQuerySubmitter.class);

  private final QueryService.ConnectionFactory connections;
  private final CredentialSource credentials;
  private final QueryProperties props;

  public JdbcQuerySubmitter(QueryService.ConnectionFactory connections,
                            CredentialSource credentials, QueryProperties props) {
    this.connections = connections;
    this.credentials = credentials;
    this.props = props;
  }

  @Override
  public String type() {
    return "jdbc";
  }

  @Override
  public QueryResult submit(SubmitRequest request) throws SQLException {
    QueryEngine engine = QueryEngine.of(request.engine());
    String password = credentials.resolve(request.connection().passwordRef());
    try (Connection connection = connections.connect(engine, request.connection(), password)) {
      connection.setReadOnly(true);
      boolean pgTransaction = engine == QueryEngine.POSTGRESQL;
      if (pgTransaction) {
        connection.setAutoCommit(false);
      }
      QueryResult result;
      try (Statement statement = connection.createStatement()) {
        request.registration().attach(statement);
        try {
          statement.setQueryTimeout(props.timeoutSeconds());
          statement.setMaxRows(request.effectiveMaxRows() + 1);
          statement.setFetchSize(props.fetchSize());
          try (ResultSet rs = statement.executeQuery(request.sql())) {
            List<ColumnView> columns = columnsOf(rs);
            List<List<Object>> rows = new ArrayList<>();
            boolean truncated = false;
            while (rows.size() < request.effectiveMaxRows() && rs.next()) {
              List<Object> row = new ArrayList<>(columns.size());
              for (int i = 1; i <= columns.size(); i++) {
                row.add(ValueJson.toSerializable(rs.getObject(i)));
              }
              rows.add(row);
            }
            if (rows.size() == request.effectiveMaxRows() && rs.next()) {
              truncated = true;
            }
            long elapsedMs = (System.nanoTime() - request.startNanos()) / 1_000_000;
            result = new QueryResult(request.instance(), engine.id(), columns, rows,
                rows.size(), truncated, request.masked(), request.rowFiltered(),
                elapsedMs,
                request.includeRewrittenSql() ? request.sql() : null,
                request.rewrittenBypassed());
          }
        } catch (SQLException | RuntimeException failure) {
          // the read failed: roll back best-effort and keep the primary cause —
          // a rollback failure becomes a suppressed exception, never a mask
          if (pgTransaction) {
            try {
              connection.rollback();
            } catch (SQLException rollbackFailure) {
              failure.addSuppressed(rollbackFailure);
            }
          }
          throw failure;
        } finally {
          request.registration().detach();
        }
      }
      if (pgTransaction) {
        // success-path cleanup; a failing rollback surfaces as QUERY_ERROR
        connection.rollback();
      }
      return result;
    } catch (SQLException e) {
      LOG.warn("query failed: instance='{}' engine='{}' sqlState={} message={}",
          request.instance(), engine.id(), e.getSQLState(), e.getMessage());
      throw ErrorClassifier.classify(e);
    } catch (QueryException e) {
      throw e;
    }
  }

  private static List<ColumnView> columnsOf(ResultSet rs) throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    int count = meta.getColumnCount();
    List<ColumnView> columns = new ArrayList<>(count);
    for (int i = 1; i <= count; i++) {
      columns.add(new ColumnView(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
    }
    return columns;
  }
}
