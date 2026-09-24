package io.sqlmask.riskserver;

import com.sun.net.httpserver.HttpServer;
import io.sqlmask.riskserver.config.RiskProperties;
import io.sqlmask.riskserver.model.Alert;
import io.sqlmask.riskserver.model.RiskSeverity;
import io.sqlmask.riskserver.notify.NotificationSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationSinkTest {

  private HttpServer server;
  private final BlockingQueue<String> bodies = new LinkedBlockingQueue<>();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String startWebhook(int status) throws IOException {
    return startWebhook(status, 0);
  }

  private String startWebhook(int status, long delayedMillis) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/hook", exchange -> {
      if (delayedMillis > 0) {
        try {
          Thread.sleep(delayedMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      exchange.sendResponseHeaders(status, 2);
      exchange.getResponseBody().write("ok".getBytes(StandardCharsets.UTF_8));
      exchange.close();
    });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
  }

  private static Alert alert(RiskSeverity severity) {
    return new Alert("alt-1", "SQLI_UNION", "UNION 注入探测", "SQLI", severity,
        "guest_9", "203.0.113.9", "[HIGH] t", "d", "SELECT 1 UNION SELECT 2",
        System.currentTimeMillis(), "evt-1");
  }

  private static RiskProperties.Notify notifyConfig(String url) {
    RiskProperties properties = new RiskProperties();
    properties.getNotify().setWebhookUrl(url);
    return properties.getNotify();
  }

  /** Delivery is async now: wait for the sink's worker to record an entry. */
  private static void awaitNotification(NotificationSink sink, int expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      if (sink.recent(10).size() >= expected) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("notification not recorded within 5s; recent=" + sink.recent(10));
  }

  @Test
  void belowThresholdIsIgnored() throws Exception {
    try (NotificationSink sink = new NotificationSink(notifyConfig(""))) {
      assertThat(sink.qualifies(alert(RiskSeverity.MEDIUM))).isFalse();
      sink.notify(alert(RiskSeverity.MEDIUM));
      Thread.sleep(100);
      assertThat(sink.recent(10)).isEmpty();
    }
  }

  @Test
  void recordsLocallyWhenNoWebhook() throws Exception {
    try (NotificationSink sink = new NotificationSink(notifyConfig(""))) {
      sink.notify(alert(RiskSeverity.CRITICAL));
      awaitNotification(sink, 1);
      var entries = sink.recent(10);
      assertThat(entries).hasSize(1);
      assertThat(entries.get(0).delivery()).isEqualTo("RECORDED");
      assertThat(entries.get(0).alertId()).isEqualTo("alt-1");
      assertThat(sink.webhookConfigured()).isFalse();
    }
  }

  @Test
  void postsPayloadToWebhookAndMarksSent() throws Exception {
    String url = startWebhook(200);
    try (NotificationSink sink = new NotificationSink(notifyConfig(url))) {
      sink.notify(alert(RiskSeverity.CRITICAL));
      String body = bodies.poll(5, TimeUnit.SECONDS);
      assertThat(body).isNotNull();
      assertThat(body).contains("\"alertId\":\"alt-1\"");
      assertThat(body).contains("\"severity\":\"critical\"");
      assertThat(body).contains("\"user\":\"guest_9\"");
      awaitNotification(sink, 1);
      assertThat(sink.recent(10).get(0).delivery()).isEqualTo("SENT");
    }
  }

  @Test
  void failedWebhookIsCountedNotThrown() throws Exception {
    String url = startWebhook(500);
    try (NotificationSink sink = new NotificationSink(notifyConfig(url))) {
      sink.notify(alert(RiskSeverity.HIGH));
      assertThat(bodies.poll(5, TimeUnit.SECONDS)).isNotNull();
      awaitNotification(sink, 1);
      assertThat(sink.recent(10).get(0).delivery()).isEqualTo("FAILED");
    }
  }

  @Test
  void unreachableWebhookDoesNotThrow() throws Exception {
    RiskProperties.Notify config = notifyConfig("http://127.0.0.1:1/nowhere");
    try (NotificationSink sink = new NotificationSink(config)) {
      sink.notify(alert(RiskSeverity.HIGH));
      awaitNotification(sink, 1);
      assertThat(sink.recent(10).get(0).delivery()).isEqualTo("FAILED");
    }
  }

  /** The ingest path must never wait on a slow webhook (regression for the
   * synchronous send that used to block alert ingestion for up to 5s). */
  @Test
  void notifyReturnsImmediatelyWhileWebhookIsSlow() throws Exception {
    String url = startWebhook(200, 2_000);
    long startedAt = System.nanoTime();
    try (NotificationSink sink = new NotificationSink(notifyConfig(url))) {
      sink.notify(alert(RiskSeverity.CRITICAL));
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
      assertThat(elapsedMillis).isLessThan(1_000);
      // the slow webhook is still served, on the worker thread
      assertThat(bodies.poll(5, TimeUnit.SECONDS)).isNotNull();
    }
  }
}