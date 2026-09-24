package io.sqlmask.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.rewrite.InheritedColumn;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import io.sqlmask.rewrite.StatementKind;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InheritedPolicyRegistrar} against a stub upstream (JDK built-in
 * {@link HttpServer}): the registrar must call, in order of concern, the
 * policy service table PUT, one policy POST per inherited column, and the
 * metadata structure PUT — mirroring the real wire contracts of
 * {@code PolicyAdminController} and {@code MetadataAdminController}.
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
      InheritedPolicyRegistrar registrar = new InheritedPolicyRegistrar(
          new InstanceRewriteConfig.Upstreams(
              new InstanceRewriteConfig.Upstreams.Service(
                  "http://localhost:" + server.getAddress().getPort(), "k"),
              new InstanceRewriteConfig.Upstreams.Service(
                  "http://localhost:" + server.getAddress().getPort(), "k")));
      registrar.register("demo", "alice",
          List.of(new StatementRewrite(1, "sql", "sql", false, false, StatementKind.CTAS,
              List.of(new InheritedColumn("crm", "public", "customer_copy", "phone", null, null,
                  List.of(new DataMaskItem(new SubjectSelector(Set.of(), Set.of("*")),
                      "mask_phone", List.of())))))));
      List<String> paths = calls.stream()
          .map(e -> e.getRequestMethod() + " " + e.getRequestURI().getPath())
          .toList();
      assertTrue(paths.contains("PUT /api/instances/demo/tables"));
      assertTrue(paths.contains("POST /api/instances/demo/policies"));
      assertTrue(paths.contains("PUT /api/instances/demo/structure"));
    } finally {
      server.stop(0);
    }
  }
}
