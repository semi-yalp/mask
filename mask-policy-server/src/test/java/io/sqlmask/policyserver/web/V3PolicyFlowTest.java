package io.sqlmask.policyserver.web;

import io.sqlmask.policyserver.connection.EngineAccess;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.ConnectionConfig;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end contract for the v3 policy service: pre-check connection, create
 * an instance (connect-first, fetch metadata), register a UDF, create/update/
 * rollback a policy with version history, compile the effective config, and
 * suggest metadata. Uses the in-memory store and a mocked engine access.
 */
@SpringBootTest
@AutoConfigureMockMvc
class V3PolicyFlowTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private EngineAccess access;

  private static final ConnectionConfig CFG = new ConnectionConfig("postgresql", "localhost",
      5432, "shop", "svc", "SHOP_PW", List.of(), false, "disable", 15);

  @BeforeEach
  void stubEngine() {
    when(access.test(any())).thenReturn(
        new io.sqlmask.policyserver.connection.ConnectionTestResult(true, "postgresql", 3,
            List.of()));
    when(access.fetch(any())).thenReturn(List.of(
        new TableDef("crm", "public", "customer",
            List.of(new ColumnDef("phone", "varchar")))));
  }

  @Test
  void connectionPreCheckSucceeds() throws Exception {
    mvc.perform(post("/api/connections/test").contentType("application/json")
            .content("{\"connection\":{\"dialect\":\"postgresql\",\"host\":\"localhost\","
                + "\"port\":5432,\"database\":\"shop\",\"dbUser\":\"svc\","
                + "\"passwordRef\":\"SHOP_PW\",\"sslmode\":\"disable\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));
  }

  @Test
  void fullPolicyLifecycleWithVersionsRollbackAndEffective() throws Exception {
    mvc.perform(post("/api/instances").contentType("application/json")
            .content("{\"name\":\"pg\",\"connection\":{\"dialect\":\"postgresql\","
                + "\"host\":\"localhost\",\"port\":5432,\"database\":\"shop\","
                + "\"dbUser\":\"svc\",\"passwordRef\":\"SHOP_PW\",\"sslmode\":\"disable\"},"
                + "\"fetchMetadata\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectionStatus").value("CONNECTED"))
        .andExpect(jsonPath("$.tables[0].name").value("customer"));

    mvc.perform(post("/api/instances/pg/udfs").contentType("application/json")
            .content("{\"name\":\"mask_phone\",\"signatures\":[{\"params\":[\"varchar\"],"
                + "\"returns\":\"varchar\"}]}"))
        .andExpect(status().isOk());

    mvc.perform(post("/api/instances/pg/policies").contentType("application/json")
            .content("{\"name\":\"p1\",\"policyType\":\"datamask\",\"isEnabled\":true,"
                + "\"resource\":{\"catalog\":\"crm\",\"schema\":\"public\",\"table\":\"customer\","
                + "\"columns\":[\"phone\"]},\"subjects\":{\"users\":[\"*\"]},"
                + "\"udf\":\"mask_phone\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentVersion").value(1))
        .andExpect(jsonPath("$.accessType").value("SELECT"));

    mvc.perform(put("/api/instances/pg/policies/p1").contentType("application/json")
            .content("{\"name\":\"p1\",\"policyType\":\"datamask\",\"isEnabled\":true,"
                + "\"resource\":{\"catalog\":\"crm\",\"schema\":\"public\",\"table\":\"customer\","
                + "\"columns\":[\"phone\"]},\"subjects\":{\"users\":[\"*\"]},"
                + "\"udf\":\"mask_phone\",\"currentVersion\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentVersion").value(2));

    mvc.perform(get("/api/instances/pg/policies/p1/versions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].version").value(1))
        .andExpect(jsonPath("$[1].version").value(2));

    mvc.perform(post("/api/instances/pg/policies/p1/rollback").contentType("application/json")
            .content("{\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currentVersion").value(3));

    mvc.perform(get("/api/effective/pg").param("user", "alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configVersion").isNumber())
        .andExpect(jsonPath("$.config.columns[0].table").value("customer"))
        .andExpect(jsonPath("$.config.columns[0].column").value("phone"))
        .andExpect(jsonPath("$.config.columns[0].policy").value("p1"));

    mvc.perform(get("/api/instances/pg/suggest").param("kind", "table").param("q", "cust"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value("customer"));
  }

  @Test
  void effectiveHasNoGlobAndNoPasswordLeak() throws Exception {
    mvc.perform(post("/api/instances").contentType("application/json")
            .content("{\"name\":\"glob\",\"connection\":{\"dialect\":\"postgresql\","
                + "\"host\":\"h\",\"port\":5432,\"database\":\"d\",\"dbUser\":\"u\","
                + "\"passwordRef\":\"PWREF\"},\"fetchMetadata\":true}"))
        .andExpect(status().isOk());

    // Glob column policy (path only; binding never contains '?' — '?' not used here).
    mvc.perform(post("/api/instances/glob/udfs").contentType("application/json")
            .content("{\"name\":\"mask_phone\",\"signatures\":[{\"params\":[\"varchar\"],"
                + "\"returns\":\"varchar\"}]}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/instances/glob/policies").contentType("application/json")
            .content("{\"name\":\"g1\",\"policyType\":\"datamask\",\"isEnabled\":true,"
                + "\"resource\":{\"catalog\":\"crm\",\"schema\":\"public\",\"table\":\"customer\","
                + "\"columns\":[\"phon*\"]},\"subjects\":{\"users\":[\"*\"]},"
                + "\"udf\":\"mask_phone\"}"))
        .andExpect(status().isOk());

    mvc.perform(get("/api/effective/glob"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("*"))))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("passwordRef"))));
  }

  @Test
  void unknownInstanceIs404WithCode() throws Exception {
    mvc.perform(get("/api/effective/nope"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POLICY_INSTANCE_NOT_FOUND"));
  }

  @Test
  void createInstanceConnectionFailurePersistsNothing() throws Exception {
    when(access.test(any())).thenThrow(
        new io.sqlmask.error.SqlMaskException(io.sqlmask.error.SqlMaskException.Code
            .CONNECTION_FAILED, "refused"));
    mvc.perform(post("/api/instances").contentType("application/json")
            .content("{\"name\":\"bad\",\"connection\":{\"dialect\":\"postgresql\","
                + "\"host\":\"down\",\"port\":1,\"database\":\"d\",\"dbUser\":\"u\","
                + "\"passwordRef\":\"PW\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONNECTION_FAILED"));
    mvc.perform(get("/api/instances/bad"))
        .andExpect(status().isNotFound());
  }
}