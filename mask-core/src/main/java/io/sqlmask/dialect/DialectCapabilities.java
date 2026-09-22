package io.sqlmask.dialect;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

/**
 * Dialect-specific abilities that decide whether a planned rewrite can be
 * rendered safely. Rendering is refused instead of generating SQL that could
 * reference the wrong column.
 */
public record DialectCapabilities(boolean canWrapDuplicateOutputNames,
                                 boolean supportsDerivedColumnAliasList) {

  /** Refuses to wrap duplicate output names; derived-table column alias lists
   * ({@code FROM (...) AS r (a, b)}) render fine. PostgreSQL, MySQL, Trino. */
  public static final DialectCapabilities STRICT = new DialectCapabilities(false, true);

  /** Same refusals, and no alias-list support (Hive, SparkSQL): wrapping an
   * unnamed computed column would leave it unreferenceable, so the rewrite is
   * refused with a naming hint instead. */
  public static final DialectCapabilities STRICT_NO_ALIAS_LIST =
      new DialectCapabilities(false, false);

  public Map<String, Object> describe() {
    return ImmutableMap.of("canWrapDuplicateOutputNames", canWrapDuplicateOutputNames,
        "supportsDerivedColumnAliasList", supportsDerivedColumnAliasList);
  }
}
