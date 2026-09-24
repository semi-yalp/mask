package io.sqlmask.policyserver.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API-key gates themselves, exercised through the real filter
 * registration: servlet path mapping is URL-pattern matching, so an exact
 * path like {@code /api/instances} must be listed explicitly alongside the
 * wildcard (a bare {@code /api/instances/*} leaves the exact path ungated).
 * Keys are injected via Spring properties, which the filters resolve through
 * the Environment (system env vars still apply in real deployments).
 */
@SpringBootTest(properties = {
    "SQLMASK_ADMIN_API_KEY=admin-secret",
    "SQLMASK_DATA_API_KEY=data-secret"})
@AutoConfigureMockMvc
class PolicyApiKeyGateTest {

  private static final String INSTANCE_BODY = """
      {"name": "pg_gate", "dialect": "postgresql",
       "tables": [{"catalog": "crm", "schema": "public", "name": "customer",
                   "columns": [{"name": "phone", "type": "varchar"}]}]}
      """;

  @Autowired
  MockMvc mockMvc;

  @Test
  void exactInstanceCreatePathIsGatedWithoutKey() throws Exception {
    mockMvc.perform(post("/api/instances").servletPath("/api/instances")
            .contentType(MediaType.APPLICATION_JSON).content(INSTANCE_BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void exactInstanceCreatePathPassesWithAdminKey() throws Exception {
    mockMvc.perform(post("/api/instances").servletPath("/api/instances")
            .header("X-Api-Key", "admin-secret")
            .contentType(MediaType.APPLICATION_JSON).content(INSTANCE_BODY))
        .andExpect(status().isOk());
  }

  @Test
  void exactInstanceListPathIsGatedWithoutKey() throws Exception {
    mockMvc.perform(get("/api/instances").servletPath("/api/instances"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void wildcardInstancePathStaysGated() throws Exception {
    mockMvc.perform(get("/api/instances/pg_gate").servletPath("/api/instances/pg_gate"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/instances/pg_gate").servletPath("/api/instances/pg_gate")
            .header("X-Api-Key", "admin-secret"))
        .andExpect(status().isNotFound()); // gate passed; instance was never created
  }

  @Test
  void effectiveExactPathRequiresDataKey() throws Exception {
    mockMvc.perform(get("/api/effective/whatever").servletPath("/api/effective/whatever"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/effective/whatever").servletPath("/api/effective/whatever")
            .header("X-Api-Key", "data-secret"))
        .andExpect(status().is4xxClientError());
    mockMvc.perform(get("/api/effective/whatever").servletPath("/api/effective/whatever")
            .header("X-Api-Key", "admin-secret"))
        .andExpect(status().isUnauthorized()); // wrong surface key
  }
}