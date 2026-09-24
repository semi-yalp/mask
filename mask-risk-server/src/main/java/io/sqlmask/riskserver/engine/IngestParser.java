package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskEvent;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses ingest documents into {@link RiskEvent} builders. The accepted shape
 * is exactly the audit document the mask-audit pipeline emits
 * ({@code @timestamp}, nested {@code actor}/{@code error}/{@code detail});
 * unknown fields are ignored so the contract can evolve.
 */
public final class IngestParser {

  private IngestParser() {
  }

  /** @throws IllegalArgumentException when the document is not usable at all. */
  public static RiskEvent.Builder parse(Map<String, Object> doc) {
    if (doc == null || doc.isEmpty()) {
      throw new IllegalArgumentException("empty ingest document");
    }
    RiskEvent.Builder builder = RiskEvent.builder()
        .timestamp(parseTimestamp(doc.get("@timestamp")))
        .service(text(doc, "service"))
        .eventType(orDefault(text(doc, "eventType"), "REWRITE"))
        .outcome(orDefault(text(doc, "outcome"), "SUCCESS"))
        .durationMs(longVal(doc.get("durationMs")))
        .sourceIp(text(doc, "sourceIp"))
        .dialect(text(doc, "dialect"))
        .statementCount(intVal(doc.get("statementCount")))
        .masked(boolVal(doc.get("masked")))
        .rowFiltered(boolVal(doc.get("rowFiltered")))
        .originalSql(text(doc, "originalSql"))
        .rewrittenSql(text(doc, "rewrittenSql"))
        .sqlTruncated(boolVal(doc.get("sqlTruncated")))
        .instance(text(doc, "instance"));

    Object actor = doc.get("actor");
    if (actor instanceof Map<?, ?> actorMap) {
      builder.user(text(actorMap, "user"));
      Object groups = actorMap.get("groups");
      if (groups instanceof List<?> list) {
        List<String> groupList = new ArrayList<>();
        for (Object g : list) {
          if (g != null) {
            groupList.add(String.valueOf(g));
          }
        }
        builder.groups(groupList);
      }
      builder.authKind(text(actorMap, "authKind"));
    }

    Object error = doc.get("error");
    if (error instanceof Map<?, ?> errorMap) {
      builder.errorCode(text(errorMap, "code"));
      builder.errorMessage(text(errorMap, "message"));
    }

    Object detail = doc.get("detail");
    if (detail instanceof Map<?, ?> detailMap) {
      @SuppressWarnings("unchecked")
      Map<String, Object> detailCopy = (Map<String, Object>) detailMap;
      builder.detail(detailCopy);
      Long rowCount = longVal(detailMap.get("rowCount"));
      if (rowCount == null) {
        rowCount = longVal(detailMap.get("rows"));
      }
      builder.rowCount(rowCount);
    }

    if (builder.build().originalSql() == null && builder.build().errorMessage() == null) {
      throw new IllegalArgumentException(
          "document carries neither originalSql nor error.message - not an audit event");
    }
    return builder;
  }

  private static Instant parseTimestamp(Object value) {
    if (value instanceof Number n) {
      return Instant.ofEpochMilli(n.longValue());
    }
    if (value instanceof String s && !s.isBlank()) {
      try {
        return Instant.parse(s);
      } catch (DateTimeParseException e) {
        try {
          return Instant.ofEpochMilli(Long.parseLong(s));
        } catch (NumberFormatException ignored) {
          // fall through to now()
        }
      }
    }
    return Instant.now();
  }

  private static String text(Map<?, ?> map, String key) {
    Object value = map.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Long longVal(Object value) {
    if (value instanceof Number n) {
      return n.longValue();
    }
    if (value instanceof String s && !s.isBlank()) {
      try {
        return Long.parseLong(s.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static Integer intVal(Object value) {
    Long l = longVal(value);
    return l == null ? null : l.intValue();
  }

  private static Boolean boolVal(Object value) {
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof String s && !s.isBlank()) {
      return Boolean.parseBoolean(s.trim());
    }
    return null;
  }
}
