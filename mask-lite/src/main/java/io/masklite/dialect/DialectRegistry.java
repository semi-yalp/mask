package io.masklite.dialect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Name -> adapter factory; the single place that instantiates dialects.
 * mask-lite 只注册 PostgreSQL：其余方言名经 DialectProfiles 统一报配置错误。
 */
public final class DialectRegistry {

  public static final String POSTGRESQL = PostgresqlDialectAdapter.NAME;

  private static final Map<String, Supplier<DialectAdapter>> ADAPTERS = new LinkedHashMap<>();

  static {
    ADAPTERS.put(PostgresqlDialectAdapter.NAME, PostgresqlDialectAdapter::new);
  }

  public static DialectAdapter create(String name) {
    DialectProfile profile = DialectProfiles.byName(name);
    Supplier<DialectAdapter> factory = ADAPTERS.get(profile.name());
    if (factory == null) {
      throw new IllegalStateException("no adapter for " + profile.name());
    }
    return factory.get();
  }

  private DialectRegistry() {
  }
}
