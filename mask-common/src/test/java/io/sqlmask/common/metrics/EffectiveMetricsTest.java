package io.sqlmask.server;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.sqlmask.config.source.EffectiveConfigResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final EffectiveMetrics metrics = new EffectiveMetrics(registry);

  private static EffectiveConfigResponse response(long version, int enabled) {
    return new EffectiveConfigResponse("pg_prod", "postgresql", version,
        new EffectiveConfigResponse.PolicySummary(enabled, 0), null);
  }

  @Test
  void successUpdatesPullCounterCompileTimerAndGauges() {
    long start = System.nanoTime();
    metrics.success("pg_prod", response(7, 3), start);
    metrics.success("pg_prod", response(9, 5), start);

    assertThat(registry.get("sqlmask.effective.pull")
        .tag("instance", "pg_prod").tag("dialect", "postgresql").tag("outcome", "SUCCESS")
        .counter().count()).isEqualTo(2.0);
    assertThat(registry.get("sqlmask.effective.config_version")
        .tag("instance", "pg_prod").gauge().value()).isEqualTo(9.0);
    assertThat(registry.get("sqlmask.effective.policies")
        .tag("instance", "pg_prod").gauge().value()).isEqualTo(5.0);
    assertThat(registry.get("sqlmask.effective.compile")
        .tag("instance", "pg_prod").timer().count()).isEqualTo(2L);
  }

  @Test
  void notFoundGoesToSharedSentinelSeries() {
    metrics.notFound(System.nanoTime());
    metrics.notFound(System.nanoTime());

    assertThat(registry.get("sqlmask.effective.pull")
        .tag("instance", "(not_found)").tag("dialect", "(not_found)").tag("outcome", "FAILURE")
        .counter().count()).isEqualTo(2.0);
    // 未知实例不得产生按请求输入命名的 series
    assertThat(registry.getMeters()).noneMatch(m ->
        m.getId().getName().equals("sqlmask.effective.pull")
            && m.getId().getTag("instance").equals("garbage"));
  }

  @Test
  void otherFailuresKeepResolvedInstanceNameWithUnknownDialect() {
    metrics.failure("pg_prod", System.nanoTime());
    metrics.failure(null, System.nanoTime());

    assertThat(registry.get("sqlmask.effective.pull")
        .tag("instance", "pg_prod").tag("dialect", "(unknown)").tag("outcome", "FAILURE")
        .counter().count()).isEqualTo(1.0);
    assertThat(registry.get("sqlmask.effective.pull")
        .tag("instance", "(not_found)").tag("dialect", "(unknown)").tag("outcome", "FAILURE")
        .counter().count()).isEqualTo(1.0);
  }
}
