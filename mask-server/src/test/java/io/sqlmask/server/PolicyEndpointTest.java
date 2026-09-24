package io.sqlmask.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the mask-policy endpoint ({@code /api/policies/parse}) is
 * wired into the combined jar: the controller lives in io.sqlmask.policy and
 * only reaches the web context through the application's component scan.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PolicyEndpointTest {

  private static final String VALID_YAML = """
      policies:
        - name: mask-phone
          resources:
            - {catalog: crm, schema: public, table: customer, column: phone}
          dataMaskItems:
            - {users: ["alice"], udf: mask_phone}
      """;

  @Autowired
  private MockMvc mvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void parsesValidPolicyYaml() throws Exception {
    mvc.perform(post("/api/policies/parse")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("policyYaml", VALID_YAML))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.policies.length()").value(1))
        .andExpect(jsonPath("$.policies[0].name").value("mask-phone"))
        .andExpect(jsonPath("$.policies[0].type").value("data_mask"))
        .andExpect(jsonPath("$.policies[0].itemCount").value(1));
  }

  @Test
  void invalidYamlReturnsConfigError() throws Exception {
    mvc.perform(post("/api/policies/parse")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("policyYaml", "policies: [ {"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }
}
