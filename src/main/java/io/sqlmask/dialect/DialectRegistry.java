package io.sqlmask.dialect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Name -> adapter factory; the single place that instantiates dialects. */
public final class DialectRegistry {

  private static final Map<String, Supplier<DialectAdapter>> ADAPTERS = new LinkedHashMap<>();

  static {
    ADAPTERS.put(PostgresqlDialectAdapter.NAME, PostgresqlDialectAdapter::new);
  }

  public static DialectAdapter create(String name) {
    return newAdapter(DialectProfiles.byName(name));
  }

  private static DialectAdapter newAdapter(DialectProfile profile) {
    return switch (profile.name()) {
      case PostgresqlDialectAdapter.NAME -> new PostgresqlDialectAdapter();
      default -> throw new IllegalStateException("no adapter for " + profile.name());
    };
  }

  private DialectRegistry() {
  }
}
