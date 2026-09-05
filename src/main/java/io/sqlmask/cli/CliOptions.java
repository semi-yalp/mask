package io.sqlmask.cli;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Validated CLI options.
 *
 * @param metadataPath path of the YAML metadata/policy configuration
 * @param sql          inline SQL text; mutually exclusive with {@code inputPath}
 * @param inputPath    UTF-8 file with the SQL statements to rewrite
 * @param outputPath   optional output file; stdout when absent
 * @param dialect      dialect name, only {@code postgresql} in this version
 */
public record CliOptions(Path metadataPath, String sql, Path inputPath, Path outputPath,
    String dialect) {

  public CliOptions {
    java.util.Objects.requireNonNull(metadataPath, "metadataPath");
    dialect = dialect == null ? "postgresql" : dialect;
  }

  public Optional<Path> output() {
    return Optional.ofNullable(outputPath);
  }
}
