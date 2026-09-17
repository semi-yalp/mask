package io.sqlmask.policyserver.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final AdminMetrics metrics = new AdminMetrics(registry);

  @Test
  void successRecordsCounterAndTimer() {
    String result = metrics.record("INSTANCE", "CREATE", () -> "ok");

    assertThat(result).isEqualTo("ok");
    assertThat(registry.get("sqlmask.admin.requests")
        .tag("resource_type", "INSTANCE").tag("action", "CREATE").tag("outcome", "SUCCESS")
        .counter().count()).isEqualTo(1.0);
    assertThat(registry.get("sqlmask.admin.duration")
        .tag("resource_type", "INSTANCE").tag("action", "CREATE").timer().count()).isEqualTo(1L);
  }

  @Test
  void failureCountsAndRethrowsUntouched() {
    RuntimeException boom = new IllegalStateException("boom");

    assertThatThrownBy(() -> metrics.record("POLICY", "DELETE", () -> {
          throw boom;
        }))
        .isSameAs(boom);

    assertThat(registry.get("sqlmask.admin.requests")
        .tag("resource_type", "POLICY").tag("action", "DELETE").tag("outcome", "FAILURE")
        .counter().count()).isEqualTo(1.0);
    assertThat(registry.get("sqlmask.admin.duration")
        .tag("resource_type", "POLICY").tag("action", "DELETE").timer().count()).isEqualTo(1L);
  }

  @Test
  void runnableOverloadRecordsVoidMutations() {
    metrics.record("UDF", "DELETE", () -> { });

    assertThat(registry.get("sqlmask.admin.requests")
        .tag("resource_type", "UDF").tag("action", "DELETE").tag("outcome", "SUCCESS")
        .counter().count()).isEqualTo(1.0);
  }
}
