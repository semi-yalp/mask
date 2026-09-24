package io.sqlmask.server.rewrite;

import io.sqlmask.common.effective.EffectiveConfigResponse;
import io.sqlmask.common.metrics.EffectiveMetrics;
import io.sqlmask.config.source.ConfigSource;
import io.sqlmask.config.source.EffectiveConfigAssembler;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policyserver.PolicyService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * In-process {@link ConfigSource} over the policy domain services: compiles
 * the subject's effective configuration locally, no HTTP. A per-subject
 * access-ordered LRU (same ceiling and locking discipline as the HTTP
 * source's) shortens repeat loads; entries are stamped with the store's
 * config version and recompiled as soon as it moves, so — unlike the remote
 * transport's 30s poll — a policy change is visible on the very next request.
 */
public final class LocalPolicyConfigSource implements ConfigSource {

  /** Cached-subject ceiling; excess evicts least-recently-used. */
  private static final int MAX_CACHED_SUBJECTS = 256;

  private record SubjectKey(String user, List<String> groups) {
  }

  private record Versioned(ResolvedConfig config, long storeVersion) {
  }

  private final PolicyService service;
  private final String instanceName;
  private final EffectiveMetrics metrics;
  private final Map<SubjectKey, Versioned> cache =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<SubjectKey, Versioned> eldest) {
          return size() > MAX_CACHED_SUBJECTS;
        }
      };

  public LocalPolicyConfigSource(PolicyService service, String instanceName,
                                 EffectiveMetrics metrics) {
    this.service = service;
    this.instanceName = instanceName;
    this.metrics = metrics;
  }

  @Override
  public ResolvedConfig load() {
    return load(Subject.anonymous());
  }

  public ResolvedConfig load(Subject subject) {
    SubjectKey key = keyOf(subject);
    long storeVersion = service.configVersion(instanceName);
    synchronized (cache) {
      Versioned cached = cache.get(key);
      if (cached != null && cached.storeVersion() == storeVersion) {
        return cached.config();
      }
    }
    long start = System.nanoTime();
    EffectiveConfigResponse payload = service.effective(instanceName, subject);
    ResolvedConfig fresh = new ResolvedConfig(
        new EffectiveConfigAssembler().assemble(payload), payload.dialect(),
        payload.configVersion());
    if (metrics != null) {
      metrics.success(instanceName, payload, start);
    }
    synchronized (cache) {
      cache.put(key, new Versioned(fresh, storeVersion));
    }
    return fresh;
  }

  /** Drops one subject (null/blank = all); used after manual config edits. */
  public int invalidate(String user) {
    synchronized (cache) {
      if (user == null || user.isBlank()) {
        int n = cache.size();
        cache.clear();
        return n;
      }
      return cache.keySet().removeIf(k -> user.equals(k.user())) ? 1 : 0;
    }
  }

  private static SubjectKey keyOf(Subject subject) {
    return new SubjectKey(subject.user(),
        List.copyOf(new TreeSet<>(subject.groups())));
  }
}
