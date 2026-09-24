package io.sqlmask.query.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.executors.QueryEngine;
import io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView;
import io.sqlmask.query.metadata.MetadataServiceClient.InstanceView;
import io.sqlmask.query.rewrite.QueryRewriter;
import io.sqlmask.query.rewrite.StatementView;
import io.sqlmask.query.submit.QuerySubmitter;
import io.sqlmask.query.submit.SubmitRequest;
import io.sqlmask.query.submit.SubmitterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * The guarded execution pipeline: per-instance concurrency permit → rewrite
 * (in-process gateway or remote service) → registered submitter → structured
 * {@code QueryException} on every failure.
 *
 * <p>Rewrite-failure posture: instances configured with
 * {@code onRewriteFailure: PASSTHROUGH} may run the ORIGINAL sql when the
 * rewrite fails — but only plain reads (SELECT/WITH) qualify, write
 * statements are never let through, and the response/audit marks the
 * bypass ({@code rewrittenBypassed=true}; the risk domain's MASK_BYPASS
 * rule watches these). The default posture is REJECT: fail-closed.
 */
@Service
public class QueryService {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(QueryService.class);

  private final InstanceDirectory directory;
  private final QueryRewriter rewrites;
  private final QueryProperties props;
  private final SubmitterRegistry submitters;
  private final ObjectProvider<MeterRegistry> meters;
  private final ConcurrentHashMap<String, Semaphore> permits = new ConcurrentHashMap<>();

  public QueryService(InstanceDirectory directory, QueryRewriter rewrites,
      QueryProperties props, SubmitterRegistry submitters,
      ObjectProvider<MeterRegistry> meters) {
    this.directory = directory;
    this.rewrites = rewrites;
    this.props = props;
    this.submitters = submitters;
    this.meters = meters;
  }

  public interface ConnectionFactory {
    java.sql.Connection connect(QueryEngine engine, ConnectionView c, String password)
        throws java.sql.SQLException;
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
    long start = System.nanoTime();
    // the hard cap applies to the configured default too, not only explicit asks
    int effectiveMax = Math.min(request.maxRows() == null ? props.maxRows() : request.maxRows(),
        props.maxRowsHard());

    String sql;
    boolean masked;
    boolean rowFiltered;
    Boolean bypassed;
    try {
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
      sql = statementView.rewrittenSql();
      masked = statementView.masked();
      rowFiltered = statementView.rowFiltered();
      bypassed = null;
    } catch (QueryException rewriteFailure) {
      if (!instance.passthroughOnRewriteFailure() || !isPlainRead(request.sql())) {
        throw rewriteFailure;
      }
      LOG.warn("rewrite failed for instance '{}' ({}): running the ORIGINAL query because "
              + "onRewriteFailure=PASSTHROUGH — this access is unmasked and audited",
          instance.name(), rewriteFailure.getMessage());
      sql = request.sql();
      masked = false;
      rowFiltered = false;
      bypassed = true;
      MeterRegistry registry = meters == null ? null : meters.getIfAvailable();
      if (registry != null) {
        registry.counter("sqlmask.query.rewrite.bypass.total",
            "instance", instance.name()).increment();
      }
    }

    QuerySubmitter submitter = submitters.forType(instance.effectiveSubmitter());
    try {
      return submitter.submit(new SubmitRequest(instance.name(), engine.id(),
          instance.connection(), sql, effectiveMax, props, registration,
          masked, rowFiltered, Boolean.TRUE.equals(request.includeRewrittenSql()),
          start, bypassed));
    } catch (SQLException e) {
      // submitters classify their own SQLExceptions; this is a defensive net
      throw new QueryException(QueryException.QUERY_ERROR, e.getMessage(), e);
    }
  }

  /** True when the statement is a plain read: optional leading comments and
   * whitespace, then SELECT or WITH. Write statements never pass through. */
  static boolean isPlainRead(String sql) {
    String body = sql == null ? "" : sql.trim();
    while (true) {
      if (body.startsWith("/*")) {
        int end = body.indexOf("*/");
        if (end < 0) {
          return false;
        }
        body = body.substring(end + 2).trim();
        continue;
      }
      if (body.startsWith("--")) {
        int end = body.indexOf('\n');
        if (end < 0) {
          return false;
        }
        body = body.substring(end + 1).trim();
        continue;
      }
      break;
    }
    String upper = body.toUpperCase(java.util.Locale.ROOT);
    return upper.startsWith("SELECT") || upper.startsWith("WITH");
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
