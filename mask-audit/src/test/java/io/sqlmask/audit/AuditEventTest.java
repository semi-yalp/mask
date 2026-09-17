package io.sqlmask.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuditEventTest {

  @Test
  void rewriteFactoryFillsEnvelope() {
    AuditEvent e = AuditEvent.rewrite("sql-mask", AuditEvent.SUCCESS, 12L, "10.0.0.5",
        "ANONYMOUS", "alice", List.of("devs"), "postgresql", 2, true, false,
        "SELECT 1", "SELECT 1", null, null);
    assertEquals(AuditEvent.REWRITE, e.eventType());
    assertEquals(AuditEvent.SUCCESS, e.outcome());
    assertEquals("sql-mask", e.service());
    assertEquals(12L, e.durationMs());
    assertEquals("postgresql", e.dialect());
    assertEquals(Boolean.TRUE, e.masked());
    assertNull(e.resourceType());
    assertNotNull(e.timestamp());
  }

  @Test
  void adminChangeAndEffectivePullFactoriesSetTypeOnlyFields() {
    AuditEvent a = AuditEvent.adminChange("sql-mask", AuditEvent.FAILURE, 3L, "127.0.0.1",
        "API_KEY", "CREATE", "INSTANCE", null, "crm", java.util.Map.of("dialect", "postgresql"),
        "CONFIG_ERROR", "boom");
    assertEquals(AuditEvent.ADMIN_CHANGE, a.eventType());
    assertEquals("INSTANCE", a.resourceType());
    assertEquals("crm", a.resourceName());
    assertEquals("CONFIG_ERROR", a.errorCode());
    assertNull(a.dialect());

    AuditEvent p = AuditEvent.effectivePull("sql-mask", AuditEvent.SUCCESS, 1L, null,
        "API_KEY", "alice", List.of("devs"), "crm", null, null);
    assertEquals(AuditEvent.EFFECTIVE_PULL, p.eventType());
    assertEquals("crm", p.instance());
    assertEquals(List.of("devs"), p.actorGroups());
  }

  @Test
  void canonicalConstructorCopiesMutableInputsAndDefaultsTimestamp() {
    Instant now = Instant.now();
    java.util.List<String> groups = new java.util.ArrayList<>(List.of("devs"));
    AuditEvent e = new AuditEvent(now, AuditEvent.REWRITE, "sql-mask", AuditEvent.SUCCESS,
        null, null, null, groups, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null);
    groups.add("ops");
    assertEquals(List.of("devs"), e.actorGroups());
    assertNotSame(groups, e.actorGroups());
  }

  @Test
  void queryFactoryCarriesEnvelopeAndDetail() {
    AuditEvent event = AuditEvent.query("mask-query", AuditEvent.SUCCESS, 12L, "10.0.0.1",
        "API_KEY", "alice", List.of("devs"), "pg_prod", "postgresql", true, false,
        "SELECT phone FROM customer", null, null,
        java.util.Map.of("engine", "postgresql", "rowCount", 2, "truncated", false));
    assertThat(event.eventType()).isEqualTo("QUERY");
    assertThat(event.instance()).isEqualTo("pg_prod");
    assertThat(event.statementCount()).isEqualTo(1);
    assertThat(event.detail()).containsEntry("rowCount", 2);
  }
}
