package io.sqlmask.server;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditEvents;
import io.sqlmask.audit.AuditRecorder;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.rewrite.RewriteEngine;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SQL rewriting endpoint. The request carries its own YAML metadata and SQL
 * text; the whole input is processed atomically and any failure is reported
 * as a structured error. An optional Ranger-style {@code policyYaml} and
 * query subject ({@code user}/{@code groups}) select subject-aware policies;
 * when given, the metadata's own policy sections must be empty. Every request
 * emits exactly one REWRITE audit event (spec §5.1) — success or failure —
 * and failures are rethrown untouched.
 */
@RestController
@RequestMapping("/api")
public class RewriteController {

  private final RewriteEngine engine;
  private final AuditRecorder audit;
  private final RewriteMetrics metrics;

  public RewriteController(RewriteEngine engine, AuditRecorder audit, RewriteMetrics metrics) {
    this.engine = engine;
    this.audit = audit;
    this.metrics = metrics;
  }

  @PostMapping("/rewrite")
  public RewriteResponse rewrite(@RequestBody RewriteRequest request,
      HttpServletRequest httpRequest) {
    String dialect = request == null || request.dialect() == null || request.dialect().isBlank()
        ? "postgresql"
        : request.dialect();
    long start = System.nanoTime();
    try {
      if (request == null || request.metadataYaml() == null || request.metadataYaml().isBlank()) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "metadataYaml is required: paste the YAML configuration declaring tables, "
                + "columns and masking policies");
      }
      if (request.sql() == null || request.sql().isBlank()) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "sql is required: provide at least one SELECT statement");
      }
      List<StatementRewrite> statements = engine.rewrite(
          request.metadataYaml(), request.policyYaml(), request.sql(), dialect,
          Subject.of(request.user(), request.groups()));
      metrics.success(dialect, statements);
      audit.record(AuditEvent.rewrite("sql-mask", AuditEvent.SUCCESS,
          elapsedMs(start), AuditEvents.sourceIp(httpRequest),
          AuditEvents.authKind(httpRequest), request.user(), request.groups(), dialect,
          statements.size(),
          statements.stream().anyMatch(StatementRewrite::masked),
          statements.stream().anyMatch(StatementRewrite::rowFiltered),
          request.sql(), RewriteEngine.join(statements), null, null));
      return new RewriteResponse(statements, RewriteEngine.join(statements));
    } catch (SqlMaskException e) {
      audit.record(failureEvent(httpRequest, start, request, e.getCode().name(), e.getMessage()));
      metrics.failure(dialect, e.getCode().name());
      throw e;
    } catch (RuntimeException e) {
      String name = e.getClass().getSimpleName();
      audit.record(failureEvent(httpRequest, start, request, name,
          e.getMessage() == null ? name : e.getMessage()));
      metrics.failure(dialect, "REWRITE_ERROR");
      throw e;
    } finally {
      metrics.duration(dialect, start);
    }
  }

  /** Builds the FAILURE event: exactly one per failed request (spec §5.1). */
  private AuditEvent failureEvent(HttpServletRequest httpRequest, long start,
      RewriteRequest request, String errorCode, String errorMessage) {
    return AuditEvent.rewrite("sql-mask", AuditEvent.FAILURE, elapsedMs(start),
        AuditEvents.sourceIp(httpRequest), AuditEvents.authKind(httpRequest),
        request == null ? null : request.user(), request == null ? null : request.groups(),
        request == null ? null : request.dialect(), null, null, null, null, null,
        errorCode, errorMessage);
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  /** Per-statement rewrite request. */
  public record RewriteRequest(String metadataYaml, String policyYaml, String sql,
      String dialect, String user, List<String> groups) {
  }

  /** Per-statement rewrite response plus the combined script. */
  public record RewriteResponse(List<StatementRewrite> statements, String rewrittenSql) {
  }
}
