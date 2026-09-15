package io.sqlmask.server;

import io.sqlmask.policy.model.Subject;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

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
}
