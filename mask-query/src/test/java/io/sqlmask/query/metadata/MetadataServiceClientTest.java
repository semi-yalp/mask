package io.sqlmask.query.metadata;

import com.sun.net.httpserver.HttpServer;
import io.sqlmask.query.metadata.MetadataServiceClient.InstanceView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataServiceClientTest {

  static HttpServer stub;
  static MetadataServiceClient client;
  static volatile int status = 200;
  static volatile String body = "{}";

  @BeforeAll
  static void start() throws Exception {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/api/instances/pg_prod", exchange -> {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      try (var out = exchange.getResponseBody()) { out.write(bytes); }
    });
    stub.start();
    client = new MetadataServiceClient(
        "http://127.0.0.1:" + stub.getAddress().getPort(), "k");
  }

  @AfterAll
  static void stop() { stub.stop(0); }

  @Test
  void parsesDetailAndDerivesEngine() {
    status = 200;
    body = """
        {"name":"pg_prod","dialect":"postgresql","engine":null,"metadataVersion":3,
         "connection":{"host":"h","port":5432,"database":"crm","dbUser":"u",
           "passwordRef":"REF","sslmode":"disable","connectTimeoutSeconds":5}}
        """;
    InstanceView view = client.fetch("pg_prod");
    assertThat(view.engine()).isEqualTo("postgresql");
    assertThat(view.connection().port()).isEqualTo(5432);
  }

  @Test
  void maps404And401() {
    status = 404; body = "{}";
    assertThatThrownBy(() -> client.fetch("pg_prod"))
        .hasFieldOrPropertyWithValue("code", "INSTANCE_NOT_FOUND");
    status = 401; body = "{}";
    assertThatThrownBy(() -> client.fetch("pg_prod"))
        .hasFieldOrPropertyWithValue("code", "CONFIG_ERROR")
        .hasMessageContaining("API key");
  }
}
