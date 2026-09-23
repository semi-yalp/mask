package io.sqlmask.riskserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.sqlmask.riskserver.block.BlockService;
import io.sqlmask.riskserver.config.RiskProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BlockService against an in-memory fake of the policy-server management API. */
class BlockServiceTest {

  private HttpServer server;
  private String baseUrl;
  private final ConcurrentMap<String, JsonNode> policies = new ConcurrentHashMap<>();
  private final AtomicReference<JsonNode> lastCreated = new AtomicReference<>();
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    byte[] body = exchange.getRequestBody().readAllBytes();
    if (method.equals("GET") && path.equals("/api/instances/crm")) {
      respond(exchange, 200, mapper.writeValueAsString(Map.of(
          "name", "crm", "dialect", "postgresql",
          "tables", List.of(
              Map.of("catalog", "crm", "schema", "public", "name", "customer"),
              Map.of("catalog", "crm", "schema", "public", "name", "orders")))));
    } else if (method.equals("GET") && path.equals("/api/instances/crm/policies")) {
      respond(exchange, 200, mapper.writeValueAsString(new ArrayList<>(policies.values())));
    } else if (method.equals("POST") && path.equals("/api/instances/crm/policies")) {
      JsonNode policy = mapper.readTree(body);
      policies.put(policy.get("name").asText(), policy);
      lastCreated.set(policy);
      respond(exchange, 200, new String(body, StandardCharsets.UTF_8));
    } else if (method.equals("DELETE") && path.startsWith("/api/instances/crm/policies/")) {
      String name = path.substring(path.lastIndexOf('/') + 1);
      respond(exchange, policies.remove(name) != null ? 200 : 404, "{}");
    } else if (method.equals("GET") && path.startsWith("/api/effective/crm")) {
      List<Map<String, Object>> filters = new ArrayList<>();
      policies.values().forEach(p -> filters.add(Map.of(
          "policy", p.get("name").asText(),
          "filterExpr", p.get("filterExpr").asText(),
          "subjects", p.get("subjects"))));
      respond(exchange, 200, mapper.writeValueAsString(Map.of("rowFilters", filters)));
    } else {
      respond(exchange, 404, "{\"code\":\"NOT_FOUND\"}");
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private BlockService service() {
    RiskProperties.Block config = new RiskProperties.Block();
    config.setBaseUrl(baseUrl);
    config.setApiKey("test-key");
    config.setDefaultInstance("crm");
    return new BlockService(config);
  }

  @Test
  void blockCreatesOneRowFilterPolicyPerTable() {
    BlockService blocks = service();
    BlockService.BlockResult result = blocks.block("guest_9", null, "alert alt-1");
    assertThat(result.policies()).containsExactlyInAnyOrder(
        "risk-block-guest_9-customer", "risk-block-guest_9-orders");
    JsonNode policy = lastCreated.get();
    assertThat(policy.get("policyType").asText()).isEqualTo("ROW_FILTER");
    assertThat(policy.get("filterExpr").asText()).isEqualTo("1 = 0");
    assertThat(policy.get("subjects").get("users").get(0).asText()).isEqualTo("guest_9");
    assertThat(policy.get("isEnabled").asBoolean()).isTrue();
    assertThat(result.verification()).contains("行过滤规则 2 条");
    assertThat(blocks.isBlocked("guest_9")).isTrue();
  }

  @Test
  void blockIsIdempotent() {
    BlockService blocks = service();
    blocks.block("guest_9", "crm", null);
    int before = policies.size();
    BlockService.BlockResult second = blocks.block("guest_9", "crm", null);
    assertThat(second.alreadyBlocked()).isTrue();
    assertThat(policies.size()).isEqualTo(before);
  }

  @Test
  void unblockRemovesOnlyOwnPolicies() {
    BlockService blocks = service();
    // a legitimate policy that must survive unblock
    policies.put("normal-pii-mask", mapper.valueToTree(Map.of(
        "name", "normal-pii-mask", "policyType", "DATAMASK")));
    blocks.block("guest_9", "crm", null);
    BlockService.BlockResult result = blocks.unblock("guest_9", "crm");
    assertThat(result.policies()).containsExactlyInAnyOrder(
        "risk-block-guest_9-customer", "risk-block-guest_9-orders");
    assertThat(policies.keySet()).containsExactly("normal-pii-mask");
    assertThat(blocks.isBlocked("guest_9")).isFalse();
  }

  @Test
  void unverifiedInstanceFails() {
    BlockService blocks = service();
    assertThatThrownBy(() -> blocks.block("u", "missing-instance", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing-instance");
  }

  @Test
  void unconfiguredServiceRejects() {
    BlockService blocks = new BlockService(new RiskProperties.Block());
    assertThatThrownBy(() -> blocks.block("u", null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("risk.block.base-url");
  }

  @Test
  void apiErrorSurfacesMessage() {
    BlockService blocks = service();
    assertThatThrownBy(() -> blocks.block("u", "gone", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("policy service");
  }
}
