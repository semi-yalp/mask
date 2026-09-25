package io.sqlmask.policyserver.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Instance and policy admin CRUD over REST, through the PolicyService facade. */
@SpringBootTest
@AutoConfigureMockMvc
class PolicyAdminEndpointTest {

  private static final String INSTANCE_BODY = """
      {"name": "pg_prod", "dialect": "postgresql",
       "tables": [{"catalog": "crm", "schema": "public", "name": "customer",
                   "columns": [{"name": "phone", "type": "varchar"}]}]}
      """;

  private static final String POLICY_BODY = """
      {"name": "phone_mask_analysts", "policyType": "datamask", "isEnabled": true,
       "resource": {"catalog": "crm", "schema": "public", "table": "customer",
                    "columns": ["phone"]},
       "subjects": {"users": ["alice"], "groups": []},
       "udf": "mask_phone", "arguments": [3, 4]}
      """;

  private static final String POLICY_BODY_WITHOUT_RESOURCE = """
      {"name": "phone_mask_analysts", "policyType": "datamask", "isEnabled": true,
       "subjects": {"users": ["alice"], "groups": []},
       "udf": "mask_phone", "arguments": [3, 4]}
      """;

  private static final String INSTANCE_BODY_TABLE_WITHOUT_CATALOG = """
      {"name": "pg_prod", "dialect": "postgresql",
       "tables": [{"schema": "public", "name": "customer",
                   "columns": [{"name": "phone", "type": "varchar"}]}]}
      """;

  private static final String POLICY_BODY_RESOURCE_WITHOUT_TABLE = """
      {"name": "phone_mask_analysts", "policyType": "datamask", "isEnabled": true,
       "resource": {"catalog": "crm", "schema": "public",
                    "columns": ["phone"]},
       "subjects": {"users": ["alice"], "groups": []},
       "udf": "mask_phone", "arguments": [3, 4]}
      """;

  private static final String EMPTY_SUBJECTS_POLICY_BODY = """
      {"name": "phone_mask_everyone", "policyType": "datamask", "isEnabled": true,
       "resource": {"catalog": "crm", "schema": "public", "table": "customer",
                    "columns": ["phone"]},
       "subjects": {"users": [], "groups": []},
       "udf": "mask_phone", "arguments": [3, 4]}
      """;

  @Autowired
  private MockMvc mvc;

  @Autowired
  private io.sqlmask.policyserver.PolicyService service;

  @BeforeEach
  void cleanUp() {
    try {
      service.deletePolicy("pg_prod", "phone_mask_analysts");
    } catch (io.sqlmask.error.SqlMaskException absent) {
      // 无遗留
    }
    try {
      service.deletePolicy("pg_prod", "phone_mask_everyone");
    } catch (io.sqlmask.error.SqlMaskException absent) {
      // 无遗留
    }
    try {
      service.deleteUdf("pg_prod", "mask_phone");
    } catch (io.sqlmask.error.SqlMaskException absent) {
      // 无遗留
    }
    try {
      service.deleteInstance("pg_prod");
    } catch (io.sqlmask.error.SqlMaskException absent) {
      // 无遗留（有策略时先删策略再删实例）
    }
  }

  @Test
  void instanceLifecycle() throws Exception {
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content(INSTANCE_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("pg_prod"))
        .andExpect(jsonPath("$.tables[0].columns[0].name").value("phone"));

    mvc.perform(get("/api/instances")).andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(get("/api/instances/pg_prod")).andExpect(status().isOk())
        .andExpect(jsonPath("$.dialect").value("postgresql"));

    mvc.perform(put("/api/instances/pg_prod/tables").contentType(MediaType.APPLICATION_JSON)
            .content("{\"tables\": [" + instanceTableJson() + "]}"))
        .andExpect(status().isOk());

    mvc.perform(post("/api/instances/pg_prod/udfs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":"
                + "[{\"params\":[\"varchar\",\"integer\",\"integer\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/policies").contentType(MediaType.APPLICATION_JSON)
            .content(POLICY_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subjects.users[0]").value("alice"));

    // 有策略时删实例被拒
    mvc.perform(delete("/api/instances/pg_prod"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));

    mvc.perform(get("/api/instances/pg_prod/policies")).andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(put("/api/instances/pg_prod/policies/phone_mask_analysts")
            .contentType(MediaType.APPLICATION_JSON)
            .content(POLICY_BODY.replace("\"isEnabled\": true", "\"isEnabled\": false")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isEnabled").value(false));
    mvc.perform(delete("/api/instances/pg_prod/policies/phone_mask_analysts"))
        .andExpect(status().isOk());
    mvc.perform(delete("/api/instances/pg_prod")).andExpect(status().isOk());
    mvc.perform(get("/api/instances/pg_prod"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POLICY_INSTANCE_NOT_FOUND"));
  }

  @Test
  void errorContractFollowsFacade() throws Exception {
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\": \"x\", \"dialect\": \"oracle\", \"tables\": []}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
    mvc.perform(get("/api/instances/nope/policies"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POLICY_INSTANCE_NOT_FOUND"));
    mvc.perform(post("/api/instances/nope/policies").contentType(MediaType.APPLICATION_JSON)
            .content(POLICY_BODY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POLICY_INSTANCE_NOT_FOUND"));
  }

  @Test
  void policyWithoutResourceFollowsErrorContract() throws Exception {
    // 缺 resource 的策略体不得触发 NPE→500；控制器守卫应先以 400 CONFIG_ERROR 拒绝
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content(INSTANCE_BODY))
        .andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/udfs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":"
                + "[{\"params\":[\"varchar\",\"integer\",\"integer\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isOk());

    mvc.perform(post("/api/instances/pg_prod/policies").contentType(MediaType.APPLICATION_JSON)
            .content(POLICY_BODY_WITHOUT_RESOURCE))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));

    // PUT 侧同样受守卫保护
    mvc.perform(put("/api/instances/pg_prod/policies/phone_mask_analysts")
            .contentType(MediaType.APPLICATION_JSON)
            .content(POLICY_BODY_WITHOUT_RESOURCE))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void tableWithoutCatalogFollowsErrorContract() throws Exception {
    // 表缺 catalog 不得漏成 ColumnKey.normalize 的 IllegalArgumentException→500；
    // 控制器守卫应以 400 CONFIG_ERROR 拒绝
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content(INSTANCE_BODY_TABLE_WITHOUT_CATALOG))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void policyResourceWithoutTableFollowsErrorContract() throws Exception {
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content(INSTANCE_BODY))
        .andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/udfs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":"
                + "[{\"params\":[\"varchar\",\"integer\",\"integer\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isOk());

    // resource 缺 table：守卫应先于 findTable 的 normalize 以 400 CONFIG_ERROR 拒绝
    mvc.perform(post("/api/instances/pg_prod/policies").contentType(MediaType.APPLICATION_JSON)
            .content(POLICY_BODY_RESOURCE_WITHOUT_TABLE))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void policyPriorityRoundTrips() throws Exception {
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content(INSTANCE_BODY)).andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/udfs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":"
                + "[{\"params\":[\"varchar\",\"integer\",\"integer\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isOk());

    String withPriority = """
        {"name": "phone_mask_analysts", "policyType": "datamask", "isEnabled": true,
         "priority": 7,
         "resource": {"catalog": "crm", "schema": "public", "table": "customer",
                      "columns": ["phone"]},
         "subjects": {"users": ["alice"], "groups": []},
         "udf": "mask_phone", "arguments": [3, 4]}
        """;
    mvc.perform(post("/api/instances/pg_prod/policies").contentType(MediaType.APPLICATION_JSON)
            .content(withPriority))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priority").value(7));
    mvc.perform(get("/api/instances/pg_prod/policies/phone_mask_analysts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priority").value(7));
  }

  @Test
  void resourceInheritColumnsRoundTrips() throws Exception {
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content(INSTANCE_BODY)).andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/udfs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":"
                + "[{\"params\":[\"varchar\",\"integer\",\"integer\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isOk());

    String withInheritColumns = """
        {"name": "phone_mask_analysts", "policyType": "datamask", "isEnabled": true,
         "resource": {"catalog": "crm", "schema": "public", "table": "customer",
                      "columns": ["phone"], "inheritColumns": ["phone"]},
         "subjects": {"users": ["alice"], "groups": []},
         "udf": "mask_phone", "arguments": [3, 4]}
        """;
    mvc.perform(post("/api/instances/pg_prod/policies").contentType(MediaType.APPLICATION_JSON)
            .content(withInheritColumns))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resource.inheritColumns[0]").value("phone"));
    mvc.perform(get("/api/instances/pg_prod/policies/phone_mask_analysts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resource.inheritColumns[0]").value("phone"));
  }

  @Test
  void omittedInheritColumnsDefaultsToEmptyOnRead() throws Exception {
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content(INSTANCE_BODY)).andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/udfs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":"
                + "[{\"params\":[\"varchar\",\"integer\",\"integer\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isOk());

    // 旧客户端不携带 inheritColumns:DTO 缺省 null,读出时应归一化为空列表
    mvc.perform(post("/api/instances/pg_prod/policies").contentType(MediaType.APPLICATION_JSON)
            .content(POLICY_BODY))
        .andExpect(status().isOk());
    mvc.perform(get("/api/instances/pg_prod/policies/phone_mask_analysts"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.resource.inheritColumns.length()").value(0));
  }

  @Test
  void omittedPriorityDefaultsToZero() throws Exception {
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content(INSTANCE_BODY)).andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/udfs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":"
                + "[{\"params\":[\"varchar\",\"integer\",\"integer\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/policies").contentType(MediaType.APPLICATION_JSON)
            .content(POLICY_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priority").value(0));
  }

  @Test
  void emptySubjectsMeanEveryone() throws Exception {
    // the console submits empty users/groups arrays for "applies to everyone"
    // (its hint reads 空=任意); both-empty sets must normalize to the wildcard
    // instead of failing SubjectSelector's non-empty guard with a 400.
    mvc.perform(post("/api/instances").contentType(MediaType.APPLICATION_JSON)
            .content(INSTANCE_BODY)).andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/udfs").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":"
                + "[{\"params\":[\"varchar\",\"integer\",\"integer\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/policies").contentType(MediaType.APPLICATION_JSON)
            .content(EMPTY_SUBJECTS_POLICY_BODY))
        .andExpect(status().isOk());

    // wildcard reach: a subject matching nothing specific still hits the policy
    mvc.perform(get("/api/effective/pg_prod").param("user", "mallory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.config.columns.length()").value(1))
        .andExpect(jsonPath("$.config.columns[0].policy").value("phone_mask_everyone"));
  }

  private static String instanceTableJson() {
    return "{\"catalog\": \"crm\", \"schema\": \"public\", \"name\": \"customer\","
        + " \"columns\": [{\"name\": \"phone\", \"type\": \"varchar\"}]}";
  }
}
