package io.sqlmask.metaserver.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Management-plane change metrics (spec §3.2). Wraps one mutation: counts the
 * outcome and records the duration, rethrowing business exceptions untouched.
 * Controllers call it outside the existing {@code AuditAdminHelper.adminChange}
 * wrap so a failed audit and a failed metric coincide (spec §5.1). Duplicate of
 * the mask-core class on purpose: the two services share no module (spec §1.2).
 */
@Component("metadataAdminMetrics")
public class AdminMetrics {

  private final MeterRegistry registry;

  public AdminMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public <T> T record(String resourceType, String action, Supplier<T> work) {
    long start = System.nanoTime();
    try {
      T result = work.get();
      counter(resourceType, action, "SUCCESS").increment();
      return result;
    } catch (RuntimeException e) {
      counter(resourceType, action, "FAILURE").increment();
      throw e;
    } finally {
      Timer.builder("sqlmask.admin.duration")
          .tag("resource_type", resourceType).tag("action", action)
          .serviceLevelObjectives(sloDurations())
          .register(registry)
          .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }
  }

  public void record(String resourceType, String action, Runnable work) {
    record(resourceType, action, () -> {
      work.run();
      return null;
    });
  }

  /** SLO bucket bounds in seconds, spec §5 管理面档（1ms–10s）。 */
  private static double[] sloSeconds() {
    return new double[] {0.001, 0.005, 0.010, 0.025, 0.050, 0.100, 0.250, 0.500,
        1.0, 5.0, 10.0};
  }

  /**
   * Micrometer 1.13 的 Timer SLO 只有 {@code Duration} 重载（无 double 按秒重载），
   * 与 mask-core RewriteMetrics 同样做法：把 {@link #sloSeconds()} 的秒值换算为
   * Duration（1ms–10s 均为精确纳秒值）。
   */
  private static Duration[] sloDurations() {
    return Arrays.stream(sloSeconds())
        .mapToObj(seconds -> Duration.ofNanos((long) (seconds * 1_000_000_000L)))
        .toArray(Duration[]::new);
  }

  private Counter counter(String resourceType, String action, String outcome) {
    return registry.counter("sqlmask.admin.requests", "resource_type", resourceType,
        "action", action, "outcome", outcome);
  }
}
