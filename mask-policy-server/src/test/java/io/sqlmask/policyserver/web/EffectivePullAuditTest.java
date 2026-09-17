package io.sqlmask.policyserver.web;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties = "audit.effective-pull.enabled=true")
@AutoConfigureMockMvc
class EffectivePullAuditTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private AuditRecorder recorder;

  @Test
  void effectivePullEmitsEventWithSubject() throws Exception {
    mvc.perform(get("/api/effective/missing?user=alice&groups=devs"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .status().isNotFound()); // instance missing -> 404 POLICY_INSTANCE_NOT_FOUND
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
}
