package io.sqlmask.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.sqlmask.config.source.InstanceQueryAssembler;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadataclient.MetadataClient;
import io.sqlmask.rewrite.RewriteEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/rewrite/instances/{name}} against stubbed metadata and
 * policy services (JDK built-in {@link HttpServer}, no new dependencies).
 * Stub payloads follow the real wire contracts: {@code MetadataSnapshot} for
 * the metadata pull and {@code EffectiveConfigResponse} for
 * {@code GET {base}/api/effective/{instance}?user=...&groups=...}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InstanceRewriteEndpointTest {

  static final String METADATA_JSON = """
      {"instance":"pg_prod","dialect":"postgresql","metadataVersion":7,
       "tables":[{"catalog":"crm","schema":"public","name":"customer",
         "columns":[{"name":"id","type":"bigint"},{"name":"phone","type":"varchar"}]}]}
      """;

  static final String EFFECTIVE_JSON = """
      {"instance":"pg_prod","dialect":"postgresql","configVersion":3,
       "policySummary":{"enabled":1,"disabled":0},
       "config":{
         "metadata":{"tables":[{"catalog":"crm","schema":"public","name":"customer",
           "rowFilter":null,
           "columns":[{"name":"id","type":"bigint"},{"name":"phone","type":"varchar"}]}]},
         "columns":[{"catalog":"crm","schema":"public","table":"customer",
           "column":"phone","policy":"m"}],
         "policies":{"m":{"udf":"mask_phone","arguments":[3,4]}}}}
      """;

  static HttpServer stub;
  static volatile String effectiveJson = EFFECTIVE_JSON;
  static volatile String seenEffectiveQuery;

  // The stub must exist before the context is created: @DynamicPropertySource
  // below reads its port during context bootstrap.
  static {
    try {
      stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      stub.createContext("/meta/api/metadata/instances/pg_prod",
          exchange -> respond(exchange, 200, METADATA_JSON));
      stub.createContext("/meta/api/metadata/instances/missing",
          exchange -> respond(exchange, 404, "{}"));
      stub.createContext("/policy/api/effective/pg_prod", exchange -> {
        seenEffectiveQuery = exchange.getRequestURI().getQuery();
        respond(exchange, 200, effectiveJson);
      });
      stub.start();
    } catch (IOException e) {
      throw new IllegalStateException("failed to start the upstream stub", e);
    }
  }

  @AfterAll
  static void stopStub() {
    stub.stop(0);
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  static String stubBase() {
    return "http://127.0.0.1:" + stub.getAddress().getPort();
  }

  @DynamicPropertySource
  static void upstreams(DynamicPropertyRegistry registry) {
    registry.add("sqlmask.metadata-service.base-url", () -> stubBase() + "/meta");
    registry.add("sqlmask.metadata-service.api-key", () -> "meta-key");
    registry.add("sqlmask.policy-service.base-url", () -> stubBase() + "/policy");
    registry.add("sqlmask.policy-service.api-key", () -> "policy-key");
  }

  @Autowired MockMvc mockMvc;

  @Test
  void rewritesAgainstInstance() throws Exception {
    effectiveJson = EFFECTIVE_JSON;
    mockMvc.perform(post("/api/rewrite/instances/pg_prod")
            .contentType("application/json")
            .content("{\"sql\":\"SELECT phone FROM customer\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statements[0].kind").value("SELECT"))
        .andExpect(jsonPath("$.statements[0].masked").value(true))
        .andExpect(jsonPath("$.rewrittenSql").value(containsString("mask_phone")));
  }

  @Test
  void subjectIsForwardedToThePolicyService() throws Exception {
    effectiveJson = EFFECTIVE_JSON;
    seenEffectiveQuery = null;
    mockMvc.perform(post("/api/rewrite/instances/pg_prod")
            .contentType("application/json")
            .content("{\"sql\":\"SELECT phone FROM customer\","
                + "\"user\":\"alice\",\"groups\":[\"devs\"]}"))
        .andExpect(status().isOk());
    assertThat(seenEffectiveQuery).contains("user=alice").contains("groups=devs");
  }

  @Test
  void missingInstancePropagatesNotFoundCode() throws Exception {
    mockMvc.perform(post("/api/rewrite/instances/missing")
            .contentType("application/json").content("{\"sql\":\"SELECT 1\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("METADATA_INSTANCE_NOT_FOUND"));
  }

  @Test
  void blankSqlReturnsConfigError() throws Exception {
    mockMvc.perform(post("/api/rewrite/instances/pg_prod")
            .contentType("application/json").content("{\"sql\":\"   \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"));
  }

  /**
   * Unconfigured service mode (blank base-urls) must fail the request with
   * {@code CONFIG_ERROR} at the call path — never fall back to unmasked
   * output, and never break context startup (the default context that
   * {@link RewriteControllerTest} boots already proves the startup half of
   * that contract). Asserted against the bare controller: the
   * {@code @DynamicPropertySource} of this class would leak into any
   * {@code @Nested} Spring context, so no second context can express
   * "unconfigured" inside this file.
   */
  @Test
  void unconfiguredServiceModeFailsWithConfigError() {
    var controller = new InstanceRewriteController(new RewriteEngine(),
        InstanceRewriteEndpointTest.<MetadataClient>absent(),
        InstanceRewriteEndpointTest.<InstanceRewriteConfig.PolicySourceProvider>absent(),
        new InstanceQueryAssembler());
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> controller.rewrite("pg_prod",
        new InstanceRewriteController.InstanceRewriteRequest("SELECT 1", null, null)));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertThat(e.getMessage())
        .contains("sqlmask.metadata-service.base-url")
        .contains("sqlmask.policy-service.base-url");
  }

  /** ObjectProvider standing in for an unconfigured (absent) bean. */
  private static <T> ObjectProvider<T> absent() {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        throw new java.util.NoSuchElementException("bean is not configured");
      }

      @Override
      public T getObject(Object... args) {
        throw new java.util.NoSuchElementException("bean is not configured");
      }

      @Override
      public T getIfAvailable() {
        return null;
      }

      @Override
      public T getIfUnique() {
        return null;
      }
    };
  }

  // ---- optional API-key gate (SQLMASK_REWRITE_API_KEY) ----

  @Test
  void apiKeyGateIsOpenWhenUnconfigured() throws Exception {
    assertEquals(200, run(new InstanceRewriteApiKeyFilter(null), null).getStatus());
    assertEquals(200, run(new InstanceRewriteApiKeyFilter("  "), null).getStatus());
  }

  @Test
  void apiKeyGateEnforcesConfiguredKey() throws Exception {
    InstanceRewriteApiKeyFilter filter = new InstanceRewriteApiKeyFilter("rewrite-secret");
    assertEquals(200, run(filter, "rewrite-secret").getStatus());
    assertEquals(401, run(filter, "wrong").getStatus());
    assertEquals(401, run(filter, null).getStatus());
  }

  @Test
  void apiKeyGateErrorShapeMatchesPolicyGate() throws Exception {
    MockHttpServletResponse response =
        run(new InstanceRewriteApiKeyFilter("rewrite-secret"), null);
    assertEquals(401, response.getStatus());
    assertEquals("application/json", response.getContentType());
    assertEquals("{\"code\":\"UNAUTHORIZED\",\"message\":\"missing or invalid API key\","
        + "\"details\":[]}", response.getContentAsString());
  }

  private static MockHttpServletRequest request(String key) {
    MockHttpServletRequest request = new MockHttpServletRequest(
        "POST", "/api/rewrite/instances/pg_prod");
    request.setServletPath("/api/rewrite/instances/pg_prod");
    if (key != null) {
      request.addHeader("X-Api-Key", key);
    }
    return request;
  }

  private static MockHttpServletResponse run(InstanceRewriteApiKeyFilter filter, String key)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request(key), response, new MockFilterChain());
    return response;
  }
}
