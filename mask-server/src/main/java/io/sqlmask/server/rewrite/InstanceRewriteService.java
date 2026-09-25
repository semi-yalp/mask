package io.sqlmask.server.rewrite;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditEvents;
import io.sqlmask.audit.AuditRecorder;
import io.sqlmask.common.metrics.RewriteMetrics;
import io.sqlmask.dialect.DialectFeatures;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.rewrite.RewriteEngine;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import io.sqlmask.server.InheritedPolicyRegistrar;
import io.sqlmask.server.RewriteController.RewriteResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Subject-aware instance rewrite over the {@link RewriteContextRepository}:
 * one place that resolves the caller's subject, assembles the compiled
 * context and runs the kernel. Both the REST surface
 * ({@code POST /api/rewrite/instances/{name}}) and the in-process query
 * gateway go through it, so they can never drift apart — and the one REWRITE
 * audit event / metrics record per rewrite is emitted here exactly once
 * (基线契约:每请求恰好一条 REWRITE 事件,成功与失败 alike)。
 *
 * <p>复制表策略继承(arch-v2 合并 rr):改写产物携带继承列(inheritOnCopy)时,
 * 返回前调用 {@link InheritedPolicyRegistrar} 把目标表结构与列策略注册到策略/元
 * 数据服务;任一注册失败即整体失败(fail-closed)。legacy 通道(inline YAML/CLI)
 * 没有注册能力,在 {@code RewriteController} 显式拒绝继承语句。</p>
 */
@Service
public class InstanceRewriteService {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(InstanceRewriteService.class);

  private final RewriteEngine engine;
  private final RewriteContextRepository contexts;
  private final MetadataService metadata;
  private final ObjectProvider<InheritedPolicyRegistrar> registrar;
  private final ObjectProvider<AuditRecorder> audit;
  private final ObjectProvider<RewriteMetrics> metrics;
  private final ObjectProvider<jakarta.servlet.http.HttpServletRequest> currentRequest;

  public InstanceRewriteService(RewriteEngine engine, RewriteContextRepository contexts,
                                MetadataService metadata,
                                ObjectProvider<InheritedPolicyRegistrar> registrar,
                                ObjectProvider<AuditRecorder> audit,
                                ObjectProvider<RewriteMetrics> metrics,
                                ObjectProvider<jakarta.servlet.http.HttpServletRequest> currentRequest) {
    this.engine = engine;
    this.contexts = contexts;
    this.metadata = metadata;
    this.registrar = registrar;
    this.audit = audit;
    this.metrics = metrics;
    this.currentRequest = currentRequest;
  }

  public RewriteResponse rewrite(String instance, String sql, String user, List<String> groups) {
    if (sql == null || sql.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "sql is required");
    }
    long start = System.nanoTime();
    List<StatementRewrite> statements;
    String dialect = contextDialect(instance);
    try {
      RewriteContextRepository.Context context = contexts.load(instance, Subject.of(user, groups));
      dialect = context.dialect();
      statements =
          engine.rewrite(context.config(), sql, context.dialect(), featuresOf(instance));
    } catch (SqlMaskException e) {
      record(instance, user, start, AuditEvent.FAILURE, 0, false, dialect,
          e.getCode().name(), e.getMessage(), List.of());
      throw e;
    }
    InheritedPolicyRegistrar inheritedRegistrar = registrar.getIfAvailable();
    if (inheritedRegistrar != null
        && statements.stream().anyMatch(s -> !s.inheritedColumns().isEmpty())) {
      try {
        inheritedRegistrar.register(instance, user, statements);
      } catch (SqlMaskException e) {
        record(instance, user, start, AuditEvent.FAILURE, statements.size(),
            anyTouched(statements), dialect, e.getCode().name(), e.getMessage(),
            statements);
        throw e;
      }
    }
    record(instance, user, start, AuditEvent.SUCCESS, statements.size(),
        anyTouched(statements), dialect, null, null, statements);
    return new RewriteResponse(statements, RewriteEngine.join(statements));
  }

  private static boolean anyTouched(List<StatementRewrite> statements) {
    return statements.stream().anyMatch(s -> s.masked() || s.rowFiltered());
  }

  /** 恰好一条 REWRITE 事件(服务名 sql-mask 保持与基线口径一致)。 */
  private void record(String instance, String user, long startNanos, String outcome,
      int statementCount, boolean touched, String dialect, String errorCode,
      String errorMessage, List<StatementRewrite> statementsForMetrics) {
    AuditRecorder recorder = audit.getIfAvailable();
    if (recorder != null) {
      try {
        jakarta.servlet.http.HttpServletRequest httpRequest = currentRequest.getIfAvailable();
        recorder.record(AuditEvent.rewrite("sql-mask", outcome,
            (System.nanoTime() - startNanos) / 1_000_000,
            httpRequest != null ? AuditEvents.sourceIp(httpRequest) : null,
            httpRequest != null ? AuditEvents.authKind(httpRequest) : "ANONYMOUS",
            user, null, dialect, statementCount, touched, null, null, null,
            errorCode, errorMessage));
      } catch (RuntimeException e) {
        LOG.warn("rewrite audit record failed: {}", e.getMessage());
      }
    }
    RewriteMetrics rewriteMetrics = metrics.getIfAvailable();
    if (rewriteMetrics != null) {
      if (AuditEvent.SUCCESS.equals(outcome)) {
        rewriteMetrics.success(dialect, statementsForMetrics);
      } else {
        rewriteMetrics.failure(dialect, errorCode == null ? "UNKNOWN" : errorCode);
      }
      rewriteMetrics.duration(dialect, startNanos);
    }
  }

  /** The dialect for audit/metrics context; unknown instance → blank. */
  private String contextDialect(String instance) {
    try {
      return metadata.get(instance).dialect();
    } catch (SqlMaskException e) {
      return "";
    }
  }

  /** Instance-scoped syntax-extension overrides; StarRocks (served through
   * the mysql dialect) defaults to INSERT OVERWRITE support because StarRocks
   * 3.x has the statement while stock MySQL does not. */
  private DialectFeatures featuresOf(String instance) {
    try {
      var row = metadata.get(instance);
      boolean insertOverwrite = row.insertOverwrite() != null
          ? row.insertOverwrite()
          : "starrocks".equals(row.effectiveEngine());
      return new DialectFeatures(row.topN(), insertOverwrite);
    } catch (SqlMaskException e) {
      // unknown instance surfaces from the context load with the right code;
      // here the feature probe simply falls back to dialect defaults
      return DialectFeatures.DEFAULTS;
    }
  }
}
