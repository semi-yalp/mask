package io.sqlmask.server;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin-surface audit contract: every mutation endpoint emits exactly one
 * ADMIN_CHANGE event (SUCCESS or FAILURE) whose action/resourceType/instance/
 * resourceName/detail follow the audit binding table — summaries never carry
 * udf arguments or full request bodies.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminAuditTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private AuditRecorder recorder;

  @Autowired
  private PolicyService service;

  private List<AuditEvent> recorded() {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, atLeastOnce()).record(captor.capture());
    return captor.getAllValues();
  }

  @BeforeEach
  void cleanUp() {
    // 经 service 直接清理上个用例的遗留（不经 API，不产生审计事件）；
    // 有策略/UDF 时 store 拒绝删实例，需先删干净
    for (String policy : new String[] {"rowf", "p1"}) {
      try {
        service.deletePolicy("crm", policy);
      } catch (SqlMaskException absent) {
        // 无遗留
      }
    }
    try {
      service.deleteUdf("crm", "mask_phone");
    } catch (SqlMaskException absent) {
      // 无遗留
    }
    try {
      service.deleteInstance("crm");
    } catch (SqlMaskException absent) {
      // 无遗留
    }
  }

  /** 用例自备实例（经 service 直建，绕过 API 故不产生审计事件）。 */
  private void ensureCrmInstance() {
    try {
      service.createInstance("crm", "postgresql", List.of(
          new TableDef("crm", "public", "customer", List.of(new ColumnDef("id", "bigint")))));
    } catch (SqlMaskException alreadyExists) {
      // 前序用例经 API 创建且未删除
    }
  }

  @Test
  void createInstanceEmitsAdminChangeWithDetail() throws Exception {
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "crm", "dialect": "postgresql",
                 "tables": [{"catalog": "crm", "schema": "public", "name": "customer",
                   "columns": [{"name": "id", "type": "bigint"}]}]}
                """))
        .andExpect(status().isOk());
    AuditEvent e = recorded().stream()
        .filter(x -> "CREATE".equals(x.action()) && "INSTANCE".equals(x.resourceType()))
        .findFirst().orElseThrow();
    assertEquals(AuditEvent.ADMIN_CHANGE, e.eventType());
    assertEquals(AuditEvent.SUCCESS, e.outcome());
    assertEquals("crm", e.resourceName());
    assertEquals("postgresql", e.detail().get("dialect"));
    assertEquals(1, e.detail().get("tableCount"));
  }

  @Test
  void failedPolicyCreateEmitsFailureWithConfigError() throws Exception {
    // 未知 policyType：toModel 在审计包装内抛 CONFIG_ERROR → FAILURE 事件携带错误码
    mvc.perform(post("/api/instances/missing/policies").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "p1", "policyType": "bogus",
                 "resource": {"catalog": "c", "schema": "s", "table": "t", "columns": ["x"]},
                 "subjects": {"users": ["*"]}, "udf": "mask", "arguments": []}
                """))
        .andExpect(status().isBadRequest());
    AuditEvent e = recorded().stream()
        .filter(x -> x.outcome().equals(AuditEvent.FAILURE))
        .findFirst().orElseThrow();
    assertEquals("CREATE", e.action());
    assertEquals("POLICY", e.resourceType());
    assertEquals("missing", e.instance());
    assertEquals("CONFIG_ERROR", e.errorCode());
  }

  @Test
  void udfRegisterAndDeleteEmitEvents() throws Exception {
    ensureCrmInstance();
    mvc.perform(post("/api/instances/crm/udfs").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "mask_phone", "signatures": [{"params": ["varchar", "integer"],
                 "returns": "varchar"}]}
                """))
        .andExpect(status().isOk());
    mvc.perform(delete("/api/instances/crm/udfs/mask_phone"))
        .andExpect(status().isOk());
    List<AuditEvent> events = recorded();
    assertEquals("REGISTER", events.stream()
        .filter(x -> "UDF".equals(x.resourceType()) && "mask_phone".equals(x.resourceName()))
        .findFirst().orElseThrow().action());
    assertEquals("DELETE", events.stream()
        .filter(x -> "UDF".equals(x.resourceType()) && "mask_phone".equals(x.resourceName()))
        .reduce((a, b) -> b).orElseThrow().action());
  }

  @Test
  void policyDeleteAndInstanceDeleteEmitEvents() throws Exception {
    ensureCrmInstance();
    mvc.perform(post("/api/instances/crm/policies").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "rowf", "policyType": "row_filter", "isEnabled": true,
                 "resource": {"catalog": "crm", "schema": "public", "table": "customer"},
                 "subjects": {"groups": ["*"]}, "filterExpr": "id > 0"}
                """))
        .andExpect(status().isOk());
    mvc.perform(delete("/api/instances/crm/policies/rowf")).andExpect(status().isOk());
    mvc.perform(delete("/api/instances/crm")).andExpect(status().isOk());
    List<AuditEvent> events = recorded();
    assertEquals("DELETE", events.stream()
        .filter(x -> "POLICY".equals(x.resourceType()) && "rowf".equals(x.resourceName()))
        .reduce((a, b) -> b).orElseThrow().action());
    AuditEvent instanceDelete = events.stream()
        .filter(x -> "INSTANCE".equals(x.resourceType()) && "crm".equals(x.resourceName()))
        .reduce((a, b) -> b).orElseThrow();
    assertEquals("DELETE", instanceDelete.action());
    assertEquals(AuditEvent.SUCCESS, instanceDelete.outcome());
  }
}
