package io.sqlmask.metadataclient;

import com.sun.net.httpserver.HttpServer;
import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataClientTest {

  private HttpServer server;
  private MetadataClient client;
  private String lastApiKey;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/api/metadata/instances/pg_prod/version", exchange -> {
      lastApiKey = exchange.getRequestHeaders().getFirst("X-Api-Key");
      if (!"secret".equals(lastApiKey)) {
        exchange.sendResponseHeaders(401, -1);
        return;
      }
      byte[] body = "{\"instance\":\"pg_prod\",\"metadataVersion\":7}".getBytes(
          StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    server.createContext("/api/metadata/instances/pg_prod", exchange -> {
      if (!"secret".equals(exchange.getRequestHeaders().getFirst("X-Api-Key"))) {
        exchange.sendResponseHeaders(401, -1);
        return;
      }
      byte[] body = """
          {"instance":"pg_prod","dialect":"postgresql","metadataVersion":7,
           "tables":[{"catalog":"crm","schema":"public","name":"customer",
                      "columns":[{"name":"id","type":"bigint"}]}]}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    server.createContext("/api/metadata/instances/ghost/version", exchange ->
        exchange.sendResponseHeaders(404, -1));
    server.createContext("/api/metadata/instances/ghost", exchange ->
        exchange.sendResponseHeaders(404, -1));
    server.start();
    client = new MetadataClient("http://127.0.0.1:" + server.getAddress().getPort(), "secret");
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void versionOfReturnsVersion() {
    assertEquals(OptionalLong.of(7), client.versionOf("pg_prod"));
  }

  @Test
  void versionOfUnknownInstanceIsEmpty() {
    assertEquals(OptionalLong.empty(), client.versionOf("ghost"));
  }

  @Test
  void fetchReturnsSnapshot() {
    MetadataClient.MetadataSnapshot snapshot = client.fetch("pg_prod");
    assertEquals("postgresql", snapshot.dialect());
    assertEquals(7, snapshot.metadataVersion());
    assertEquals("customer", snapshot.tables().get(0).name());
    assertEquals("bigint", snapshot.tables().get(0).columns().get(0).type());
  }

  @Test
  void fetchUnknownInstanceThrowsNotFound() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> client.fetch("ghost"));
    assertEquals(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND, e.getCode());
  }

  @Test
  void wrongApiKeyFailsClosed() {
    MetadataClient bad = new MetadataClient(
        "http://127.0.0.1:" + server.getAddress().getPort(), "wrong");
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> bad.fetch("pg_prod"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  @Test
  void unreachableServiceFailsClosed() {
    MetadataClient dead = new MetadataClient("http://127.0.0.1:1", "secret");
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> dead.fetch("pg_prod"));
    assertEquals(SqlMaskException.Code.METADATA_SERVICE_UNAVAILABLE, e.getCode());
    assertTrue(e.getMessage().contains("metadata service"));
  }
}
