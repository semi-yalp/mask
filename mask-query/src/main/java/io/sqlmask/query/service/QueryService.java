package io.sqlmask.query.service;

import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.executors.QueryEngine;
import io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView;
import io.sqlmask.query.metadata.MetadataServiceClient.InstanceView;
import io.sqlmask.query.rewrite.QueryRewriter;
import io.sqlmask.query.rewrite.StatementView;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * The guarded execution pipeline: per-instance concurrency permit → rewrite
 * (via mask-core) → read-only connection → statement timeout → maxRows+1
 * truncation → streaming fetch. PostgreSQL reads inside a read-only
 * transaction (rolled back at the end) so the driver streams; every failure
 * maps to a structured QueryException.
 */
@Service
public class QueryService {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(QueryService.class);

  private final InstanceDirectory directory;
  private final QueryRewriter rewrites;
  private final QueryProperties props;
  private final ConnectionFactory connections;
  private final CredentialSource credentials;
  private final ConcurrentHashMap<String, Semaphore> permits = new ConcurrentHashMap<>();

  public QueryService(InstanceDirectory directory, QueryRewriter rewrites,
      QueryProperties props, ConnectionFactory connections, CredentialSource credentials) {
    this.directory = directory;
    this.rewrites = rewrites;
    this.props = props;
    this.connections = connections;
    this.credentials = credentials;
  }

  public interface ConnectionFactory {
    Connection connect(QueryEngine engine, ConnectionView c, String password) throws SQLException;
  }

  public interface InstanceDirectory {
    InstanceView fetch(String name);
  }

  public QueryModels.QueryResult execute(QueryModels.QueryRequest request,
      CancelRegistry.Registration registration) {
    InstanceView instance = directory.fetch(request.instance());
    if (instance.connection() == null) {
      throw new QueryException(QueryException.INSTANCE_NOT_EXECUTABLE,
          "instance '" + instance.name() + "' declares no connection settings");
    }
    QueryEngine engine = QueryEngine.of(instance.engine());
    if (!engine.dialect().equals(instance.dialect())) {
      throw new QueryException(QueryException.CONFIG_ERROR,
          "instance engine '" + engine.id() + "' does not match its dialect '"
              + instance.dialect() + "'");
    }
    Semaphore permit = permits.computeIfAbsent(instance.name(),
        n -> new Semaphore(props.maxConcurrentPerInstance()));
    if (!permit.tryAcquire()) {
      throw new QueryException(QueryException.QUERY_BUSY,
          "instance '" + instance.name() + "' has reached the concurrent query limit ("
              + props.maxConcurrentPerInstance() + ")");
    }
    try {
      return executeUnderPermit(request, instance, engine, registration);
    } finally {
      permit.release();
    }
  }

  private QueryModels.QueryResult executeUnderPermit(QueryModels.QueryRequest request,
      InstanceView instance, QueryEngine engine, CancelRegistry.Registration registration) {
    List<StatementView> statements = rewrites
        .rewrite(instance.name(), request.sql(), request.user(), request.groups())
        .statements();
    if (statements.isEmpty()) {
      throw new QueryException(QueryException.CONFIG_ERROR,
          "the query contains no executable statement");
    }
    if (statements.size() > 1) {
      throw new QueryException(QueryException.MULTI_STATEMENT,
          "the query API accepts exactly one statement (got " + statements.size() + ")");
    }
    StatementView statementView = statements.get(0);
    if (!"SELECT".equals(statementView.kind())) {
      throw new QueryException(QueryException.WRITE_STATEMENT,
          "the query API is read-only; statement kind '" + statementView.kind()
              + "' is rejected");
    }
    String rewritten = statementView.rewrittenSql();
    String password = credentials.resolve(instance.connection().passwordRef());
    // the hard cap applies to the configured default too, not only explicit asks
    int effectiveMax = Math.min(request.maxRows() == null ? props.maxRows() : request.maxRows(),
        props.maxRowsHard());
    long start = System.nanoTime();
    try (Connection connection = connections.connect(engine, instance.connection(), password)) {
      connection.setReadOnly(true);
      boolean pgTransaction = engine == QueryEngine.POSTGRESQL;
      if (pgTransaction) {
        connection.setAutoCommit(false);
      }
      QueryModels.QueryResult result;
      try (Statement statement = connection.createStatement()) {
        registration.attach(statement);
        try {
          statement.setQueryTimeout(props.timeoutSeconds());
          statement.setMaxRows(effectiveMax + 1);
          statement.setFetchSize(props.fetchSize());
          try (ResultSet rs = statement.executeQuery(rewritten)) {
            List<QueryModels.ColumnView> columns = columnsOf(rs);
            List<List<Object>> rows = new ArrayList<>();
            boolean truncated = false;
            while (rows.size() < effectiveMax && rs.next()) {
              List<Object> row = new ArrayList<>(columns.size());
              for (int i = 1; i <= columns.size(); i++) {
                row.add(ValueJson.toSerializable(rs.getObject(i)));
              }
              rows.add(row);
            }
            if (rows.size() == effectiveMax && rs.next()) {
              truncated = true;
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            result = new QueryModels.QueryResult(instance.name(), engine.id(), columns, rows,
                rows.size(), truncated, statementView.masked(), statementView.rowFiltered(),
                elapsedMs, Boolean.TRUE.equals(request.includeRewrittenSql()) ? rewritten : null);
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
          registration.detach();
        }
      }
      if (pgTransaction) {
        // success-path cleanup; a failing rollback surfaces as QUERY_ERROR
        connection.rollback();
      }
      return result;
    } catch (SQLException e) {
      LOG.warn("query failed: instance='{}' engine='{}' sqlState={} message={}",
          instance.name(), engine.id(), e.getSQLState(), e.getMessage());
      throw ErrorClassifier.classify(e);
    }
  }

  private static List<QueryModels.ColumnView> columnsOf(ResultSet rs) throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    int count = meta.getColumnCount();
    List<QueryModels.ColumnView> columns = new ArrayList<>(count);
    for (int i = 1; i <= count; i++) {
      columns.add(new QueryModels.ColumnView(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
    }
    return columns;
  }

  // —— 并发信号量的测试钩子（生产不调用） ——
  void holdPermitForTest(String instance) {
    permits.computeIfAbsent(instance, n -> new Semaphore(props.maxConcurrentPerInstance()));
    try {
      permits.get(instance).acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  void releasePermitForTest(String instance) {
    Semaphore semaphore = permits.get(instance);
    if (semaphore != null) {
      semaphore.release();
    }
  }
}
