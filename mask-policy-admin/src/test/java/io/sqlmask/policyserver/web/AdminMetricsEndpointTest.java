package io.sqlmask.policyserver.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Management-plane change metrics (spec §3.2) over the admin REST endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminMetricsEndpointTest {

  private static final String INSTANCE = "metrics_pg";

  @Autowired
  private MockMvc mvc;

  @Autowired
  private MeterRegistry registry;

  @BeforeEach
  void removeLeftoverInstance() throws Exception {
    mvc.perform(delete("/api/instances/" + INSTANCE));
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
    mvc.perform(post("/api/instances/" + INSTANCE + "/policies")
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
