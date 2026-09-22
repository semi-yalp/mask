package io.masklite;

import io.masklite.config.MaskingConfig;
import io.masklite.config.YamlConfigLoader;
import io.masklite.rewrite.RewriteEngine;

import java.nio.file.Path;
import java.util.List;

/**
 * The one-entry facade of mask-lite: load a metadata YAML once, rewrite any
 * number of PostgreSQL SELECT statements with the declared column masking
 * policies and static row-filter conditions.
 *
 * <pre>
 * MaskLite mask = MaskLite.fromYamlFile(Path.of("metadata.yaml"));
 * String masked = mask.rewrite("SELECT phone FROM crm.public.customer");
 * </pre>
 *
 * Input and output are both PostgreSQL dialect. Only SELECT (and
 * {@code WITH ... SELECT}) statements are accepted; anything else, any
 * output column whose origin cannot be traced, or any FROM shape that a
 * declared row filter cannot be injected into safely fails with a
 * {@link io.masklite.error.SqlMaskException} instead of silently passing
 * sensitive data through.
 */
public final class MaskLite {

  private final MaskingConfig config;
  private final RewriteEngine engine = new RewriteEngine();

  private MaskLite(MaskingConfig config) {
    this.config = config;
  }

  /** Loads and validates metadata YAML content. */
  public static MaskLite fromYaml(String metadataYaml) {
    return new MaskLite(new YamlConfigLoader().loadContent(metadataYaml, "metadata.yaml"));
  }

  /** Loads and validates the metadata YAML file at {@code path}. */
  public static MaskLite fromYamlFile(Path path) {
    return new MaskLite(new YamlConfigLoader().load(path));
  }

  /**
   * Rewrites one or more semicolon-separated statements and joins the
   * results into a single script ({@code ';'} per statement, blank line
   * between statements). Empty input yields an empty string.
   */
  public String rewrite(String sql) {
    return RewriteEngine.join(rewriteStatements(sql));
  }

  /** Per-statement form of {@link #rewrite(String)}. */
  public List<RewriteEngine.StatementRewrite> rewriteStatements(String sql) {
    return engine.rewrite(config, sql);
  }
}
