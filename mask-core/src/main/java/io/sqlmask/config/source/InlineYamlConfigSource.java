package io.sqlmask.config.source;

import io.sqlmask.config.YamlConfigLoader;

/** The legacy path: YAML text carried with the request itself. */
public final class InlineYamlConfigSource implements ConfigSource {

  private final String yaml;
  private final String dialect;

  public InlineYamlConfigSource(String yaml, String dialect) {
    this.yaml = yaml;
    this.dialect = dialect;
  }

  @Override
  public ResolvedConfig load() {
    return new ResolvedConfig(
        new YamlConfigLoader().loadContent(yaml, "metadata.yaml", dialect), dialect);
  }
}
