package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.store.MemoryRiskStore;

import java.time.Instant;
import java.util.function.Predicate;

/**
 * Per-evaluation capabilities handed to {@link RuleEvaluator}s: sliding-window
 * counting over the stored event stream and the first-access baseline.
 */
public final class RuleContext {

  private final MemoryRiskStore store;
  private final boolean dryRun;
  private final BaselineService baseline;

  public RuleContext(MemoryRiskStore store) {
    this(store, false, null);
  }

  /** Dry-run contexts never mutate the first-access baseline (rule testing). */
  public RuleContext(MemoryRiskStore store, boolean dryRun) {
    this(store, dryRun, null);
  }

  public RuleContext(MemoryRiskStore store, boolean dryRun, BaselineService baseline) {
    this.store = store;
    this.dryRun = dryRun;
    this.baseline = baseline;
  }

  /** UEBA profiles; null when the engine runs without a baseline service. */
  public BaselineService baseline() {
    return baseline;
  }

  /**
   * Counts stored events at or after {@code since} matching {@code filter}.
   * The event currently being evaluated is already stored, so bursts count it.
   */
  public long countEvents(Instant since, Predicate<RiskEvent> filter) {
    return store.countEventsSince(since.toEpochMilli(), filter);
  }

  /**
   * Registers the user against the column in the first-access baseline;
   * returns true when the user was already known (false = first access).
   * Dry-run contexts only read the baseline.
   */
  public boolean registerBaseline(String columnKey, String user) {
    if (dryRun) {
      return store.baselineSeen(columnKey, user);
    }
    return store.registerBaseline(columnKey, user);
  }
}
