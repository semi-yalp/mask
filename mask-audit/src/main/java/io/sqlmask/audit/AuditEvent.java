package io.sqlmask.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One audit document (spec §3): a common envelope plus type-specific fields;
 * fields that do not apply stay null and are omitted when mapped to the ES
 * document. {@code sqlTruncated} is not carried here — truncation happens at
 * document-mapping time under the recorder's {@code sql-max-chars} setting.
 */
public record AuditEvent(
    Instant timestamp,
    String eventType,
    String service,
    String outcome,
    Long durationMs,
    String sourceIp,
    String actorUser,
    List<String> actorGroups,
    String authKind,
    String errorCode,
    String errorMessage,
    String dialect,
    Integer statementCount,
    Boolean masked,
    Boolean rowFiltered,
    String originalSql,
    String rewrittenSql,
    String resourceType,
    String action,
    String instance,
    String resourceName,
    Map<String, Object> detail) {

  public static final String REWRITE = "REWRITE";
  public static final String ADMIN_CHANGE = "ADMIN_CHANGE";
  public static final String EFFECTIVE_PULL = "EFFECTIVE_PULL";
  public static final String QUERY = "QUERY";
  public static final String SUCCESS = "SUCCESS";
  public static final String FAILURE = "FAILURE";

  public AuditEvent {
    timestamp = timestamp == null ? Instant.now() : timestamp;
    actorGroups = actorGroups == null ? List.of() : List.copyOf(actorGroups);
    detail = detail == null ? null : Map.copyOf(detail);
  }

  public static AuditEvent rewrite(String service, String outcome, Long durationMs,
      String sourceIp, String authKind, String user, List<String> groups, String dialect,
      Integer statementCount, Boolean masked, Boolean rowFiltered,
      String originalSql, String rewrittenSql, String errorCode, String errorMessage) {
    return new AuditEvent(null, REWRITE, service, outcome, durationMs, sourceIp, user, groups,
        authKind, errorCode, errorMessage, dialect, statementCount, masked, rowFiltered,
        originalSql, rewrittenSql, null, null, null, null, null);
  }

  public static AuditEvent adminChange(String service, String outcome, Long durationMs,
      String sourceIp, String authKind, String action, String resourceType, String instance,
      String resourceName, Map<String, Object> detail, String errorCode, String errorMessage) {
    return new AuditEvent(null, ADMIN_CHANGE, service, outcome, durationMs, sourceIp, null,
        List.of(), authKind, errorCode, errorMessage, null, null, null, null, null, null,
        resourceType, action, instance, resourceName, detail);
  }

  public static AuditEvent effectivePull(String service, String outcome, Long durationMs,
      String sourceIp, String authKind, String user, List<String> groups, String instance,
      String errorCode, String errorMessage) {
    return new AuditEvent(null, EFFECTIVE_PULL, service, outcome, durationMs, sourceIp, user,
        groups, authKind, errorCode, errorMessage, null, null, null, null, null, null,
        null, null, instance, null, null);
  }

  /** Data-plane query execution (mask-query). detail carries engine/rowCount/truncated. */
  public static AuditEvent query(String service, String outcome, Long durationMs, String sourceIp,
      String authKind, String user, List<String> groups, String instance, String dialect,
      Boolean masked, Boolean rowFiltered, String originalSql, String errorCode,
      String errorMessage, Map<String, Object> detail) {
    return new AuditEvent(null, QUERY, service, outcome, durationMs, sourceIp, user, groups,
        authKind, errorCode, errorMessage, dialect, 1, masked, rowFiltered, originalSql, null,
        null, null, instance, null, detail);
  }
}
