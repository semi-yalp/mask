package io.sqlmask.server;
import io.sqlmask.common.metrics.EffectiveMetrics;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.sqlmask.config.source.PolicyServiceConfigSource;
import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Effective-config pull metrics (spec §3.3) on the client path: the config
 * source records SUCCESS pulls against the resolved instance, 404s collapse
 * into the shared {@code (not_found)} sentinel series, and no series is ever
 * derived from an unresolved request-supplied instance name.
 */
class EffectiveConfigMetricsTest {

  private static final String BODY_V1 = """
      {"instance":"pg_prod","dialect":"postgresql","configVersion":1,
       "policySummary":{"enabled":1,"disabled":0},
       "config":{"metadata":{"tables":[{"catalog":"crm","schema":"public","name":"customer",
         "rowFilter":null,"columns":[{"name":"phone","type":"varchar"}]}]},
         "columns":[{"catalog":"crm","schema":"public","table":"customer","column":"phone",
           "policy":"phone_mask"}],
         "policies":{"phone_mask":{"udf":"mask_phone","arguments":[3,4]}}}}
      """;

  private HttpServer server;
  private final AtomicReference<Integer> status = new AtomicReference<>(200);
  private final AtomicInteger hits = new AtomicInteger();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/api/effective/", exchange -> {
      hits.incrementAndGet();
      byte[] bytes = BODY_V1.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(), bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private PolicyServiceConfigSource source(MeterRegistry registry, String instance) {
    return new PolicyServiceConfigSource(
        "http://127.0.0.1:" + server.getAddress().getPort(), "secret", instance,
        new EffectiveMetrics(registry));
  }

  @Test
  void pullUpdatesGauges() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PolicyServiceConfigSource source = source(registry, "pg_prod");

    source.load();

    assertThat(registry.get("sqlmask.effective.pull")
        .tag("instance", "pg_prod").tag("dialect", "postgresql").tag("outcome", "SUCCESS")
        .counter().count()).isEqualTo(1.0);
    assertThat(registry.get("sqlmask.effective.config_version")
        .tag("instance", "pg_prod").gauge().value()).isEqualTo(1.0);
    assertThat(registry.get("sqlmask.effective.policies")
        .tag("instance", "pg_prod").gauge().value()).isEqualTo(1.0);
    // 第二次 load 命中 subject LRU，不再产生新的 pull 计数
    source.load();
    assertThat(registry.get("sqlmask.effective.pull")
        .tag("instance", "pg_prod").tag("dialect", "postgresql").tag("outcome", "SUCCESS")
        .counter().count()).isEqualTo(1.0);
  }

  @Test
  void unknownInstanceCollapsesIntoNotFoundSentinel() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PolicyServiceConfigSource source = source(registry, "no_such_instance_metrics");
    status.set(404);

    assertThatThrownBy(source::load)
        .isInstanceOfSatisfying(SqlMaskException.class, e ->
            assertThat(e.getCode()).isEqualTo(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND));

    double after = registry.get("sqlmask.effective.pull")
        .tag("instance", "(not_found)").tag("dialect", "(not_found)").tag("outcome", "FAILURE")
        .counter().count();
    assertThat(after).isEqualTo(1.0);
    assertThat(registry.getMeters()).noneMatch(m ->
        m.getId().getName().equals("sqlmask.effective.pull")
            && "no_such_instance_metrics".equals(m.getId().getTag("instance")));
  }
}
