package io.sqlmask.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Data-plane rewrite metrics (spec 2026-09-17-prometheus-metrics §3.1). Label
 * values from request input pass through {@link #normalize} first so the
 * series cardinality is bounded by code, not by callers.
 */
@Component
public class RewriteMetrics {

  // 与 DialectProfiles.names() 同源；星号进 label 会基数爆炸，未知仍归 invalid
  private static final Set<String> DIALECTS = Set.of("postgresql", "mysql", "trino",
      "hive", "sparksql");

  private final MeterRegistry registry;

  public RewriteMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void success(String dialect, List<StatementRewrite> statements) {
    String d = normalize(dialect);
    registry.counter("sqlmask.rewrite.requests", "dialect", d, "outcome", "SUCCESS",
            "masked", String.valueOf(any(statements, StatementRewrite::masked)),
            "row_filtered", String.valueOf(any(statements, StatementRewrite::rowFiltered)))
        .increment();
    registry.counter("sqlmask.rewrite.statements", "dialect", d).increment(statements.size());
  }

  public void failure(String dialect, String code) {
    String d = normalize(dialect);
    registry.counter("sqlmask.rewrite.requests", "dialect", d, "outcome", "FAILURE",
            "masked", "false", "row_filtered", "false").increment();
    registry.counter("sqlmask.rewrite.failures", "dialect", d, "code", code).increment();
  }

  /** Called from a finally block with the nanoTime captured before the rewrite. */
  public void duration(String dialect, long startNanos) {
    Timer.builder("sqlmask.rewrite.duration")
        .tag("dialect", normalize(dialect))
        .serviceLevelObjectives(sloDurations())
        .register(registry)
        .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
  }

  static String normalize(String raw) {
    if (raw == null) {
      return "invalid";
    }
    String d = raw.trim().toLowerCase(Locale.ROOT);
    return DIALECTS.contains(d) ? d : "invalid";
  }

  /** SLO bucket bounds in seconds, spec §5 (1ms–10s). Task 3/4 的指标类复用同一组桶。 */
  public static double[] sloSeconds() {
    return new double[] {0.001, 0.005, 0.010, 0.025, 0.050, 0.100, 0.250, 0.500,
        1.0, 5.0, 10.0};
  }

  /**
   * Micrometer 1.13 的 Timer SLO 只有 {@code Duration} 重载（无 double 按秒重载），
   * 故把 {@link #sloSeconds()} 的秒值换算为 Duration（1ms–10s 均为精确纳秒值）。
   */
  private static Duration[] sloDurations() {
    return Arrays.stream(sloSeconds())
        .mapToObj(seconds -> Duration.ofNanos((long) (seconds * 1_000_000_000L)))
        .toArray(Duration[]::new);
  }

  private static boolean any(List<StatementRewrite> statements,
      java.util.function.Predicate<StatementRewrite> flag) {
    return statements.stream().anyMatch(flag);
  }
}
