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
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/hook", exchange -> {
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

  @Test
  void belowThresholdIsIgnored() {
    NotificationSink sink = new NotificationSink(notifyConfig(""));
    assertThat(sink.qualifies(alert(RiskSeverity.MEDIUM))).isFalse();
    sink.notify(alert(RiskSeverity.MEDIUM));
    assertThat(sink.recent(10)).isEmpty();
  }

  @Test
  void recordsLocallyWhenNoWebhook() {
    NotificationSink sink = new NotificationSink(notifyConfig(""));
    sink.notify(alert(RiskSeverity.CRITICAL));
    var entries = sink.recent(10);
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).delivery()).isEqualTo("RECORDED");
    assertThat(entries.get(0).alertId()).isEqualTo("alt-1");
    assertThat(sink.webhookConfigured()).isFalse();
  }

  @Test
  void postsPayloadToWebhookAndMarksSent() throws Exception {
    String url = startWebhook(200);
    NotificationSink sink = new NotificationSink(notifyConfig(url));
    sink.notify(alert(RiskSeverity.CRITICAL));
    String body = bodies.poll(5, TimeUnit.SECONDS);
    assertThat(body).isNotNull();
    assertThat(body).contains("\"alertId\":\"alt-1\"");
    assertThat(body).contains("\"severity\":\"critical\"");
    assertThat(body).contains("\"user\":\"guest_9\"");
    assertThat(sink.recent(10).get(0).delivery()).isEqualTo("SENT");
  }

  @Test
  void failedWebhookIsCountedNotThrown() throws Exception {
    String url = startWebhook(500);
    NotificationSink sink = new NotificationSink(notifyConfig(url));
    sink.notify(alert(RiskSeverity.HIGH));
    assertThat(bodies.poll(5, TimeUnit.SECONDS)).isNotNull();
    assertThat(sink.recent(10).get(0).delivery()).isEqualTo("FAILED");
  }

  @Test
  void unreachableWebhookDoesNotThrow() {
    RiskProperties.Notify config = notifyConfig("http://127.0.0.1:1/nowhere");
    NotificationSink sink = new NotificationSink(config);
    sink.notify(alert(RiskSeverity.HIGH));
    assertThat(sink.recent(10).get(0).delivery()).isEqualTo("FAILED");
  }
}
