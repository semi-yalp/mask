package io.sqlmask.server;

import io.sqlmask.audit.AuditQuery;
import io.sqlmask.audit.AuditSearchClient;
import io.sqlmask.audit.AuditSearchResult;
import io.sqlmask.audit.AuditSearchUnavailableException;
import io.sqlmask.error.SqlMaskException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Fixed-condition audit search over ES (spec §6). Newest first, offset paging,
 * time range capped at 7 days; no DSL passthrough — free-form exploration
 * belongs to Kibana.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditQueryController {

  private static final long MAX_RANGE_SECONDS = 7 * 24 * 3600L;
  private static final int DEFAULT_SIZE = 50;
  private static final int MAX_SIZE = 200;

  private final AuditSearchClient search;

  /**
   * The search client bean is conditional on {@code audit.enabled=true}; an
   * absent client (audit disabled) must not break application startup — the
   * endpoint responds 502 {@code AUDIT_SEARCH_UNAVAILABLE} instead.
   */
  public AuditQueryController(ObjectProvider<AuditSearchClient> searchProvider) {
    this.search = searchProvider.getIfAvailable();
  }

  public record AuditQueryResponse(long total, int page, int size,
      List<?> events) {
  }

  @GetMapping("/events")
  public AuditQueryResponse events(
      @RequestParam(value = "eventType", required = false) String eventType,
      @RequestParam(value = "outcome", required = false) String outcome,
      @RequestParam(value = "instance", required = false) String instance,
      @RequestParam(value = "resourceType", required = false) String resourceType,
      @RequestParam(value = "action", required = false) String action,
      @RequestParam(value = "user", required = false) String user,
      @RequestParam(value = "from", required = false) String from,
      @RequestParam(value = "to", required = false) String to,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "size", required = false) Integer size) {
    if (search == null) {
      throw new AuditSearchUnavailableException(
          "audit is disabled (audit.enabled=false)", null);
    }
    int pageSize = size == null ? DEFAULT_SIZE : size;
    if (pageSize < 1 || pageSize > MAX_SIZE) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "size must be between 1 and " + MAX_SIZE);
    }
    int pageNumber = page == null ? 0 : page;
    if (pageNumber < 0) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "page must be >= 0");
    }
    // ES index.max_result_window: from+size beyond 10000 makes the search fail
    // as "ES unavailable" (502) — answer a plain 400 instead
    if ((long) pageNumber * pageSize + pageSize > 10_000L) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "page*size exceeds the 10000-result search window; narrow the time range "
              + "or walk fewer pages");
    }
    Instant toAt = parseTime(to, "to", Instant.now());
    Instant fromAt = parseTime(from, "from", toAt.minusSeconds(24 * 3600L));
    if (!fromAt.isBefore(toAt)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "from must be before to");
    }
    if (toAt.getEpochSecond() - fromAt.getEpochSecond() > MAX_RANGE_SECONDS) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "time range must not exceed 7 days");
    }
    AuditSearchResult result = search.search(new AuditQuery(eventType, outcome, instance,
        resourceType, action, user, fromAt, toAt, pageNumber, pageSize));
    return new AuditQueryResponse(result.total(), pageNumber, pageSize, result.events());
  }

  private static Instant parseTime(String raw, String what, Instant fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          what + " must be ISO-8601 instant (e.g. 2026-09-16T00:00:00Z)");
    }
  }
}
