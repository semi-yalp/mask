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
import java.util.concurrent.CountDownLatch;
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
  public final AtomicReference<Integer> templateStatus = new AtomicReference<>(200);
  /** When set, every /_bulk handler waits on it before answering (holds the writer). */
  public final AtomicReference<CountDownLatch> bulkGate = new AtomicReference<>();
  /** Per /_bulk artificial server-side delay in ms (simulates a slow ES). */
  public final AtomicReference<Integer> bulkDelayMs = new AtomicReference<>(0);
  public final AtomicReference<String> searchBody = new AtomicReference<>("""
      {"took":1,"timed_out":false,"_shards":{"total":1,"successful":1,"skipped":0,"failed":0},
       "hits":{"total":{"value":0,"relation":"eq"},"max_score":null,"hits":[]}}""");

  private final HttpServer server;
  private final List<RestClientTransport> transports = new CopyOnWriteArrayList<>();

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
        fake.awaitBulkGate();
        int delay = fake.bulkDelayMs.get();
        if (delay > 0) {
          fake.sleepQuietly(delay);
        }
        status = fake.bulkStatus.get();
        response = fake.bulkBody.get();
      } else if (path.startsWith("/_index_template")) {
        status = fake.templateStatus.get();
        response = "{\"acknowledged\":true}";
      } else if (path.endsWith("/_search")) {
        response = fake.searchBody.get();
      }
      byte[] out = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
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

  private void awaitBulkGate() {
    CountDownLatch gate = bulkGate.get();
    if (gate == null) {
      return;
    }
    try {
      gate.await();
    } catch (InterruptedException ignored) {
      // hold until released; server teardown unblocks the test anyway
    }
  }

  private void sleepQuietly(int delayMs) {
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException ignored) {
      // fall through and answer with the configured status/body
    }
  }

  public ElasticsearchClient client() {
    RestClient rest = RestClient.builder(
        org.apache.http.HttpHost.create("http://127.0.0.1:" + port())).build();
    RestClientTransport transport =
        new RestClientTransport(rest, new JacksonJsonpMapper(new ObjectMapper()));
    transports.add(transport);
    return new ElasticsearchClient(transport);
  }

  public List<RecordedRequest> requests(String pathPrefix) {
    return requests.stream().filter(r -> r.path().startsWith(pathPrefix)).toList();
  }

  public void setBulkFailure() {
    bulkStatus.set(503);
    bulkBody.set("boom");
  }

  public void resetBulk() {
    bulkStatus.set(200);
    bulkBody.set("{\"errors\":false,\"items\":[]}");
  }

  public void setTemplateFailure() {
    templateStatus.set(500);
  }

  public void resetTemplate() {
    templateStatus.set(200);
  }

  @Override
  public void close() {
    for (RestClientTransport transport : transports) {
      try {
        transport.close();
      } catch (IOException ignored) {
        // best-effort teardown
      }
    }
    server.stop(0);
  }
}
