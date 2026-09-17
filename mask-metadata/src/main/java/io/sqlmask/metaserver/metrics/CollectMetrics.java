package io.sqlmask.metaserver.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Metadata collection metrics (spec §3.4). The engine label comes from the
 * stored instance row (admin-bounded), never from free request input.
 */
@Component
public class CollectMetrics {

  private final MeterRegistry registry;

  public CollectMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public <T> T record(String engine, Supplier<T> work) {
    long start = System.nanoTime();
    try {
      T result = work.get();
      registry.counter("sqlmask.metadata.collect", "engine", engine, "outcome", "SUCCESS")
          .increment();
      return result;
    } catch (RuntimeException e) {
      registry.counter("sqlmask.metadata.collect", "engine", engine, "outcome", "FAILURE")
          .increment();
      throw e;
    } finally {
      Timer.builder("sqlmask.metadata.collect.duration")
          .tag("engine", engine)
          .serviceLevelObjectives(sloDurations())
          .register(registry)
          .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
  }

  public void warnings(String engine, int count) {
    if (count > 0) {
      registry.counter("sqlmask.metadata.warnings", "engine", engine).increment(count);
    }
  }

  /** SLO bucket bounds in seconds, spec §5 采集档（10ms–60s）。 */
  private static double[] sloSeconds() {
    return new double[] {0.010, 0.050, 0.100, 0.250, 0.500, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0};
  }

  /**
   * Micrometer 1.13 的 Timer SLO 只有 {@code Duration} 重载（无 double 按秒重载），
   * 与 mask-core RewriteMetrics 同样做法：把 {@link #sloSeconds()} 的秒值换算为
   * Duration（各桶均为精确纳秒值）。
   */
  private static Duration[] sloDurations() {
    return Arrays.stream(sloSeconds())
        .mapToObj(seconds -> Duration.ofNanos((long) (seconds * 1_000_000_000L)))
        .toArray(Duration[]::new);
  }
}
