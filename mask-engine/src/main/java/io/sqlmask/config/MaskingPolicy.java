package io.sqlmask.config;

import java.util.List;

/**
 * A named masking policy: the target UDF identifier plus an ordered list of
 * scalar arguments rendered verbatim into the outer projection.
 */
public record MaskingPolicy(String name, String udf, List<Object> arguments) {

  public MaskingPolicy {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("policy name must not be blank");
    }
    if (udf == null || udf.isBlank()) {
      throw new IllegalArgumentException("policy '" + name + "' requires a udf identifier");
    }
    arguments = List.copyOf(arguments);
  }

  public MaskingPolicy(String name, String udf) {
    this(name, udf, List.of());
  }

  /**
   * Validates that every argument is a YAML scalar usable as a SQL literal
   * (string, boolean or number).
   */
  public static void validateArguments(List<Object> arguments, String policyName, String path) {
    for (Object argument : arguments) {
      if (argument == null
          || argument instanceof java.util.Map<?, ?>
          || argument instanceof java.util.List<?>) {
        throw new io.sqlmask.error.SqlMaskException(
            io.sqlmask.error.SqlMaskException.Code.CONFIG_ERROR,
            path + ": policy '" + policyName + "' argument '" + argument
                + "' must be a scalar (string, number or boolean)");
      }
    }
  }
}
