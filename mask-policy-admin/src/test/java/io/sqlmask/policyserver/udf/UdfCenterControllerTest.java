package io.sqlmask.policyserver.udf;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.web.PolicyApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Request-shape validation of the UDF-center endpoints: everything that can
 * be rejected before an engine connection is opened (passwordRef prefix,
 * required connection fields, template/ddl presence) is a 400. The happy
 * paths need a live engine plus a {@code SQLMASK_*} environment variable and
 * are covered by {@link UdfCenterServiceTest} behind the fake introspector.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UdfCenterControllerTest {

  @Autowired
  private MockMvc mvc;

  @Test
  void templatesAreListedStatically() throws Exception {
    mvc.perform(get("/api/instances/whatever/udfs/templates"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.templates.length()").value(5))
        .andExpect(jsonPath("$.templates[0]").value("mask_email"))
        .andExpect(jsonPath("$.templates[4]").value("mask_text"));
  }

  @Test
  void importRejectsPasswordRefWithoutSqlmaskPrefix() throws Exception {
    mvc.perform(post("/api/instances/pg_prod/udfs/import")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(null, "\"engine\":\"postgresql\",\"host\":\"127.0.0.1\","
                + "\"port\":5432,\"database\":\"app\",\"user\":\"u\",\"passwordRef\":\"SECRET\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("METADATA_CREDENTIAL_UNAVAILABLE"));
  }

  @Test
  void importRejectsMissingConnectionFields() throws Exception {
    mvc.perform(post("/api/instances/pg_prod/udfs/import")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(null, "\"engine\":\"postgresql\",\"port\":5432,"
                + "\"database\":\"app\",\"user\":\"u\",\"passwordRef\":\"SQLMASK_X\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));

    mvc.perform(post("/api/instances/pg_prod/udfs/import")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(null, "\"engine\":\"postgresql\",\"host\":\"127.0.0.1\","
                + "\"user\":\"u\",\"passwordRef\":\"SQLMASK_X\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));

    mvc.perform(post("/api/instances/pg_prod/udfs/import")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(null, "\"engine\":\"oracle\",\"host\":\"127.0.0.1\","
                + "\"database\":\"app\",\"user\":\"u\",\"passwordRef\":\"SQLMASK_X\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void deployRequiresTemplateOrDdl() throws Exception {
    mvc.perform(post("/api/instances/pg_prod/udfs/deploy")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(null, "\"engine\":\"postgresql\",\"host\":\"127.0.0.1\","
                + "\"database\":\"app\",\"user\":\"u\",\"passwordRef\":\"SQLMASK_X\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  @Test
  void deployRejectsUnknownTemplateBeforeConnecting() throws Exception {
    mvc.perform(post("/api/instances/pg_prod/udfs/deploy")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("\"template\":\"mask_hash\"", "\"engine\":\"postgresql\","
                + "\"host\":\"127.0.0.1\",\"database\":\"app\",\"user\":\"u\","
                + "\"passwordRef\":\"SQLMASK_X\"")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  /**
   * Engine-side failures map to 502: the database is an upstream of this
   * service. Checked directly on the handler — driving it through MockMvc
   * would require a reachable engine plus a live {@code SQLMASK_*} variable.
   */
  @Test
  void introspectErrorsMapToBadGateway() {
    PolicyApiExceptionHandler handler = new PolicyApiExceptionHandler();
    assertEquals(502, handler.handle(new SqlMaskException(
        SqlMaskException.Code.INTROSPECT_ERROR, "engine connection failed"))
        .getStatusCode().value());
    assertEquals(400, handler.handle(new SqlMaskException(
        SqlMaskException.Code.CONFIG_ERROR, "bad request")).getStatusCode().value());
    assertEquals(404, handler.handle(new SqlMaskException(
        SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND, "nope"))
        .getStatusCode().value());
  }

  /** Joins optional leading fields with the connection fields into one JSON object. */
  private static String body(String leading, String connectionFields) {
    String prefix = leading == null ? "" : leading + ",";
    return "{" + prefix + connectionFields + "}";
  }
}
