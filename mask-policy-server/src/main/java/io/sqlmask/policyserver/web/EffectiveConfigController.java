package io.sqlmask.policyserver.web;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditEvents;
import io.sqlmask.audit.AuditProperties;
import io.sqlmask.audit.AuditRecorder;
import io.sqlmask.config.source.EffectiveConfigResponse;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.server.EffectiveMetrics;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Policy-service data plane: the subject-parameterized compiled effective
 * config. Absent user/groups is the anonymous subject (wildcard policies only).
 * Every pull emits an EFFECTIVE_PULL audit event unless
 * {@code audit.effective-pull.enabled=false} (spec §5.1/§5.3). Pull metrics
 * ({@code sqlmask.effective.*}) are recorded alongside the audit event —
 * audit records the event, metrics record the counts/timers/gauges.
 */
@RestController
public class EffectiveConfigController {

  private final PolicyService service;
  private final AuditRecorder audit;
  private final AuditProperties auditProperties;
  private final EffectiveMetrics metrics;

  public EffectiveConfigController(PolicyService service, AuditRecorder audit,
      AuditProperties auditProperties, EffectiveMetrics metrics) {
    this.service = service;
    this.audit = audit;
    this.auditProperties = auditProperties;
    this.metrics = metrics;
  }

  @GetMapping("/api/effective/{instance}")
  public EffectiveConfigResponse effective(@PathVariable("instance") String instance,
      @RequestParam(value = "user", required = false) String user,
      @RequestParam(value = "groups", required = false) List<String> groups,
      HttpServletRequest httpRequest) {
    long start = System.nanoTime();
    try {
      EffectiveConfigResponse response = service.effective(instance, Subject.of(user, groups));
      metrics.success(instance, response, start);
      record(httpRequest, instance, user, groups, start, AuditEvent.SUCCESS, null, null);
      return response;
    } catch (RuntimeException e) {
      if (e instanceof SqlMaskException sme
          && sme.getCode() == SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND) {
        metrics.notFound(start);
      } else {
        metrics.failure(instance, start);
      }
      record(httpRequest, instance, user, groups, start, AuditEvent.FAILURE,
          e instanceof SqlMaskException sme ? sme.getCode().name() : e.getClass().getSimpleName(),
          e.getMessage());
      throw e;
    }
  }

  private void record(HttpServletRequest httpRequest, String instance, String user,
      List<String> groups, long startNanos, String outcome, String errorCode,
      String errorMessage) {
    if (!auditProperties.isEffectivePullEnabled()) {
      return;
    }
    audit.record(AuditEvent.effectivePull("sql-mask", outcome,
        (System.nanoTime() - startNanos) / 1_000_000, AuditEvents.sourceIp(httpRequest),
        AuditEvents.authKind(httpRequest), user, groups, instance, errorCode, errorMessage));
  }
}
