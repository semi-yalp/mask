package io.sqlmask.dialect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Name -> adapter factory; the single place that instantiates dialects. */
public final class DialectRegistry {

  private static final Map<String, Supplier<DialectAdapter>> ADAPTERS = new LinkedHashMap<>();

  static {
    ADAPTERS.put(PostgresqlDialectAdapter.NAME, PostgresqlDialectAdapter::new);
    ADAPTERS.put(TrinoDialectAdapter.NAME, TrinoDialectAdapter::new);
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
