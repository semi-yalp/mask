package io.sqlmask.config.source;

import io.sqlmask.config.LoadedConfig;

/** Where a rewrite run gets its validated configuration from. */
public interface ConfigSource {

  ResolvedConfig load();

  record ResolvedConfig(LoadedConfig config, String dialect, long configVersion) {

    public ResolvedConfig(LoadedConfig config, String dialect) {
      this(config, dialect, 0);
    }
  }
}
