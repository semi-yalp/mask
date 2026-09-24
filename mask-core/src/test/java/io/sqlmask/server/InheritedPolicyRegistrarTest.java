package io.sqlmask.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.rewrite.InheritedColumn;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import io.sqlmask.rewrite.StatementKind;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InheritedPolicyRegistrar} against a stub upstream (JDK built-in
 * {@link HttpServer}): the registrar must call, in order of concern, the
 * policy service table PUT, one policy POST per inherited column, and the
 * metadata structure PUT — mirroring the real wire contracts of
 * {@code PolicyAdminController} and {@code MetadataAdminController}. The
 * structure PUT is a whole-list replace on the server side, so the registrar
 * must first snapshot the instance via the read-only data plane
 * ({@code GET /api/metadata/instances/{name}}) and PUT the merged table list.
 */
class InheritedPolicyRegistrarTest {

  @Test
  void registersTableThenColumnsThenStructure() throws Exception {
    List<HttpExchange> calls = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/", ex -> {
      calls.add(ex);
      byte[] body = switch (ex.getRequestMethod() + " " + ex.getRequestURI().getPath()) {
        case "GET /api/instances/demo" -> """
            {"name":"demo","dialect":"postgresql","tables":[]}
            """.getBytes();
        case "GET /api/metadata/instances/demo" -> ("{\"instance\":\"demo\","
            + "\"dialect\":\"postgresql\",\"metadataVersion\":1,\"tables\":[]}").getBytes();
        case "PUT /api/instances/demo/tables" -> "{\"name\":\"demo\"}".getBytes();
        case "POST /api/instances/demo/policies" ->
            "{\"name\":\"auto.inherit.customer_copy.phone\"}".getBytes();
        case "PUT /api/instances/demo/structure" -> "{\"name\":\"demo\"}".getBytes();
        default -> "{}".getBytes();
      };
      ex.sendResponseHeaders(200, body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });
    server.start();
    try {
      registrar(server).register("demo", "alice", List.of(ctasWithInheritedPhone()));
      List<String> paths = calls.stream()
          .map(e -> e.getRequestMethod() + " " + e.getRequestURI().getPath())
          .toList();
      assertTrue(paths.contains("GET /api/metadata/instances/demo"));
      assertTrue(paths.contains("PUT /api/instances/demo/tables"));
      assertTrue(paths.contains("POST /api/instances/demo/policies"));
      assertTrue(paths.contains("PUT /api/instances/demo/structure"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void mergesStructureWithExistingTablesInsteadOfReplacing() throws Exception {
    List<String> structureBodies = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/", ex -> {
      String call = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
      byte[] body = switch (call) {
        case "GET /api/instances/demo" -> """
            {"name":"demo","dialect":"postgresql","tables":[]}
            """.getBytes();
        case "GET /api/metadata/instances/demo" -> """
            {"instance":"demo","dialect":"postgresql","metadataVersion":3,
             "tables":[{"catalog":"crm","schema":"public","name":"existing",
                        "columns":[{"name":"id","type":"bigint"}]}]}
            """.getBytes();
        case "PUT /api/instances/demo/tables" -> "{\"name\":\"demo\"}".getBytes();
        case "POST /api/instances/demo/policies" ->
            "{\"name\":\"auto.inherit.customer_copy.phone\"}".getBytes();
        case "PUT /api/instances/demo/structure" -> "{\"name\":\"demo\"}".getBytes();
        default -> "{}".getBytes();
      };
      if (call.equals("PUT /api/instances/demo/structure")) {
        structureBodies.add(new String(ex.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8));
      }
      ex.sendResponseHeaders(200, body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });
    server.start();
    try {
      registrar(server).register("demo", "alice", List.of(ctasWithInheritedPhone()));
      JsonNode tables = new ObjectMapper().readTree(structureBodies.get(0));
      // 合并而非替换:既有表 crm.public.existing 保留(列原样),目标表 customer_copy 追加
      JsonNode existing = tableByName(tables, "existing");
      JsonNode copy = tableByName(tables, "customer_copy");
      assertEquals("bigint", columnType(existing, "id"));
      assertEquals("varchar", columnType(copy, "phone"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void structureSnapshotFailureFailsWholeRewrite() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/", ex -> {
      String call = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
      boolean notFound = call.equals("GET /api/metadata/instances/demo");
      byte[] body = (notFound ? "{\"error\":\"instance not found\"}"
          : switch (call) {
            case "GET /api/instances/demo" -> """
                {"name":"demo","dialect":"postgresql","tables":[]}
                """;
            case "PUT /api/instances/demo/tables" -> "{\"name\":\"demo\"}";
            case "POST /api/instances/demo/policies" ->
                "{\"name\":\"auto.inherit.customer_copy.phone\"}";
            default -> "{}";
          }).getBytes();
      ex.sendResponseHeaders(notFound ? 404 : 200, body.length);
      ex.getResponseBody().write(body);
      ex.close();
    });
    server.start();
    try {
      SqlMaskException failure = assertThrows(SqlMaskException.class,
          () -> registrar(server).register("demo", "alice", List.of(ctasWithInheritedPhone())));
      assertEquals(SqlMaskException.Code.CONFIG_ERROR, failure.getCode());
      assertTrue(failure.getMessage().contains("GET /api/metadata/instances/demo"));
      assertTrue(failure.getMessage().contains("instance not found"));
    } finally {
      server.stop(0);
    }
  }

  /** policy 与 metadata 指向同一 stub 的注册器。 */
  private static InheritedPolicyRegistrar registrar(HttpServer server) {
    String base = "http://localhost:" + server.getAddress().getPort();
    return new InheritedPolicyRegistrar(new InstanceRewriteConfig.Upstreams(
        new InstanceRewriteConfig.Upstreams.Service(base, "k"),
        new InstanceRewriteConfig.Upstreams.Service(base, "k")));
  }

  /** CTAS 目标表 crm.public.customer_copy,继承列 phone(类型 varchar,引擎填写)。 */
  private static StatementRewrite ctasWithInheritedPhone() {
    return new StatementRewrite(1, "sql", "sql", false, false, StatementKind.CTAS,
        List.of(new InheritedColumn("crm", "public", "customer_copy", "phone", "varchar", null,
            List.of(new DataMaskItem(new SubjectSelector(Set.of(), Set.of("*")),
                "mask_phone", List.of())))));
  }

  private static JsonNode tableByName(JsonNode tables, String name) {
    for (JsonNode table : tables) {
      if (name.equals(table.path("name").asText())) {
        return table;
      }
    }
    throw new AssertionError("structure PUT 缺少表 '" + name + "': " + tables);
  }

  private static String columnType(JsonNode table, String column) {
    for (JsonNode entry : table.path("columns")) {
      if (column.equals(entry.path("name").asText())) {
        return entry.path("type").asText();
      }
    }
    throw new AssertionError(
        "表 '" + table.path("name").asText() + "' 缺少列 '" + column + "': " + table);
  }
}
