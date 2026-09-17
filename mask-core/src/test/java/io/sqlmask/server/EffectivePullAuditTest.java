package io.sqlmask.server;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import io.sqlmask.error.SqlMaskException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "audit.effective-pull.enabled=true")
@AutoConfigureMockMvc
class EffectivePullAuditTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private AuditRecorder recorder;

  @Autowired
  private PolicyService service;

  /** 用例自备实例（经 service 直建，绕过 API 故不产生审计事件）。 */
  private void ensurePullInstance() {
    try {
      service.createInstance("pull_it", "postgresql", List.of(
          new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    } catch (SqlMaskException alreadyExists) {
      // 前序用例已创建
    }
  }

  /** 缺失实例 → 400/POLICY_INSTANCE_NOT_FOUND，仍发一条 FAILURE 事件并携带 subject。 */
  @Test
  void missingInstancePullEmitsFailureEventWithSubject() throws Exception {
    mvc.perform(get("/api/effective/missing?user=alice&groups=devs"))
        .andExpect(status().isBadRequest());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, times(1)).record(captor.capture());
    AuditEvent e = captor.getValue();
    assertEquals(AuditEvent.EFFECTIVE_PULL, e.eventType());
    assertEquals(AuditEvent.FAILURE, e.outcome());
    assertEquals("missing", e.instance());
    assertEquals("alice", e.actorUser());
    assertEquals(List.of("devs"), e.actorGroups());
    assertEquals("POLICY_INSTANCE_NOT_FOUND", e.errorCode());
    assertEquals("sql-mask", e.service());
  }

  /** 成功拉取 → 恰好一条 SUCCESS 事件（开关开时每次拉取一条）。 */
  @Test
  void successfulPullEmitsExactlyOneSuccessEvent() throws Exception {
    ensurePullInstance();
    mvc.perform(get("/api/effective/pull_it").param("user", "alice").param("groups", "devs"))
        .andExpect(status().isOk());
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, times(1)).record(captor.capture());
    AuditEvent e = captor.getValue();
    assertEquals(AuditEvent.EFFECTIVE_PULL, e.eventType());
    assertEquals(AuditEvent.SUCCESS, e.outcome());
    assertEquals("pull_it", e.instance());
    assertEquals("alice", e.actorUser());
    assertEquals(List.of("devs"), e.actorGroups());
    assertEquals("sql-mask", e.service());
    assertNull(e.errorCode());
  }
}

/** 开关关闭 → 业务结果不变且零事件。 */
@SpringBootTest(properties = "audit.effective-pull.enabled=false")
@AutoConfigureMockMvc
class EffectivePullSwitchOffAuditTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private AuditRecorder recorder;

  @Test
  void switchOffKeepsBusinessOutcomeAndEmitsZeroEvents() throws Exception {
    mvc.perform(get("/api/effective/missing?user=alice&groups=devs"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(recorder);
  }
}
