package io.sqlmask.audit;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.elasticsearch.client.RestClient;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal in-JVM Elasticsearch stand-in: records every request (method, path,
 * body) and answers the handful of endpoints the audit writer/client uses.
 * Zero new test dependencies (JDK HttpServer).
 */
public final class FakeEsServer implements Closeable {

  public record RecordedRequest(String method, String path, String body) {
  }

  public final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
  public final AtomicReference<Integer> bulkStatus = new AtomicReference<>(200);
  public final AtomicReference<String> bulkBody =
      new AtomicReference<>("{\"errors\":false,\"items\":[]}");
  // Search responses must carry the fields a real ES 8 response always sends
  // (took/timedOut/_shards, hits.total.relation, _index/_id per hit):
  // elasticsearch-java validates them while decoding HitsMetadata.
  public final AtomicReference<String> searchBody = new AtomicReference<>("""
      {"took":0,"timed_out":false,"_shards":{"total":1,"successful":1,"failed":0,"skipped":0},
       "hits":{"total":{"value":0,"relation":"eq"},"hits":[]}}""");

  private final HttpServer server;

  private FakeEsServer(HttpServer server) {
    this.server = server;
  }

  public static FakeEsServer start() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
    FakeEsServer fake = new FakeEsServer(server);
    server.createContext("/", exchange -> {
      byte[] body;
      try (InputStream in = exchange.getRequestBody()) {
        body = in.readAllBytes();
      }
      String path = exchange.getRequestURI().getPath();
      fake.requests.add(new RecordedRequest(exchange.getRequestMethod(), path,
          new String(body, StandardCharsets.UTF_8)));
      int status = 200;
      String response = "{}";
      if (path.equals("/_bulk")) {
        status = fake.bulkStatus.get();
        response = fake.bulkBody.get();
      } else if (path.startsWith("/_index_template")) {
        response = "{\"acknowledged\":true}";
      } else if (path.endsWith("/_search")) {
        response = fake.searchBody.get();
      }
      byte[] out = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      // Required by elasticsearch-java's product check on every API response.
      exchange.getResponseHeaders().set("X-Elastic-Product", "Elasticsearch");
      exchange.sendResponseHeaders(status, out.length);
      exchange.getResponseBody().write(out);
      exchange.close();
    });
    server.start();
    return fake;
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public ElasticsearchClient client() {
    RestClient rest = RestClient.builder(
        org.apache.http.HttpHost.create("http://127.0.0.1:" + port())).build();
    return new ElasticsearchClient(new RestClientTransport(rest, new JacksonJsonpMapper(
        new ObjectMapper())));
  }

  public List<RecordedRequest> requests(String pathPrefix) {
    return requests.stream().filter(r -> r.path().startsWith(pathPrefix)).toList();
  }

  /** Requests whose path ends with the suffix — e.g. the daily-index search
   * requests this module sends, which the prefix filter cannot reach. */
  public List<RecordedRequest> requestsEndingWith(String pathSuffix) {
    return requests.stream().filter(r -> r.path().endsWith(pathSuffix)).toList();
  }

  public void setBulkFailure() {
    bulkStatus.set(503);
    bulkBody.set("boom");
  }

  public void resetBulk() {
    bulkStatus.set(200);
    bulkBody.set("{\"errors\":false,\"items\":[]}");
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
