package io.sqlmask.rewrite;

import io.sqlmask.policy.model.MaskInstruction;

import java.util.Optional;

/**
 * Rewriting decision for one output column, identified by ordinal (never by
 * name, so duplicate aliases stay addressable for future dialect-specific
 * mechanisms).
 */
public record OutputRewrite(int ordinal, String outputName, Optional<MaskInstruction> policy) {

  public OutputRewrite {
    policy = policy == null ? Optional.empty() : policy;
  }

  public boolean isMasked() {
    return policy.isPresent();
  }

  public static OutputRewrite passthrough(int ordinal, String outputName) {
    return new OutputRewrite(ordinal, outputName, Optional.empty());
  }

  public static OutputRewrite masked(int ordinal, String outputName, MaskInstruction policy) {
    return new OutputRewrite(ordinal, outputName, Optional.of(policy));
  }
}
