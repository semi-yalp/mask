package io.sqlmask.policyserver.web;

import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Data plane: subject-parameterized effective config over REST. */
@SpringBootTest
@AutoConfigureMockMvc
class EffectiveConfigEndpointTest {

  @Autowired
  private MockMvc mvc;

  @Autowired
  private PolicyService service;

  @BeforeEach
  void setUp() {
    try {
      service.createInstance("pg_prod", "postgresql", List.of(
          new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    } catch (io.sqlmask.error.SqlMaskException alreadyExists) {
      // 上下文复用
    }
  }

  @Test
  void returnsSubjectFilteredConfig() throws Exception {
    mvc.perform(get("/api/effective/pg_prod").param("user", "alice").param("groups", "a,b"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.instance").value("pg_prod"))
        .andExpect(jsonPath("$.dialect").value("postgresql"))
        .andExpect(jsonPath("$.configVersion").isNumber());
  }

  @Test
  void unknownInstanceMapsToNotFound() throws Exception {
    mvc.perform(get("/api/effective/nope").param("user", "alice"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("POLICY_INSTANCE_NOT_FOUND"));
  }

  /** Spec §9 full-flow leg: two subjects pull two different configs over REST. */
  @Test
  void differentSubjectsGetDifferentConfigs() throws Exception {
    try {
      service.createUdf("pg_prod", new UdfDefinition("mask_phone",
          List.of(new UdfDefinition.UdfSignature(List.of("varchar"), "varchar"))));
    } catch (io.sqlmask.error.SqlMaskException alreadyExists) {
      // 其它端点测试已注册
    }
    service.createPolicy("pg_prod", new PolicyEntity("e2e_alice_phone", PolicyType.DATAMASK,
        true, new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("alice"), Set.of()), "mask_phone", List.of(), null));
    try {
      mvc.perform(get("/api/effective/pg_prod").param("user", "alice"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.config.columns.length()").value(1))
          .andExpect(jsonPath("$.config.columns[0].policy").value("e2e_alice_phone"))
          .andExpect(jsonPath("$.policySummary.enabled").value(1));
      mvc.perform(get("/api/effective/pg_prod").param("user", "bob"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.config.columns.length()").value(0))
          .andExpect(jsonPath("$.policySummary.enabled").value(0));
    } finally {
      service.deletePolicy("pg_prod", "e2e_alice_phone");
    }
  }
}
