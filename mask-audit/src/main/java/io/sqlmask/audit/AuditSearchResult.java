package io.sqlmask.audit;

import java.util.List;
import java.util.Map;

/**
 * One page of audit search results: {@code total} is the number of matching
 * events and {@code events} are the page's hit sources as generic maps
 * (documents keep their stored JSON shape).
 */
public record AuditSearchResult(long total, List<Map<String, Object>> events) {
}
