package io.sqlmask.metaserver.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final CollectMetrics metrics = new CollectMetrics(registry);

  @Test
  void successRecordsCounterTimerAndWarnings() {
    int result = metrics.record("postgresql", () -> 2);

    assertThat(result).isEqualTo(2);
    assertThat(registry.get("sqlmask.metadata.collect")
        .tag("engine", "postgresql").tag("outcome", "SUCCESS").counter().count()).isEqualTo(1.0);
    assertThat(registry.get("sqlmask.metadata.collect.duration")
        .tag("engine", "postgresql").timer().count()).isEqualTo(1L);

    metrics.warnings("postgresql", 2);
    metrics.warnings("postgresql", 0);
    assertThat(registry.get("sqlmask.metadata.warnings")
        .tag("engine", "postgresql").counter().count()).isEqualTo(2.0);
  }

  @Test
  void failureCountsAndRethrowsUntouched() {
    RuntimeException boom = new IllegalStateException("db down");

    assertThatThrownBy(() -> metrics.record("mysql", () -> {
          throw boom;
        }))
        .isSameAs(boom);

    assertThat(registry.get("sqlmask.metadata.collect")
        .tag("engine", "mysql").tag("outcome", "FAILURE").counter().count()).isEqualTo(1.0);
    assertThat(registry.get("sqlmask.metadata.collect.duration")
        .tag("engine", "mysql").timer().count()).isEqualTo(1L);
  }
}
