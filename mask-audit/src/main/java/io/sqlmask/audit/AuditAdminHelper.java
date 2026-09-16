package io.sqlmask.audit;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Wraps one admin mutation: runs {@code work}, measures duration and emits a
 * single ADMIN_CHANGE event (SUCCESS or FAILURE) — business exceptions are
 * rethrown untouched (spec §5.1). {@code errorCode} is host-supplied so this
 * module stays free of host exception types.
 */
public final class AuditAdminHelper {

  private final AuditRecorder recorder;
  private final String service;
  private final Function<Throwable, String> errorCode;

  public AuditAdminHelper(AuditRecorder recorder, String service,
      Function<Throwable, String> errorCode) {
    this.recorder = recorder;
    this.service = service;
    this.errorCode = errorCode;
  }

  public <T> T adminChange(HttpServletRequest request, String action, String resourceType,
      String instance, String resourceName, Supplier<Map<String, Object>> detail,
      Supplier<T> work) {
    long start = System.nanoTime();
    try {
      T result = work.get();
      recordSafely(AuditEvent.adminChange(service, AuditEvent.SUCCESS,
          elapsedMs(start), AuditEvents.sourceIp(request), AuditEvents.authKind(request),
          action, resourceType, instance, resourceName, safeDetail(detail), null, null));
      return result;
    } catch (RuntimeException e) {
      recordSafely(AuditEvent.adminChange(service, AuditEvent.FAILURE,
          elapsedMs(start), AuditEvents.sourceIp(request), AuditEvents.authKind(request),
          action, resourceType, instance, resourceName, safeDetail(detail),
          errorCode.apply(e), e.getMessage() == null ? e.getClass().getSimpleName()
              : e.getMessage()));
      throw e;
    }
  }

  /**
   * Audit is best-effort: {@link AuditRecorder#record} is contractually safe,
   * but if an implementation misbehaves its exception must never replace the
   * business result or the original error being rethrown.
   */
  private void recordSafely(AuditEvent event) {
    try {
      recorder.record(event);
    } catch (RuntimeException ignored) {
      // best-effort only — see javadoc
    }
  }

  private static Map<String, Object> safeDetail(Supplier<Map<String, Object>> detail) {
    try {
      return detail.get();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
