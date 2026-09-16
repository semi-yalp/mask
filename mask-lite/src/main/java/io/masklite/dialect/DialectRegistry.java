package io.masklite.dialect;

import io.masklite.error.SqlMaskException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** Name -> dialect factory; the single place that instantiates dialects. */
public final class DialectRegistry {

  /** Dialect used unless a caller picks one explicitly. */
  public static final String DEFAULT = PostgresDialect.NAME;

  private static final Map<String, Supplier<Dialect>> DIALECTS = new LinkedHashMap<>();

  static {
    DIALECTS.put(PostgresDialect.NAME, PostgresDialect::new);
  }

  /** Creates the dialect registered under {@code name} (case-insensitive). */
  public static Dialect create(String name) {
    Supplier<Dialect> factory = name == null ? null : DIALECTS.get(name.toLowerCase(Locale.ROOT));
    if (factory == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unsupported dialect '" + name + "'; supported dialects: "
              + String.join(", ", DIALECTS.keySet()));
    }
    return factory.get();
  }

  private DialectRegistry() {
  }
}
