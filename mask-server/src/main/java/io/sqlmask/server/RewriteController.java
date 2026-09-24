/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.sqlmask.server;
import io.sqlmask.common.metrics.EffectiveMetrics;
import io.sqlmask.common.metrics.RewriteMetrics;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditEvents;
import io.sqlmask.audit.AuditRecorder;
import io.sqlmask.config.source.ConfigSource;
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
 * Rewrites customer SQL through the masking engine. Each request is processed
 * atomically; any failure is reported as a structured error.
 * Every request emits exactly one REWRITE audit event (spec §5.1) — success
 * or failure — and failures are rethrown untouched. Metrics (spec §3.1):
 * every request counts duration, every outcome counts success/failure.
 */
@RestController
@RequestMapping("/api")
public class RewriteController {

  private final RewriteEngine engine;
  private final InstanceConfigSources sources;
  private final AuditRecorder audit;
  private final RewriteMetrics metrics;

  public RewriteController(RewriteEngine engine, InstanceConfigSources sources,
      AuditRecorder audit, RewriteMetrics metrics) {
    this.engine = engine;
    this.sources = sources;
    this.audit = audit;
    this.metrics = metrics;
  }

  @PostMapping("/rewrite")
  public RewriteResponse rewrite(@RequestBody RewriteRequest request,
      HttpServletRequest httpRequest) {
    long start = System.nanoTime();
    // Determined during the flow (instance mode resolves it from the config);
    // failures before that point record the "invalid" sentinel via normalize().
    String dialect = null;
    try {
      if (request == null || request.sql() == null || request.sql().isBlank()) {
        throw fail(httpRequest, start, null, SqlMaskException.Code.CONFIG_ERROR,
            "sql is required: provide at least one SELECT statement");
      }
      boolean hasInstance = request.instance() != null && !request.instance().isBlank();
      boolean hasYaml = request.metadataYaml() != null && !request.metadataYaml().isBlank();
      if (hasInstance == hasYaml) {
        throw fail(httpRequest, start, request, SqlMaskException.Code.CONFIG_ERROR,
            "exactly one of metadataYaml or instance is required: metadataYaml is required "
                + "for inline YAML mode, or pass the policy-service instance name to "
                + "rewrite with the compiled effective config");
      }
      Subject subject = Subject.of(request.user(), request.groups());
      if (hasInstance) {
        if (request.policyYaml() != null && !request.policyYaml().isBlank()) {
          throw fail(httpRequest, start, request, SqlMaskException.Code.CONFIG_ERROR,
              "policyYaml cannot be combined with instance: instance mode uses the "
                  + "policies already compiled into the effective config");
        }
        if (!sources.configured()) {
          throw fail(httpRequest, start, request, SqlMaskException.Code.CONFIG_ERROR,
              "policy.service.url (env POLICY_SERVICE_URL) is not configured: "
                  + "instance mode requires the policy service location");
        }
      }
      List<StatementRewrite> statements;
      try {
        if (hasInstance) {
          ConfigSource.ResolvedConfig resolved = sources.get(request.instance()).load(subject);
          dialect = resolved.dialect();
          statements = engine.rewrite(resolved.config(), null, request.sql(), dialect, subject);
        } else {
          dialect = request.dialect() == null || request.dialect().isBlank()
              ? "postgresql"
              : request.dialect();
          statements = engine.rewrite(request.metadataYaml(), request.policyYaml(),
              request.sql(), dialect, subject);
        }
      } catch (SqlMaskException e) {
        throw recorded(httpRequest, start, request, e.getCode(), e);
      } catch (RuntimeException e) {
        throw recorded(httpRequest, start, request, null, e);
      }
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
      metrics.failure(dialect, e.getCode().name());
      throw e;
    } catch (RuntimeException e) {
      metrics.failure(dialect, "REWRITE_ERROR");
      throw e;
    } finally {
      metrics.duration(dialect, start);
    }
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
  public record RewriteRequest(String metadataYaml, String policyYaml, String instance,
      String sql, String dialect, String user, List<String> groups) {
  }

  /** Per-statement rewrite response plus the combined script. */
  public record RewriteResponse(List<StatementRewrite> statements, String rewrittenSql) {
  }
}
