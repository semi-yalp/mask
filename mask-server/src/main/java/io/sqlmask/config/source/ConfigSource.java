package io.sqlmask.config.source;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.policy.model.Subject;

/** Where a rewrite run gets its validated configuration from. */
public interface ConfigSource {

  ResolvedConfig load();

  /** Subject-parameterized load: sources that ignore subjects default to
   * the anonymous behavior of {@link #load()}. */
  default ResolvedConfig load(Subject subject) {
    return load();
  }

  record ResolvedConfig(LoadedConfig config, String dialect, long configVersion) {

    public ResolvedConfig(LoadedConfig config, String dialect) {
      this(config, dialect, 0);
    }
  }
}
