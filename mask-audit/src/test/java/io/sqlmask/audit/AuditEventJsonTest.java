package io.sqlmask.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditEventJsonTest {

  private static final Instant T = Instant.parse("2026-09-16T12:34:56.789Z");

  private AuditEvent rewriteEvent(String originalSql, String rewrittenSql) {
    return new AuditEvent(T, AuditEvent.REWRITE, "sql-mask", AuditEvent.SUCCESS, 12L,
        "10.0.0.5", "alice", List.of("devs"), "ANONYMOUS", null, null, "postgresql",
        2, true, false, originalSql, rewrittenSql, null, null, null, null, null);
  }

  @Test
  void documentCarriesEpochMillisAndOmitsNulls() {
    Map<String, Object> doc = AuditEventJson.toDocument(rewriteEvent("SELECT 1", "SELECT 1"), 100);
    assertEquals(1789562096789L, doc.get("@timestamp"));
    assertEquals("REWRITE", doc.get("eventType"));
    assertEquals("sql-mask", doc.get("service"));
    assertEquals("postgresql", doc.get("dialect"));
    assertEquals(2, doc.get("statementCount"));
    assertNull(doc.get("resourceType"));
    assertNull(doc.get("detail"));
    assertFalse(doc.containsKey("sqlTruncated"));
    @SuppressWarnings("unchecked")
    Map<String, Object> actor = (Map<String, Object>) doc.get("actor");
    assertEquals("alice", actor.get("user"));
    assertEquals(List.of("devs"), actor.get("groups"));
    assertEquals("ANONYMOUS", actor.get("authKind"));
  }

  @Test
  void longSqlIsTruncatedAndFlagged() {
    String big = "x".repeat(30);
    Map<String, Object> doc = AuditEventJson.toDocument(rewriteEvent(big, "y".repeat(5)), 10);
    assertEquals("x".repeat(10), doc.get("originalSql"));
    assertEquals("y".repeat(5), doc.get("rewrittenSql"));
    assertEquals(Boolean.TRUE, doc.get("sqlTruncated"));
  }

  @Test
  void exactLengthIsNotTruncated() {
    String exact = "x".repeat(10);
    Map<String, Object> doc = AuditEventJson.toDocument(rewriteEvent(exact, null), 10);
    assertEquals(exact, doc.get("originalSql"));
    assertFalse(doc.containsKey("sqlTruncated"));
  }

  @Test
  void detailIsCopiedThroughWhenPresent() {
    AuditEvent e = AuditEvent.adminChange("sql-mask", AuditEvent.SUCCESS, 1L, null, "API_KEY",
        "CREATE", "POLICY", "crm", "mask-phone",
        Map.of("policyType", "datamask", "enabled", true), null, null);
    Map<String, Object> doc = AuditEventJson.toDocument(e, 100);
    assertEquals(Map.of("policyType", "datamask", "enabled", true), doc.get("detail"));
    assertTrue(doc.containsKey("resourceName"));
  }
}
