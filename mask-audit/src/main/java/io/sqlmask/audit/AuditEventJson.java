package io.sqlmask.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps an {@link AuditEvent} to its Elasticsearch document: null fields are
 * omitted, {@code @timestamp} is epoch millis (the mapping declares a date),
 * and SQL texts are truncated to {@code sqlMaxChars} with a single
 * {@code sqlTruncated:true} marker when any field was cut.
 */
public final class AuditEventJson {

  private AuditEventJson() {
  }

  public static Map<String, Object> toDocument(AuditEvent event, int sqlMaxChars) {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("@timestamp", event.timestamp().toEpochMilli());
    put(doc, "eventType", event.eventType());
    put(doc, "service", event.service());
    put(doc, "outcome", event.outcome());
    put(doc, "durationMs", event.durationMs());
    put(doc, "sourceIp", event.sourceIp());
    if (event.actorUser() != null || event.actorGroups() != null || event.authKind() != null) {
      Map<String, Object> actor = new LinkedHashMap<>();
      put(actor, "user", event.actorUser());
      if (!event.actorGroups().isEmpty()) {
        actor.put("groups", event.actorGroups());
      }
      put(actor, "authKind", event.authKind());
      doc.put("actor", actor);
    }
    if (event.errorCode() != null || event.errorMessage() != null) {
      Map<String, Object> error = new LinkedHashMap<>();
      put(error, "code", event.errorCode());
      put(error, "message", event.errorMessage());
      doc.put("error", error);
    }
    put(doc, "dialect", event.dialect());
    put(doc, "statementCount", event.statementCount());
    put(doc, "masked", event.masked());
    put(doc, "rowFiltered", event.rowFiltered());
    boolean truncated = cut(doc, "originalSql", event.originalSql(), sqlMaxChars);
    truncated |= cut(doc, "rewrittenSql", event.rewrittenSql(), sqlMaxChars);
    if (truncated) {
      doc.put("sqlTruncated", Boolean.TRUE);
    }
    put(doc, "resourceType", event.resourceType());
    put(doc, "action", event.action());
    put(doc, "instance", event.instance());
    put(doc, "resourceName", event.resourceName());
    if (event.detail() != null && !event.detail().isEmpty()) {
      doc.put("detail", event.detail());
    }
    return doc;
  }

  private static void put(Map<String, Object> doc, String key, Object value) {
    if (value != null) {
      doc.put(key, value);
    }
  }

  /** Puts the (possibly cut) value; returns true when it was cut. */
  private static boolean cut(Map<String, Object> doc, String key, String value, int max) {
    if (value == null) {
      return false;
    }
    if (value.length() <= max) {
      doc.put(key, value);
      return false;
    }
    doc.put(key, value.substring(0, max));
    return true;
  }
}
