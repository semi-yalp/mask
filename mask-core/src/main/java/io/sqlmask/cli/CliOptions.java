package io.sqlmask.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Validated CLI options.
 *
 * @param metadataPath     path of the YAML metadata configuration
 * @param policiesPath     optional path of a Ranger-style policies.yaml; when given,
 *                         the metadata file must not declare policies/columns/rowFilter
 * @param user             rewrite mode: query subject user (pull-metadata mode: database user)
 * @param groups           rewrite mode: query subject groups, order-preserving
 * @param sql              inline SQL text; mutually exclusive with {@code inputPath}
 * @param inputPath        UTF-8 file with the SQL statements to rewrite
 * @param outputPath       optional output file; stdout when absent
 * @param dialect          dialect name
 * @param instance         policy-service instance name (instance mode; mutually exclusive
 *                         with metadataPath)
 * @param policyServiceUrl policy service base URL override (default $POLICY_SERVICE_URL)
 */
public record CliOptions(Path metadataPath, Path policiesPath, String user, List<String> groups,
    String sql, Path inputPath, Path outputPath, String dialect,
    String instance, String policyServiceUrl) {

  public CliOptions {
    if (metadataPath == null && (instance == null || instance.isBlank())) {
      throw new IllegalArgumentException("metadataPath or instance is required");
    }
    groups = groups == null ? List.of() : List.copyOf(groups);
    dialect = dialect == null ? "postgresql" : dialect;
  }

  /** Legacy convenience constructor: no policy file and an anonymous subject. */
  public CliOptions(Path metadataPath, String sql, Path inputPath, Path outputPath,
      String dialect) {
    this(metadataPath, null, null, null, sql, inputPath, outputPath, dialect, null, null);
  }

  /** Legacy constructor from before policy-service instance mode. */
  public CliOptions(Path metadataPath, Path policiesPath, String user, List<String> groups,
      String sql, Path inputPath, Path outputPath, String dialect) {
    this(metadataPath, policiesPath, user, groups, sql, inputPath, outputPath, dialect,
        null, null);
  }

  /** True when the configuration should come from the policy service. */
  public boolean instanceMode() {
    return instance != null && !instance.isBlank();
  }

  public Optional<Path> output() {
    return Optional.ofNullable(outputPath);
  }
}
