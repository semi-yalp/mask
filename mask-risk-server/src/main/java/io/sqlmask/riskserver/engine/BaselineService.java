package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.store.MemoryRiskStore;

import java.util.Map;

/**
 * Lazily computes and caches {@link UserProfile}s over the stored event
 * window. Recomputation is O(events) and happens at most once per TTL, so
 * per-event rule evaluation stays cheap; profiles are derived state (the
 * events themselves are what persists).
 */
public class BaselineService {

  private final MemoryRiskStore store;
  private final long ttlMs;
  private final Object lock = new Object();
  private volatile Map<String, UserProfile> cache = Map.of();
  private volatile long computedAt = Long.MIN_VALUE / 2;

  public BaselineService(MemoryRiskStore store, long ttlMs) {
    this.store = store;
    this.ttlMs = Math.max(5_000, ttlMs);
  }

  /** Current profiles; recomputes when the cache is older than the TTL. */
  public Map<String, UserProfile> profiles() {
    long now = System.currentTimeMillis();
    if (now - computedAt > ttlMs) {
      synchronized (lock) {
        now = System.currentTimeMillis();
        if (now - computedAt > ttlMs) {
          cache = UserProfile.compute(store.snapshotEvents());
          computedAt = now;
        }
      }
    }
    return cache;
  }

  /** @return the (possibly cached) profile for one user, or null. */
  public UserProfile profile(String user) {
    if (user == null) {
      return null;
    }
    return profiles().get(user);
  }

  /** Test/admin hook: forces the next {@link #profiles()} call to recompute. */
  public void invalidate() {
    computedAt = Long.MIN_VALUE / 2;
  }
}
