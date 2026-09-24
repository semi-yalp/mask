package io.sqlmask.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RiskForwarderTest {

  private HttpServer server;
  private final BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
  private final BlockingQueue<String> apiKeys = new LinkedBlockingQueue<>();

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/risk/ingest", exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      bodies.add(new String(body, StandardCharsets.UTF_8));
      apiKeys.add(exchange.getRequestHeaders().getFirst("X-Api-Key"));
      byte[] resp = "{\"accepted\":1}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, resp.length);
      exchange.getResponseBody().write(resp);
      exchange.close();
    });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/risk/ingest";
  }

  private static AuditEvent sample(String sql) {
    return AuditEvent.rewrite("sql-mask", AuditEvent.SUCCESS, 5L, "10.0.0.9", "API_KEY",
        "analyst_q", List.of("bi"), "postgresql", 1, true, false, sql, sql, null, null);
  }

  @Test
  void forwardsBatchAsJsonArrayWithApiKeyAndAuditDocShape() throws Exception {
    String url = startServer();
    RiskForwardProperties properties = new RiskForwardProperties();
    properties.setUrl(url);
    properties.setApiKey("risk-key");
    properties.setBatchSize(2);
    // The worker holds the first event up to the flush interval waiting for
    // the batch to fill, so recording both events right away must produce one
    // batch of two — no timer can race a half batch out from under us.
    properties.setFlushIntervalMs(10_000);

    java.util.List<String> batches = new java.util.ArrayList<>();
    try (RiskForwarder forwarder = new RiskForwarder(properties, new SimpleMeterRegistry())) {
      forwarder.record(sample("SELECT phone FROM crm.public.customer"));
      forwarder.record(sample("SELECT id_card FROM crm.public.customer"));
      String first = bodies.poll(5, TimeUnit.SECONDS);
      assertThat(first).isNotNull();
      batches.add(first);
    }
    // close() 会把剩余事件刷出；容忍定时器把两条拆成两批，总数恒为 2
    String extra;
    while ((extra = bodies.poll(2, TimeUnit.SECONDS)) != null) {
      batches.add(extra);
    }

    int total = 0;
    JsonNode firstBatch = null;
    for (String batchBody : batches) {
      JsonNode batch = new ObjectMapper().readTree(batchBody);
      assertThat(batch.isArray()).isTrue();
      total += batch.size();
      if (firstBatch == null) {
        firstBatch = batch;
      }
    }
    assertThat(total).isEqualTo(2);
    JsonNode first = firstBatch.get(0);
    assertThat(first.get("@timestamp").isNumber()).isTrue();
    assertThat(first.get("eventType").asText()).isEqualTo("REWRITE");
    assertThat(first.get("actor").get("user").asText()).isEqualTo("analyst_q");
    assertThat(first.get("originalSql").asText()).contains("phone");
    assertThat(apiKeys.poll()).isEqualTo("risk-key");
  }

  @Test
  void sparseStreamIsFlushedAfterTheHoldInterval() throws Exception {
    String url = startServer();
    RiskForwardProperties properties = new RiskForwardProperties();
    properties.setUrl(url);
    properties.setBatchSize(10);
    properties.setFlushIntervalMs(300);

    String body;
    try (RiskForwarder forwarder = new RiskForwarder(properties, new SimpleMeterRegistry())) {
      forwarder.record(sample("SELECT phone FROM crm.public.customer"));
      // batch never fills: the worker ships the single event after ~300ms
      body = bodies.poll(5, TimeUnit.SECONDS);
      assertThat(body).isNotNull();
    }
    JsonNode batch = new ObjectMapper().readTree(body);
    assertThat(batch.isArray()).isTrue();
    assertThat(batch.size()).isEqualTo(1);
  }

  @Test
  void nullEventIsIgnoredAndCloseIsIdempotentEnough() {
    RiskForwardProperties properties = new RiskForwardProperties();
    properties.setUrl("http://127.0.0.1:1/nowhere");
    RiskForwarder forwarder = new RiskForwarder(properties, null);
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
      forwarder.record(null);
      forwarder.close();
    });
  }

  @Test
  void autoConfigurationWrapsRecorderOnlyWhenUrlConfigured() {
    ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(SimpleMeterRegistry.class)
        .withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class));

    runner.run(ctx -> {
      assertThat(ctx).hasSingleBean(AuditRecorder.class);
      assertThat(ctx).doesNotHaveBean(ForwardingAuditRecorder.class);
      assertThat(ctx).doesNotHaveBean(RiskForwarder.class);
    });

    // audit on + forwarding on: ES recorder stays, decorator is @Primary
    runner.withPropertyValues(
        "audit.enabled=true", "risk.forward.url=http://127.0.0.1:9099/x").run(ctx -> {
      assertThat(ctx).hasBean("esAuditRecorder");
      assertThat(ctx).hasBean("forwardingAuditRecorder");
      assertThat(ctx.getBean(AuditRecorder.class)).isInstanceOf(ForwardingAuditRecorder.class);
      assertThat(((ForwardingAuditRecorder) ctx.getBean(AuditRecorder.class)).delegate())
          .isInstanceOf(EsAuditRecorder.class);
    });

    runner.withPropertyValues("audit.enabled=false", "risk.forward.url=http://127.0.0.1:9099/x")
        .run(ctx -> {
          assertThat(ctx).hasSingleBean(AuditRecorder.class);
          assertThat(ctx.getBean(AuditRecorder.class)).isInstanceOf(ForwardingAuditRecorder.class);
          assertThat(((ForwardingAuditRecorder) ctx.getBean(AuditRecorder.class)).delegate())
              .isInstanceOf(NoopAuditRecorder.class);
        });
  }
}
