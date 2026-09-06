package io.sqlmask.policy;

import io.sqlmask.metadata.ColumnKey;

import java.util.Map;
import java.util.Optional;

/**
 * Exact-match index from normalized {@link ColumnKey} to its configured
 * {@link MaskingPolicy}. Lookup is the only policy matching mechanism in
 * this version: no wildcards, regular expressions or tags.
 */
public final class PolicyRegistry {

  private final Map<String, MaskingPolicy> policiesByName;
  private final Map<ColumnKey, MaskingPolicy> policiesByColumn;

  public PolicyRegistry(Map<String, MaskingPolicy> policiesByName,
      Map<ColumnKey, MaskingPolicy> policiesByColumn) {
    this.policiesByName = Map.copyOf(policiesByName);
    this.policiesByColumn = Map.copyOf(policiesByColumn);
  }

  /** Exact lookup on the normalized column key. */
  public Optional<MaskingPolicy> find(ColumnKey key) {
    return Optional.ofNullable(policiesByColumn.get(key));
  }

  /** Lookup by declared policy name, used for configuration validation. */
  public Optional<MaskingPolicy> byName(String name) {
    return Optional.ofNullable(policiesByName.get(name));
  }

  public Map<ColumnKey, MaskingPolicy> columnBindings() {
    return policiesByColumn;
  }
}
