package io.sqlmask.dialect;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

/**
 * Dialect-specific abilities that decide whether a planned rewrite can be
 * rendered safely. Rendering is refused instead of generating SQL that could
 * reference the wrong column.
 */
public record DialectCapabilities(boolean canWrapDuplicateOutputNames) {

  /** Refuses to wrap duplicate output names: the rewrite fails instead. */
  public static final DialectCapabilities STRICT = new DialectCapabilities(false);

  public Map<String, Object> describe() {
    return ImmutableMap.of("canWrapDuplicateOutputNames", canWrapDuplicateOutputNames);
  }
}
