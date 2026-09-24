package io.sqlmask.query.submit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.error.QueryException;
import io.sqlmask.query.metadata.MetadataServiceClient.ConnectionView;
import io.sqlmask.query.service.CancelRegistry;
import io.sqlmask.query.service.QueryModels.QueryResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The generic HTTP submitter against a conventional SQL-over-HTTP endpoint. */
class HttpQuerySubmitterTest {

  static HttpServer stub;
  static volatile String responseBody = "{}";
  static volatile int status = 200;
  static volatile String seenQuery;

  @BeforeAll
  static void start() throws Exception {
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/sql", exchange -> {
      seenQuery = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      respond(exchange, status, responseBody);
    });
    stub.start();
  }

  @AfterAll
  static void stop() {
    stub.stop(0);
  }

  private static void respond(HttpExchange exchange, int code, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(code, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static final ConnectionView CONN =
      new ConnectionView("h", 1, "db", "u", "REF", "disable", 10);

  private HttpQuerySubmitter submitter() {
    return new HttpQuerySubmitter(new QueryProperties(30, 1000, 10000, 500, 10,
        "http://127.0.0.1:" + stub.getAddress().getPort() + "/sql"));
  }

  private SubmitRequest request(String sql) {
    return new SubmitRequest("inst", "postgresql", CONN, sql, 100,
        new QueryProperties(30, 1000, 10000, 500, 10, "http://x"),
        new CancelRegistry().begin(), true, false, false,
        System.nanoTime(), null);
  }

  @Test
  void postsQueryAndAssemblesResult() {
    responseBody = """
        {"columns":[{"name":"phone","type":"varchar"}],
         "rows":[["138****0001"],["138****0002"],["138****0003"]],
         "truncated":true,"extraIgnored":1}
        """;
    status = 200;
    QueryResult result = submitter().submit(request("SELECT phone FROM t"));
    assertThat(seenQuery).contains("SELECT phone FROM t");
    assertThat(result.columns()).hasSize(1);
    assertThat(result.columns().get(0).name()).isEqualTo("phone");
    assertThat(result.rowCount()).isEqualTo(3);
    assertThat(result.truncated()).isTrue();
    assertThat(result.masked()).isTrue();
  }

  @Test
  void httpErrorBecomesQueryError() {
    status = 500;
    assertThatThrownBy(() -> submitter().submit(request("SELECT 1")))
        .isInstanceOf(QueryException.class)
        .hasFieldOrPropertyWithValue("code", QueryException.QUERY_ERROR);
  }

  @Test
  void unconfiguredEndpointIsConfigError() {
    HttpQuerySubmitter unconfigured = new HttpQuerySubmitter(
        new QueryProperties(30, 1000, 10000, 500, 10, " "));
    assertThatThrownBy(() -> unconfigured.submit(request("SELECT 1")))
        .isInstanceOf(QueryException.class)
        .hasFieldOrPropertyWithValue("code", QueryException.CONFIG_ERROR);
  }
}
