package io.sqlmask.policyserver.web;

import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.TableDef;
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

/** UDF registry CRUD over REST, backed by the in-memory policy store bean. */
@SpringBootTest
@AutoConfigureMockMvc
class UdfEndpointTest {

  private static final String BODY = """
      {
        "name": "mask_phone",
        "signatures": [
          {"params": ["varchar", "integer", "integer"], "returns": "varchar"},
          {"params": ["bigint", "integer", "integer"], "returns": "varchar"}
        ]
      }
      """;

  private static final String DUP_BODY = """
      {"name": "dup_check", "signatures": [{"params": ["varchar"], "returns": "varchar"}]}
      """;

  @Autowired
  private MockMvc mvc;

  @Autowired
  private PolicyService service;

  @BeforeEach
  void setUpInstance() {
    try {
      service.createInstance("pg_prod", "postgresql", List.of(
          new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    } catch (io.sqlmask.error.SqlMaskException alreadyExists) {
      // 上下文复用时实例已存在
    }
    for (String udf : new String[] {"mask_phone", "dup_check"}) {
      try {
        service.deleteUdf("pg_prod", udf);
      } catch (io.sqlmask.error.SqlMaskException absent) {
        // 清理上一个用例的遗留
      }
    }
  }

  @Test
  void udfCrudRoundTrip() throws Exception {
    mvc.perform(post("/api/instances/pg_prod/udfs")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("mask_phone"))
        .andExpect(jsonPath("$.signatures.length()").value(2));

    mvc.perform(get("/api/instances/pg_prod/udfs/mask_phone"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signatures[0].params[0]").value("varchar"));

    mvc.perform(get("/api/instances/pg_prod/udfs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));

    mvc.perform(put("/api/instances/pg_prod/udfs/mask_phone")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":"
                + "[{\"params\":[\"varchar\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signatures.length()").value(1));

    mvc.perform(delete("/api/instances/pg_prod/udfs/mask_phone"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/instances/pg_prod/udfs/mask_phone"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void unknownInstanceAndDuplicateFollowErrorContract() throws Exception {
    mvc.perform(post("/api/instances/nope/udfs")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POLICY_INSTANCE_NOT_FOUND"));

    mvc.perform(post("/api/instances/pg_prod/udfs")
            .contentType(MediaType.APPLICATION_JSON).content(DUP_BODY))
        .andExpect(status().isOk());
    mvc.perform(post("/api/instances/pg_prod/udfs")
            .contentType(MediaType.APPLICATION_JSON).content(DUP_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));

    mvc.perform(post("/api/instances/pg_prod/udfs")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"bad\",\"signatures\":"
                + "[{\"params\":[\"strng\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void malformedUdfBodiesFollowErrorContract() throws Exception {
    // signature 缺 returns：锁住既有 requireReturnType 守卫
    mvc.perform(post("/api/instances/pg_prod/udfs")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":"
                + "[{\"params\":[\"varchar\",\"integer\",\"integer\"]}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));

    // PUT body 缺 name：不得让 PolicyService 触发 NPE 变 500
    mvc.perform(put("/api/instances/pg_prod/udfs/mask_phone")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"signatures\":[{\"params\":[\"varchar\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));

    // signatures 数组里的 null 元素
    mvc.perform(post("/api/instances/pg_prod/udfs")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":[null]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));

    // params 里的 null 元素（List.copyOf 拒绝 null）
    mvc.perform(post("/api/instances/pg_prod/udfs")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"mask_phone\",\"signatures\":"
                + "[{\"params\":[\"varchar\",null],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void replaceUnknownUdfFollowsErrorContract() throws Exception {
    mvc.perform(put("/api/instances/pg_prod/udfs/never_created")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"never_created\",\"signatures\":"
                + "[{\"params\":[\"varchar\"],\"returns\":\"varchar\"}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }
}
