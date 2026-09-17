package io.sqlmask.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.PolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminMetricsEndpointTest {

  private static final String INSTANCE = "metrics_pg";

  @Autowired
  private MockMvc mvc;

  @Autowired
  private MeterRegistry registry;

  @Autowired
  private PolicyService service;

  @BeforeEach
  void removeLeftoverInstance() {
    try {
      service.deleteInstance(INSTANCE);
    } catch (SqlMaskException notExists) {
      // 首次运行实例不存在，忽略
    }
  }

  @Test
  void createInstanceCountsSuccess() throws Exception {
    double before = counter("INSTANCE", "CREATE", "SUCCESS");
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content(new ObjectMapper().writeValueAsString(Map.of(
                "name", INSTANCE, "dialect", "postgresql", "tables", List.of(Map.of(
                    "catalog", "crm", "schema", "public", "name", "customer",
                    "columns", List.of(Map.of("name", "phone", "type", "varchar"))))))))
        .andExpect(status().isOk());
    assertThat(counter("INSTANCE", "CREATE", "SUCCESS")).isEqualTo(before + 1);
  }

  @Test
  void failedMutationCountsFailure() throws Exception {
    double before = counter("POLICY", "CREATE", "FAILURE");
    mvc.perform(post("/api/instances/no_such_instance_metrics/policies")
            .contentType(MediaType.APPLICATION_JSON)
            .content(new ObjectMapper().writeValueAsString(Map.of(
                "name", "metrics_pol", "policyType", "datamask", "isEnabled", true,
                "resource", Map.of("catalog", "crm", "schema", "public", "table", "customer",
                    "columns", List.of("phone")),
                "udf", "mask_phone", "arguments", List.of()))))
        .andExpect(status().is4xxClientError());
    assertThat(counter("POLICY", "CREATE", "FAILURE")).isEqualTo(before + 1);
  }

  private double counter(String resource, String action, String outcome) {
    return registry.counter("sqlmask.admin.requests",
        "resource_type", resource, "action", action, "outcome", outcome).count();
  }
}
