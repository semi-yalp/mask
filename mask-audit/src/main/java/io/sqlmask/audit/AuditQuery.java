package io.sqlmask.audit;

import java.time.Instant;

/** Fixed-condition audit search (spec §6): no DSL passthrough. */
public record AuditQuery(String eventType, String outcome, String instance,
    String resourceType, String action, String user, Instant from, Instant to,
    int page, int size) {
}
