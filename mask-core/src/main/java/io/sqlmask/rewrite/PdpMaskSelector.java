package io.sqlmask.rewrite;

import io.sqlmask.lineage.ColumnOrigin;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.policy.match.PolicyEngine;
import io.sqlmask.policy.model.MaskInstruction;
import io.sqlmask.policy.model.Subject;

import java.util.Optional;
import java.util.Set;

/**
 * PEP adapter: asks the policy decision point for every origin column and
 * applies the legacy-stable tie-break — among origins with a mask decision,
 * the lexicographically smallest normalized column key wins. Priority only
 * resolves conflicts on the same column.
 */
public final class PdpMaskSelector implements MaskSelector {

  private final PolicyEngine engine;
  private final Subject subject;

  public PdpMaskSelector(PolicyEngine engine, Subject subject) {
    this.engine = engine;
    this.subject = subject;
  }

  @Override
  public Optional<MaskInstruction> select(Set<ColumnOrigin> origins) {
    ColumnKey bestKey = null;
    Optional<MaskInstruction> best = Optional.empty();
    for (ColumnOrigin origin : origins) {
      ColumnKey key = origin.key();
      Optional<MaskInstruction> instruction = engine.maskFor(
          key.catalog(), key.schema(), key.table(), key.column(), subject);
      if (instruction.isEmpty()) {
        continue;
      }
      if (bestKey == null || ColumnKey.ORDER.compare(key, bestKey) < 0) {
        bestKey = key;
        best = instruction;
      }
    }
    return best;
  }
}
