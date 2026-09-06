package io.sqlmask.rewrite;

import io.sqlmask.lineage.ColumnOrigin;
import io.sqlmask.policy.model.MaskInstruction;

import java.util.Optional;
import java.util.Set;

/** Chooses the masking instruction for one output column from its origin columns. */
public interface MaskSelector {

  Optional<MaskInstruction> select(Set<ColumnOrigin> origins);
}
