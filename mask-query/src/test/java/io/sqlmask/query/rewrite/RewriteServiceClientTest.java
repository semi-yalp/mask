package io.sqlmask.query.rewrite;

import com.sun.net.httpserver.HttpServer;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.rewrite.RewriteServiceClient.RewrittenQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RewriteServiceClientTest {

  static HttpServer stub;
  static RewriteServiceClient client;
  static volatile int status = 200;
  static volatile String body = "{}";

  @BeforeAll
  static void start() throws Exception {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/api/rewrite/instances/x", exchange -> {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var out = exchange.getResponseBody()) { out.write(bytes); }
    });
    stub.start();
    client = new RewriteServiceClient(
        "http://127.0.0.1:" + stub.getAddress().getPort(), "k");
  }

  @AfterAll
  static void stop() { stub.stop(0); }

  @Test
  void parsesStatementsAndPassthroughErrors() {
    status = 200;
    body = """
        {"statements":[{"ordinal":1,"originalSql":"SELECT 1","rewrittenSql":"SELECT 1",
          "masked":false,"rowFiltered":false,"kind":"SELECT"}],"rewrittenSql":"SELECT 1;"}
        """;
    RewrittenQuery result = client.rewrite("x", "SELECT 1", null, List.of());
    assertThat(result.statements()).hasSize(1);
    assertThat(result.statements().get(0).kind()).isEqualTo("SELECT");

    status = 400;
    body = "{\"code\":\"POLICY_SERVICE_UNAVAILABLE\",\"message\":\"down\"}";
    assertThatThrownBy(() -> client.rewrite("x", "SELECT 1", null, List.of()))
        .isInstanceOf(QueryException.class)
        .hasFieldOrPropertyWithValue("code", "POLICY_SERVICE_UNAVAILABLE")
        .matches(e -> ((QueryException) e).rewritePhase());

    status = 500; body = "boom";
    assertThatThrownBy(() -> client.rewrite("x", "SELECT 1", null, List.of()))
        .hasFieldOrPropertyWithValue("code", "REWRITE_SERVICE_UNAVAILABLE");
  }
}
