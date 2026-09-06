package io.sqlmask.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RewriteControllerTest {

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
              - name: email
                type: varchar
              - name: status
                type: varchar
              - name: name
                type: varchar
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

  private static final String ROW_FILTER_YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            rowFilter: "status = 'active'"
            columns:
              - name: id
                type: bigint
              - name: phone
                type: varchar
              - name: status
                type: varchar
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

  private MvcResult rewrite(String yaml, String sql) throws Exception {
    return mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                Map.of("metadataYaml", yaml, "sql", sql))))
        .andReturn();
  }

  @Test
  void rewritesStatementsAndReturnsPerStatementResults() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "metadataYaml", YAML,
                "sql", "SELECT id, phone FROM customer; SELECT id, name FROM customer;"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statements.length()").value(2))
        .andExpect(jsonPath("$.statements[0].ordinal").value(1))
        .andExpect(jsonPath("$.statements[0].masked").value(true))
        .andExpect(jsonPath("$.statements[0].rewrittenSql")
            .value(org.hamcrest.Matchers.containsString("mask_phone(r.phone, 3, 4) AS phone")))
        .andExpect(jsonPath("$.statements[1].masked").value(false))
        .andExpect(jsonPath("$.rewrittenSql")
            .value(org.hamcrest.Matchers.containsString("SELECT id, name")));
  }

  @Test
  void keepsCteInsideWrapper() throws Exception {
    MvcResult result = rewrite(YAML,
        "WITH active AS (SELECT phone FROM customer) SELECT phone FROM active");
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    String body = result.getResponse().getContentAsString();
    assertThat(body).contains("mask_phone(r.phone, 3, 4) AS phone");
    assertThat(body.toUpperCase()).contains("WITH ACTIVE AS");
  }

  @Test
  void unsupportedStatementReturnsStructuredBadRequest() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "metadataYaml", YAML, "sql", "DELETE FROM customer"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_STATEMENT"))
        .andExpect(jsonPath("$.message")
            .value(org.hamcrest.Matchers.containsString("statement 1")));
  }

  @Test
  void validationErrorReturnsBadRequest() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "metadataYaml", YAML, "sql", "SELECT nope FROM customer"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void invalidMetadataReturnsBadRequest() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "metadataYaml", "metadata: {}",
                "sql", "SELECT 1"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void missingFieldsReturnBadRequest() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("metadataYaml", YAML))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message").value(
            org.hamcrest.Matchers.containsString("sql is required")));

    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("sql", "SELECT 1"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message")
            .value(org.hamcrest.Matchers.containsString("metadataYaml is required")));
  }

  @Test
  void unsupportedDialectReturnsBadRequest() throws Exception {
    // mysql 是已注册方言（Task 5 起），改用真正未注册的 oracle 验证拒绝路径
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "metadataYaml", YAML, "sql", "SELECT 1", "dialect", "oracle"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("dialect")));
  }

  @Test
  void rewriteResponseCarriesRowFilteredFlag() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "metadataYaml", ROW_FILTER_YAML,
                "sql", "SELECT 1 AS constant; SELECT id, phone FROM customer;"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statements[0].rowFiltered").value(false))
        .andExpect(jsonPath("$.statements[0].unchanged").value(true))
        .andExpect(jsonPath("$.statements[1].rowFiltered").value(true))
        .andExpect(jsonPath("$.statements[1].masked").value(true))
        .andExpect(jsonPath("$.statements[1].unchanged").value(false))
        .andExpect(jsonPath("$.statements[1].rewrittenSql")
            .value(org.hamcrest.Matchers.containsString("WHERE status = 'active'")));
  }

  @Test
  void indexPageIsServed() throws Exception {
    mvc.perform(get("/index.html"))
        .andExpect(status().isOk())
        .andExpect(result -> assertThat(result.getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
            .contains("执行改写"));
  }

  private static final String POLICY_METADATA = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns: [{name: phone, type: varchar}]
      policies: {}
      """;

  private static final String POLICY_FILE = """
      policies:
        - name: mask-phone
          resources:
            - {catalog: crm, schema: public, table: customer, column: phone}
          dataMaskItems:
            - {users: ["alice"], udf: mask_phone}
      """;

  @Test
  void rewriteAcceptsPolicyYamlAndSubject() throws Exception {
    // alice → masked
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "metadataYaml", POLICY_METADATA, "policyYaml", POLICY_FILE,
                "sql", "SELECT phone FROM customer;", "user", "alice"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statements[0].masked").value(true));
    // 匿名 → 原样
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "metadataYaml", POLICY_METADATA, "policyYaml", POLICY_FILE,
                "sql", "SELECT phone FROM customer;"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statements[0].masked").value(false));
  }

  private static final String GROUP_POLICY_FILE = """
      policies:
        - name: mask-phone-devs
          resources:
            - {catalog: crm, schema: public, table: customer, column: phone}
          dataMaskItems:
            - {groups: ["devs"], udf: mask_phone}
      """;

  @Test
  void groupsSubjectPlumbsThroughToPolicyMatching() throws Exception {
    // devs 组成员命中 groups 项 → 掩码
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "metadataYaml", POLICY_METADATA, "policyYaml", GROUP_POLICY_FILE,
                "sql", "SELECT phone FROM customer;", "groups", List.of("devs")))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statements[0].masked").value(true));
    // 匿名（未带 groups）不命中 → 原样
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "metadataYaml", POLICY_METADATA, "policyYaml", GROUP_POLICY_FILE,
                "sql", "SELECT phone FROM customer;"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statements[0].masked").value(false));
  }

  @Test
  void policyYamlConflictWithMetadataPoliciesReturnsBadRequest() throws Exception {
    mvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "metadataYaml", YAML, "policyYaml", POLICY_FILE,
                "sql", "SELECT phone FROM customer;"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message")
            .value(org.hamcrest.Matchers.containsString("single source")));
  }
}
