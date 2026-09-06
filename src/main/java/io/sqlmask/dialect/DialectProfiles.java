package io.sqlmask.dialect;

import io.sqlmask.error.SqlMaskException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Name -> profile lookup, case-insensitive, stable declaration order. */
public final class DialectProfiles {

  private static final Map<String, DialectProfile> PROFILES = new LinkedHashMap<>();

  static {
    PostgresqlDialectAdapter pg = new PostgresqlDialectAdapter();
    PROFILES.put(pg.name(), pg.profile());
  }

  public static DialectProfile byName(String name) {
    DialectProfile profile = name == null ? null : PROFILES.get(name.toLowerCase(Locale.ROOT));
    if (profile == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unsupported dialect '" + name + "'; supported dialects: "
              + String.join(", ", PROFILES.keySet()));
    }
    return profile;
  }

  public static java.util.Set<String> names() {
    return java.util.Collections.unmodifiableSet(PROFILES.keySet());
  }

  private DialectProfiles() {
  }
}
