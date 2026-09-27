package io.masklite.dialect;

import io.masklite.error.SqlMaskException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Name -> profile lookup, case-insensitive, stable declaration order.
 * mask-lite 只支持 PostgreSQL 方言：任何其他名字都是配置错误。
 */
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
          "unsupported dialect '" + name + "'; mask-lite only supports: "
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
