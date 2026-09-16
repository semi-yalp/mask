package io.sqlmask.policyserver.web;

import io.sqlmask.audit.AuditEvent;
import io.sqlmask.audit.AuditRecorder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminAuditTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private AuditRecorder recorder;

  private List<AuditEvent> recorded() {
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    org.mockito.Mockito.verify(recorder, atLeastOnce()).record(captor.capture());
    return captor.getAllValues();
  }

  @Test
  @Order(1)
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
  @Order(2)
  void failedPolicyCreateEmitsFailureWithConfigError() throws Exception {
    mvc.perform(post("/api/instances/missing/policies").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "p1", "policyType": "datamask",
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
    assertEquals("POLICY_INSTANCE_NOT_FOUND", e.errorCode());
  }

  @Test
  @Order(3)
  void udfRegisterAndDeleteEmitEvents() throws Exception {
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
  @Order(4)
  void policyCreateUpdateDeleteAndInstanceDeleteEmitEvents() throws Exception {
    mvc.perform(post("/api/instances/crm/policies").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "rowf", "policyType": "row_filter", "isEnabled": true,
                 "resource": {"catalog": "crm", "schema": "public", "table": "customer"},
                 "subjects": {"groups": ["*"]}, "filterExpr": "id > 0"}
                """))
        .andExpect(status().isOk());
    mvc.perform(put("/api/instances/crm/policies/rowf").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "rowf", "policyType": "row_filter", "isEnabled": true,
                 "resource": {"catalog": "crm", "schema": "public", "table": "customer"},
                 "subjects": {"groups": ["*"]}, "filterExpr": "id > 1"}
                """))
        .andExpect(status().isOk());
    mvc.perform(delete("/api/instances/crm/policies/rowf")).andExpect(status().isOk());
    mvc.perform(delete("/api/instances/crm")).andExpect(status().isOk());
    List<AuditEvent> events = recorded();
    List<String> rowfActions = events.stream()
        .filter(x -> "POLICY".equals(x.resourceType()) && "rowf".equals(x.resourceName()))
        .map(AuditEvent::action).toList();
    assertEquals(List.of("CREATE", "UPDATE", "DELETE"), rowfActions);
    AuditEvent instanceDelete = events.stream()
        .filter(x -> "INSTANCE".equals(x.resourceType()) && "crm".equals(x.resourceName()))
        .reduce((a, b) -> b).orElseThrow();
    assertEquals("DELETE", instanceDelete.action());
    assertEquals(AuditEvent.SUCCESS, instanceDelete.outcome());
  }
}
