package io.sqlmask.audit;

import java.util.List;
import java.util.Map;

public record AuditSearchResult(long total, List<Map<String, Object>> events) {
}
