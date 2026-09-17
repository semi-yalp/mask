package io.sqlmask.server;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.sqlmask.config.source.EffectiveConfigResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Policy-service data-plane metrics (spec §3.3). The {@code instance} label is
 * the only semi-open label in the catalog: it is accepted only after the
 * instance resolved (admin-bounded); unresolved lookups collapse into the
 * shared {@code (not_found)} series.
 */
@Component
public class EffectiveMetrics {

  static final String NOT_FOUND = "(not_found)";
  private static final String UNKNOWN_DIALECT = "(unknown)";

  private final MeterRegistry registry;
  private final ConcurrentHashMap<String, AtomicLong> versions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicLong> policies = new ConcurrentHashMap<>();

  public EffectiveMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  public void success(String instance, EffectiveConfigResponse response, long startNanos) {
    registry.counter("sqlmask.effective.pull", "instance", instance,
        "dialect", response.dialect(), "outcome", "SUCCESS").increment();
    gauge(versions, "sqlmask.effective.config_version", instance).set(response.configVersion());
    gauge(policies, "sqlmask.effective.policies", instance)
        .set(response.policySummary().enabled());
    compile(instance, startNanos);
  }

  public void notFound(long startNanos) {
    registry.counter("sqlmask.effective.pull", "instance", NOT_FOUND,
        "dialect", NOT_FOUND, "outcome", "FAILURE").increment();
    compile(NOT_FOUND, startNanos);
  }

  /** Only for failures after the instance resolved — the name is admin-bounded. */
  public void failure(String instance, long startNanos) {
    String name = instance == null || instance.isBlank() ? NOT_FOUND : instance;
    registry.counter("sqlmask.effective.pull", "instance", name,
        "dialect", UNKNOWN_DIALECT, "outcome", "FAILURE").increment();
    compile(name, startNanos);
  }

  private void compile(String instance, long startNanos) {
    Timer.builder("sqlmask.effective.compile")
        .tag("instance", instance)
        .serviceLevelObjectives(sloDurations())
        .register(registry)
        .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
  }

  /**
   * Micrometer 1.13 的 Timer SLO 只有 {@code Duration} 重载（无 double 按秒重载），
   * 与 RewriteMetrics/AdminMetrics 同样做法：把 {@link RewriteMetrics#sloSeconds()}
   * 的秒值换算为 Duration（1ms–10s 均为精确纳秒值），桶数值不另复制一份。
   */
  private static Duration[] sloDurations() {
    return Arrays.stream(RewriteMetrics.sloSeconds())
        .mapToObj(seconds -> Duration.ofNanos((long) (seconds * 1_000_000_000L)))
        .toArray(Duration[]::new);
  }

  private AtomicLong gauge(ConcurrentHashMap<String, AtomicLong> map, String name,
      String instance) {
    return map.computeIfAbsent(instance, i -> {
      AtomicLong ref = new AtomicLong();
      Gauge.builder(name, ref, AtomicLong::get).tag("instance", i).register(registry);
      return ref;
    });
  }
}
