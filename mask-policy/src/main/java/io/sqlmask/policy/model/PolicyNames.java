package io.sqlmask.policy.model;

import io.sqlmask.policy.PolicyException;

import java.util.Locale;

/**
 * Resource identifier normalization shared by the loader and the matcher:
 * PostgreSQL unquoted-identifier convention (fold to lower case), identical
 * to mask-core's ColumnKey.normalize so both sides agree.
 */
public final class PolicyNames {

  private PolicyNames() {
  }

  public static String normalize(String identifier, String partName) {
    if (identifier == null || identifier.isBlank()) {
      throw new PolicyException(partName + " identifier must not be blank (use \"*\" for any)");
    }
    return identifier.toLowerCase(Locale.ROOT);
  }
}
