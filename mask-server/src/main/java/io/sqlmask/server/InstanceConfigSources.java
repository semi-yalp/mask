package io.sqlmask.server;
import io.sqlmask.common.metrics.EffectiveMetrics;
import io.sqlmask.common.metrics.RewriteMetrics;

import io.sqlmask.config.source.PolicyServiceConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-instance cache of policy-service clients so rewrite requests reuse the
 * client's subject LRU and stale-but-available cache. The poll cycle calls
 * every client's refresh(); a failed poll is logged and leaves the cached
 * config in place (fail-closed on a cold cache lives in the client itself).
 */
@Component
public class InstanceConfigSources {

  private static final Logger log = LoggerFactory.getLogger(InstanceConfigSources.class);

  private final String baseUrl;
  private final String apiKey;
  private final EffectiveMetrics metrics;
  private final Map<String, PolicyServiceConfigSource> sources = new ConcurrentHashMap<>();

  public InstanceConfigSources(@Value("${policy.service.url:}") String baseUrl,
      @Value("${policy.service.api-key:}") String apiKey) {
    this(baseUrl, apiKey, null);
  }

  /** {@code metrics} may be null in non-Spring constructions (CLI). */
  @org.springframework.beans.factory.annotation.Autowired
  public InstanceConfigSources(@Value("${policy.service.url:}") String baseUrl,
      @Value("${policy.service.api-key:}") String apiKey, EffectiveMetrics metrics) {
    this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    this.apiKey = apiKey;
    this.metrics = metrics;
  }

  /** False when policy.service.url is not configured: instance mode is unavailable. */
  public boolean configured() {
    return !baseUrl.isEmpty();
  }

  public PolicyServiceConfigSource get(String instance) {
    return sources.computeIfAbsent(instance,
        i -> new PolicyServiceConfigSource(baseUrl, apiKey, i, metrics));
  }

  /** Drops one instance (null/blank = all); the next load re-fetches. Returns the count. */
  public int clear(String instance) {
    if (instance == null || instance.isBlank()) {
      int n = sources.size();
      sources.clear();
      return n;
    }
    return sources.remove(instance) != null ? 1 : 0;
  }

  @Scheduled(fixedDelayString = "${policy.service.poll-interval-ms:30000}")
  public void refreshAll() {
    for (Map.Entry<String, PolicyServiceConfigSource> entry : sources.entrySet()) {
      try {
        if (entry.getValue().refresh()) {
          log.info("policy config refreshed for instance '{}'", entry.getKey());
        }
      } catch (RuntimeException e) {
        log.warn("policy config refresh failed for instance '{}' (serving stale cache): {}",
            entry.getKey(), e.getMessage());
      }
    }
  }
}
