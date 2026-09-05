package io.sqlmask.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ConfigControllerTest {

  private static final String YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - name: id
                type: bigint
              - name: phone
                type: varchar
              - name: created_at
                type: timestamp
              - name: amount
                type: decimal(10,2)
      columns:
        - catalog: crm
          schema: public
          table: customer
          column: phone
          policy: mask_phone
      policies:
        mask_phone:
          udf: mask_phone
          arguments: [3, 4]
      """;

  @Autowired
  private MockMvc mvc;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  void parsesYamlIntoStructuredEditorShape() throws Exception {
    mvc.perform(post("/api/config/parse")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("metadataYaml", YAML))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tables.length()").value(1))
        .andExpect(jsonPath("$.tables[0].catalog").value("crm"))
        .andExpect(jsonPath("$.tables[0].schema").value("public"))
        .andExpect(jsonPath("$.tables[0].name").value("customer"))
        .andExpect(jsonPath("$.tables[0].columns.length()").value(4))
        .andExpect(jsonPath("$.tables[0].columns[1].name").value("phone"))
        .andExpect(jsonPath("$.tables[0].columns[1].type").value("varchar"))
        .andExpect(jsonPath("$.tables[0].columns[3].type").value("decimal(10,2)"))
        .andExpect(jsonPath("$.columnPolicies.length()").value(1))
        .andExpect(jsonPath("$.columnPolicies[0].column").value("phone"))
        .andExpect(jsonPath("$.columnPolicies[0].policy").value("mask_phone"))
        .andExpect(jsonPath("$.policies.length()").value(1))
        .andExpect(jsonPath("$.policies[0].udf").value("mask_phone"))
        .andExpect(jsonPath("$.policies[0].arguments[0]").value(3))
        .andExpect(jsonPath("$.policies[0].arguments[1]").value(4));
  }

  @Test
  void invalidYamlReturnsStructuredBadRequest() throws Exception {
    mvc.perform(post("/api/config/parse")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "metadataYaml", "metadata: {tables: []}"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void blankBodyReturnsBadRequest() throws Exception {
    mvc.perform(post("/api/config/parse")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("metadataYaml", "  "))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message")
            .value(org.hamcrest.Matchers.containsString("metadataYaml is required")));
  }
}
