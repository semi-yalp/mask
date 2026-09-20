package io.sqlmask.policy.model;

import java.util.List;

/**
 * The masking decision for one column: which UDF to call with which arguments.
 *
 * <p>v3 (2026-09-21): rebuilt in place; contract byte-identical.
 */
public record MaskInstruction(String policyName, String udf, List<Object> arguments) {

  public MaskInstruction {
    arguments = arguments == null ? List.of() : List.copyOf(arguments);
  }
}