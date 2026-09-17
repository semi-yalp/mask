package io.sqlmask.policyserver;

import io.sqlmask.dialect.DialectProfiles;

import java.util.Set;

/** Built-in engine definitions: the dialects the rewrite engine itself supports. */
public final class EngineDefs {

  private EngineDefs() {
  }

  public static Set<String> names() {
    return DialectProfiles.names();
  }
}
