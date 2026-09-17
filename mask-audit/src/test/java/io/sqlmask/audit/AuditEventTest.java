package io.sqlmask.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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
  void detailDropsNullValuedEntriesInsteadOfThrowing() {
    // Map.of 禁 null 值，须用 HashMap 构造含 null 的 detail（如 policyType 缺省）
    var raw = new java.util.HashMap<String, Object>();
    raw.put("a", "x");
    raw.put("b", null);
    AuditEvent e = AuditEvent.adminChange("sql-mask", AuditEvent.FAILURE, 3L, "127.0.0.1",
        "ANONYMOUS", "CREATE", "POLICY", "crm", "p1", raw, "CONFIG_ERROR", "boom");
    assertEquals(java.util.Map.of("a", "x"), e.detail());
    raw.put("c", "y"); // 防御性拷贝：后续改动不影响已构造事件
    assertEquals(java.util.Map.of("a", "x"), e.detail());

    var allNull = new java.util.HashMap<String, Object>();
    allNull.put("policyType", null);
    AuditEvent n = AuditEvent.adminChange("sql-mask", AuditEvent.FAILURE, 3L, "127.0.0.1",
        "ANONYMOUS", "CREATE", "POLICY", "crm", "p1", allNull, "CONFIG_ERROR", "boom");
    assertNull(n.detail());
  }
}
