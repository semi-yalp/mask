package io.sqlmask.riskserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.sqlmask.riskserver.block.BlockService;
import io.sqlmask.riskserver.config.RiskProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Block list survives process restarts via the local state file. */
class BlockServicePersistenceTest {

  @TempDir
  Path tmp;

  private HttpServer server;
  private String baseUrl;
  private final ObjectMapper mapper = new ObjectMapper();
  private final ConcurrentMap<String, com.fasterxml.jackson.databind.JsonNode> policies =
      new ConcurrentHashMap<>();

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
          "tables", List.of(Map.of("catalog", "crm", "schema", "public", "name", "customer")))));
    } else if (method.equals("GET") && path.equals("/api/instances/crm/policies")) {
      respond(exchange, 200, mapper.writeValueAsString(List.copyOf(policies.values())));
    } else if (method.equals("POST") && path.equals("/api/instances/crm/policies")) {
      var policy = mapper.readTree(body);
      policies.put(policy.get("name").asText(), policy);
      respond(exchange, 200, new String(body, StandardCharsets.UTF_8));
    } else if (method.equals("DELETE") && path.startsWith("/api/instances/crm/policies/")) {
      respond(exchange, policies.remove(path.substring(path.lastIndexOf('/') + 1)) != null ? 200 : 404, "{}");
    } else if (method.equals("GET") && path.startsWith("/api/effective/crm")) {
      respond(exchange, 200, mapper.writeValueAsString(Map.of("rowFilters", List.of())));
    } else {
      respond(exchange, 404, "{}");
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private RiskProperties.Block config(String stateFile) {
    RiskProperties.Block config = new RiskProperties.Block();
    config.setBaseUrl(baseUrl);
    config.setDefaultInstance("crm");
    config.setStatePath(stateFile);
    return config;
  }

  @Test
  void blockListSurvivesRestart() {
    Path state = tmp.resolve("blocks.json");
    BlockService first = new BlockService(config(state.toString()));
    first.block("guest_9", null, "alert alt-1");
    assertThat(first.isBlocked("guest_9")).isTrue();
    assertThat(state.toFile()).exists();

    // "restart": a brand-new service reading the same state file
    BlockService second = new BlockService(config(state.toString()));
    assertThat(second.isBlocked("guest_9")).isTrue();
    assertThat(second.snapshot()).hasSize(1);
    assertThat(second.snapshot().get(0).policyNames())
        .containsExactly("risk-block-guest_9-customer");
    assertThat(second.snapshot().get(0).note()).isEqualTo("alert alt-1");

    // unblock persists too
    second.unblock("guest_9", null);
    BlockService third = new BlockService(config(state.toString()));
    assertThat(third.isBlocked("guest_9")).isFalse();
  }

  @Test
  void blankStatePathKeepsLegacyInMemoryBehavior() {
    BlockService service = new BlockService(config(""));
    service.block("u", null, null);
    assertThat(service.isBlocked("u")).isTrue();
  }
}
