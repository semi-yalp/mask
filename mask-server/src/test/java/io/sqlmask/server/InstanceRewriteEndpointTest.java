package io.sqlmask.server;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.metaserver.service.StructureService;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import io.sqlmask.server.rewrite.RewriteContextRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/rewrite/instances/{name}} through the in-process
 * {@link RewriteContextRepository}: the instance and its policy are seeded
 * through the real metadata/policy domain services (H2-backed), the request
 * is subject-parameterized, and the kernel output carries per-statement
 * {@code kind}/{@code masked} for read-only data planes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InstanceRewriteEndpointTest {

  @Autowired MockMvc mockMvc;
  @Autowired MetadataService metadata;
  @Autowired StructureService structures;
  @Autowired PolicyService policies;

  @BeforeEach
  void seed() {
    try {
      metadata.delete("pg_prod");
    } catch (SqlMaskException ignored) {
      // not seeded yet
    }
    metadata.create("pg_prod", "postgresql", null, null);
    structures.replace("pg_prod", List.of(new TableStructure(
        "crm", "public", "customer", List.of(
            new TableStructure.ColumnStructure("id", "bigint"),
            new TableStructure.ColumnStructure("phone", "varchar")))));

    // an instance with policies refuses direct deletion: clear the policies first
    try {
      for (PolicyEntity policy : policies.policies("pg_prod")) {
        policies.deletePolicy("pg_prod", policy.name());
      }
    } catch (SqlMaskException ignored) {
      // not seeded yet
    }
    try {
      policies.deleteInstance("pg_prod");
    } catch (SqlMaskException ignored) {
      // not seeded yet
    }
    policies.createInstance("pg_prod", "postgresql", List.of(new TableDef("crm", "public",
        "customer", List.of(new ColumnDef("id", "bigint"),
            new ColumnDef("phone", "varchar")))));
    policies.createUdf("pg_prod", new UdfDefinition("mask_phone",
        List.of(new UdfDefinition.UdfSignature(List.of("varchar", "integer", "integer"), "varchar"),
            new UdfDefinition.UdfSignature(List.of("bigint", "integer", "integer"), "bigint"))));
    policies.createPolicy("pg_prod", new PolicyEntity("m", PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        null, "mask_phone", List.of(3, 4), null));
  }

  @Test
  void rewritesAgainstInstance() throws Exception {
    mockMvc.perform(post("/api/rewrite/instances/pg_prod")
            .contentType("application/json")
            .content("{\"sql\":\"SELECT phone FROM customer\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statements[0].kind").value("SELECT"))
        .andExpect(jsonPath("$.statements[0].masked").value(true))
        .andExpect(jsonPath("$.rewrittenSql").value(containsString("mask_phone")));
  }

  @Test
  void subjectIsHonoredByThePolicyCompile() throws Exception {
    // a subject-scoped policy for another user must not leak into alice's compile
    policies.createPolicy("pg_prod", new PolicyEntity("scoped", PolicyType.DATAMASK, true, 10,
        new ResourceSelector("crm", "public", "customer", List.of("id")),
        new SubjectSelector(java.util.Set.of("bob"), java.util.Set.of()),
        "mask_phone", List.of(1, 1), null));
    try {
      mockMvc.perform(post("/api/rewrite/instances/pg_prod")
              .contentType("application/json")
              .content("{\"sql\":\"SELECT id, phone FROM customer\",\"user\":\"alice\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.statements[0].masked").value(true))
          .andExpect(jsonPath("$.rewrittenSql").value(containsString("mask_phone")));
    } finally {
      policies.deletePolicy("pg_prod", "scoped");
    }
  }

  @Test
  void missingInstancePropagatesNotFoundCode() throws Exception {
    mockMvc.perform(post("/api/rewrite/instances/missing")
            .contentType("application/json").content("{\"sql\":\"SELECT 1\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("METADATA_INSTANCE_NOT_FOUND"));
  }

  @Test
  void blankSqlReturnsConfigError() throws Exception {
    mockMvc.perform(post("/api/rewrite/instances/pg_prod")
            .contentType("application/json").content("{\"sql\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  // ---- optional API-key gate primitives (unit-level; retired from the
  //      monolith request path but still shipped for standalone deployments) ----

  @Test
  void apiKeyGateIsOpenWhenUnconfigured() throws Exception {
    assertEquals(200, run(io.sqlmask.common.web.ApiKeyFilter.failOpen(null), null).getStatus());
    assertEquals(200, run(io.sqlmask.common.web.ApiKeyFilter.failOpen("  "), null).getStatus());
  }

  @Test
  void apiKeyGateEnforcesConfiguredKey() throws Exception {
    var filter = io.sqlmask.common.web.ApiKeyFilter.failOpen("rewrite-secret");
    assertEquals(200, run(filter, "rewrite-secret").getStatus());
    assertEquals(401, run(filter, "wrong").getStatus());
    assertEquals(401, run(filter, null).getStatus());
  }

  @Test
  void apiKeyGateErrorShapeMatchesPolicyGate() throws Exception {
    org.springframework.mock.web.MockHttpServletResponse response =
        run(io.sqlmask.common.web.ApiKeyFilter.failOpen("rewrite-secret"), null);
    assertEquals(401, response.getStatus());
    assertEquals("application/json", response.getContentType());
    assertEquals("{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid API key\","
        + "\"details\":[]}", response.getContentAsString());
  }

  private static org.springframework.mock.web.MockHttpServletRequest request(String key) {
    org.springframework.mock.web.MockHttpServletRequest request =
        new org.springframework.mock.web.MockHttpServletRequest(
            "POST", "/api/rewrite/instances/pg_prod");
    request.setServletPath("/api/rewrite/instances/pg_prod");
    if (key != null) {
      request.addHeader("X-Api-Key", key);
    }
    return request;
  }

  private static org.springframework.mock.web.MockHttpServletResponse run(
      io.sqlmask.common.web.ApiKeyFilter filter, String key) throws Exception {
    org.springframework.mock.web.MockHttpServletResponse response =
        new org.springframework.mock.web.MockHttpServletResponse();
    filter.doFilter(request(key), response, new org.springframework.mock.web.MockFilterChain());
    return response;
  }
}
