package io.sqlmask.dialect;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Name -> adapter factory; the single place that instantiates dialects. */
public final class DialectRegistry {

  private static final Map<String, Function<DialectFeatures, DialectAdapter>> ADAPTERS =
      new LinkedHashMap<>();

  static {
    ADAPTERS.put(PostgresqlDialectAdapter.NAME, PostgresqlDialectAdapter::new);
    ADAPTERS.put(TrinoDialectAdapter.NAME, TrinoDialectAdapter::new);
    ADAPTERS.put(MysqlDialectAdapter.NAME, MysqlDialectAdapter::new);
    ADAPTERS.put(HiveDialectAdapter.NAME, HiveDialectAdapter::new);
    ADAPTERS.put(SparkSqlDialectAdapter.NAME, SparkSqlDialectAdapter::new);
  }

  public static DialectAdapter create(String name) {
    return create(name, DialectFeatures.DEFAULTS);
  }

  /** Creates the dialect with per-instance syntax-extension overrides
   * ({@link DialectFeatures#DEFAULTS} keeps the dialect defaults). */
  public static DialectAdapter create(String name, DialectFeatures features) {
    DialectProfile profile = DialectProfiles.byName(name);
    Function<DialectFeatures, DialectAdapter> factory = ADAPTERS.get(profile.name());
    if (factory == null) {
      throw new IllegalStateException("no adapter for " + profile.name());
    }
    return features == null ? factory.apply(DialectFeatures.DEFAULTS) : factory.apply(features);
  }

  private DialectRegistry() {
  }
}
