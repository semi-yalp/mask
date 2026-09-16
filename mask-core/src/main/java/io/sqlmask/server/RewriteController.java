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

  public RewriteController(RewriteEngine engine, AuditRecorder audit) {
    this.engine = engine;
    this.audit = audit;
  }

  @PostMapping("/rewrite")
  public RewriteResponse rewrite(@RequestBody RewriteRequest request,
      HttpServletRequest httpRequest) {
    long start = System.nanoTime();
    if (request == null || request.metadataYaml() == null || request.metadataYaml().isBlank()) {
      throw fail(httpRequest, start, null, SqlMaskException.Code.CONFIG_ERROR,
          "metadataYaml is required: paste the YAML configuration declaring tables, "
              + "columns and masking policies");
    }
    if (request.sql() == null || request.sql().isBlank()) {
      throw fail(httpRequest, start, request, SqlMaskException.Code.CONFIG_ERROR,
          "sql is required: provide at least one SELECT statement");
    }
    String dialect = request.dialect() == null || request.dialect().isBlank()
        ? "postgresql"
        : request.dialect();
    List<StatementRewrite> statements;
    try {
      statements = engine.rewrite(
          request.metadataYaml(), request.policyYaml(), request.sql(), dialect,
          Subject.of(request.user(), request.groups()));
    } catch (SqlMaskException e) {
      throw recorded(httpRequest, start, request, e.getCode(), e);
    } catch (RuntimeException e) {
      throw recorded(httpRequest, start, request, null, e);
    }
    audit.record(AuditEvent.rewrite("sql-mask", AuditEvent.SUCCESS,
        elapsedMs(start), AuditEvents.sourceIp(httpRequest),
        AuditEvents.authKind(httpRequest), request.user(), request.groups(), dialect,
        statements.size(),
        statements.stream().anyMatch(StatementRewrite::masked),
        statements.stream().anyMatch(StatementRewrite::rowFiltered),
        request.sql(), RewriteEngine.join(statements), null, null));
    return new RewriteResponse(statements, RewriteEngine.join(statements));
  }

  /** Guards path: record the FAILURE event then raise the 400. */
  private SqlMaskException fail(HttpServletRequest httpRequest, long start,
      RewriteRequest request, SqlMaskException.Code code, String message) {
    audit.record(AuditEvent.rewrite("sql-mask", AuditEvent.FAILURE, elapsedMs(start),
        AuditEvents.sourceIp(httpRequest), AuditEvents.authKind(httpRequest),
        request == null ? null : request.user(), request == null ? null : request.groups(),
        request == null ? null : request.dialect(), null, null, null, null, null,
        code.name(), message));
    return new SqlMaskException(code, message);
  }

  /** Records the FAILURE event and returns the exception to rethrow untouched. */
  private RuntimeException recorded(HttpServletRequest httpRequest, long start,
      RewriteRequest request, SqlMaskException.Code code, RuntimeException e) {
    audit.record(AuditEvent.rewrite("sql-mask", AuditEvent.FAILURE, elapsedMs(start),
        AuditEvents.sourceIp(httpRequest), AuditEvents.authKind(httpRequest),
        request == null ? null : request.user(), request == null ? null : request.groups(),
        request == null ? null : request.dialect(), null, null, null, null, null,
        code == null ? e.getClass().getSimpleName() : code.name(),
        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    return e;
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
