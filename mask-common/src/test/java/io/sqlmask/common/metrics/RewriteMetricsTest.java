package io.sqlmask.common.metrics;

import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RewriteMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final RewriteMetrics metrics = new RewriteMetrics(registry);

  @Test
  void successCountsRequestsStatementsAndFlags() {
    metrics.success("PostgreSQL", List.of(
        new StatementRewrite(0, "SELECT phone FROM c", "SELECT mask(phone) FROM c", true, false),
        new StatementRewrite(1, "SELECT 1", "SELECT 1", false, true)));

    assertThat(counter("sqlmask.rewrite.requests", "dialect", "postgresql",
        "outcome", "SUCCESS", "masked", "true", "row_filtered", "true")).isEqualTo(1.0);
    assertThat(counter("sqlmask.rewrite.statements", "dialect", "postgresql")).isEqualTo(2.0);
  }

  @Test
  void failureRecordsOutcomeAndClosedEnumCode() {
    metrics.failure("mysql", "PARSE_ERROR");

    assertThat(counter("sqlmask.rewrite.requests", "dialect", "mysql",
        "outcome", "FAILURE", "masked", "false", "row_filtered", "false")).isEqualTo(1.0);
    assertThat(counter("sqlmask.rewrite.failures", "dialect", "mysql", "code", "PARSE_ERROR"))
        .isEqualTo(1.0);
  }

  @Test
  void unknownDialectGoesToInvalidSentinel() {
    metrics.success("JUNK", List.of());
    metrics.failure(null, "CONFIG_ERROR");

    assertThat(counter("sqlmask.rewrite.requests", "dialect", "invalid",
        "outcome", "SUCCESS", "masked", "false", "row_filtered", "false")).isEqualTo(1.0);
    assertThat(counter("sqlmask.rewrite.failures", "dialect", "invalid", "code", "CONFIG_ERROR"))
        .isEqualTo(1.0);
    assertThat(registry.get("sqlmask.rewrite.requests").meters().size()).isEqualTo(2);
  }

  @Test
  void durationRecordsWithSloBuckets() {
    metrics.duration("trino", System.nanoTime() - 5_000_000);

    assertThat(registry.get("sqlmask.rewrite.duration").tag("dialect", "trino")
        .timer().count()).isEqualTo(1L);
    // 10s 是最大 SLO 桶，任何成功记录的耗时应落入其中（micrometer 1.13 经 histogramCounts() 读桶计数）
    assertThat(Arrays.stream(registry.get("sqlmask.rewrite.duration").tag("dialect", "trino")
                .timer().takeSnapshot().histogramCounts())
            .filter(b -> b.bucket(TimeUnit.SECONDS) == 10.0)
            .mapToDouble(CountAtBucket::count).sum()).isEqualTo(1.0);
  }

  @Test
  void normalizeIsLowercaseAllowlist() {
    assertThat(RewriteMetrics.normalize("PostgreSQL")).isEqualTo("postgresql");
    assertThat(RewriteMetrics.normalize("trino")).isEqualTo("trino");
    assertThat(RewriteMetrics.normalize(" ")).isEqualTo("invalid");
    assertThat(RewriteMetrics.normalize("pg")).isEqualTo("invalid");
  }

  private double counter(String name, String... tags) {
    return registry.get(name).tags(tags).counter().count();
  }
}
