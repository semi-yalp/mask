package io.sqlmask.policyserver.web;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "audit.effective-pull.enabled=true")
@AutoConfigureMockMvc
class EffectivePullAuditTest {

  @Autowired
  private MockMvc mvc;

  @Autowired
  private PolicyService service;

  @MockBean
  private AuditRecorder recorder;

  @Test
  void effectivePullEmitsEventWithSubject() throws Exception {
    mvc.perform(get("/api/effective/missing?user=alice&groups=devs"))
        .andExpect(status().isNotFound()); // instance missing -> 404 POLICY_INSTANCE_NOT_FOUND
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, times(1)).record(captor.capture());
    AuditEvent e = captor.getValue();
    assertEquals(AuditEvent.EFFECTIVE_PULL, e.eventType());
    assertEquals(AuditEvent.FAILURE, e.outcome());
    assertEquals("missing", e.instance());
    assertEquals("alice", e.actorUser());
    assertEquals(java.util.List.of("devs"), e.actorGroups());
    assertEquals("POLICY_INSTANCE_NOT_FOUND", e.errorCode());
  }

  /**
   * Gap §4.6 #9: the SUCCESS side of the EFFECTIVE_PULL event (fields set on a
   * 200 pull) had no test — only FAILURE was covered. Runs order-independently
   * by clearing mock call history first.
   */
  @Test
  void successfulPullEmitsSuccessEventWithFields() throws Exception {
    org.mockito.Mockito.clearInvocations(recorder);
    String instance = "it_ef_" + UUID.randomUUID().toString().substring(0, 8);
    try {
      service.createInstance(instance, "postgresql",
          List.of(new TableDef("crm", "public", "customer",
              List.of(new ColumnDef("phone", "varchar")))));
      mvc.perform(get("/api/effective/" + instance)
              .param("user", "bob").param("groups", "g1,g2"))
          .andExpect(status().isOk());
    } finally {
      service.deleteInstance(instance);
    }
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, times(1)).record(captor.capture());
    AuditEvent e = captor.getValue();
    assertEquals(AuditEvent.EFFECTIVE_PULL, e.eventType());
    assertEquals(AuditEvent.SUCCESS, e.outcome());
    assertEquals(instance, e.instance());
    assertEquals("bob", e.actorUser());
    assertEquals(List.of("g1", "g2"), e.actorGroups());
    assertNull(e.errorCode());
    assertNull(e.errorMessage());
  }
}