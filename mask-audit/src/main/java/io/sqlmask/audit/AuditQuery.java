package io.sqlmask.audit;

import java.time.Instant;

/**
 * Fixed-condition audit search request (spec §6). Conditions come only from
 * these fields — there is no query-DSL passthrough. Blank filters are
 * ignored, {@code from}/{@code to} bound the {@code @timestamp} range, and
 * paging is offset-based ({@code page * size}). Times are absolute
 * {@link Instant}s, serialized to Elasticsearch as epoch millis.
 */
public record AuditQuery(String eventType, String outcome, String instance,
    String resourceType, String action, String user, Instant from, Instant to,
    int page, int size) {
}
