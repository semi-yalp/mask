package io.sqlmask.cli;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.rewrite.RewriteEngine;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CLI orchestration on top of {@link RewriteEngine}: reads the YAML
 * configuration and SQL input from files, delegates rewriting, and returns
 * the complete output. The output is produced only after every statement
 * succeeds; any failure aborts without a partial result.
 */
public final class SqlMaskRunner {

  /**
   * Runs the pipeline and returns the complete rewritten SQL. Statements are
   * joined with a blank line and terminated with a semicolon each; the
   * formatting is Calcite-generated SQL, not a preservation of the input
   * formatting.
   */
  public String run(CliOptions options) {
    String metadataYaml = readUtf8(options.metadataPath(), "metadata file");
    String policyYaml = options.policiesPath() == null
        ? null
        : readUtf8(options.policiesPath(), "policies file");
    String sqlText = options.sql() != null
        ? options.sql()
        : readUtf8(options.inputPath(), "SQL input file");
    List<StatementRewrite> statements = new RewriteEngine().rewrite(
        metadataYaml, policyYaml, sqlText, options.dialect(),
        Subject.of(options.user(), options.groups()));
    return RewriteEngine.join(statements);
  }

  private String readUtf8(Path path, String what) {
    if (path == null) {
      throw new SqlMaskException(SqlMaskException.Code.IO_ERROR,
          "no " + what + " given");
    }
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new SqlMaskException(SqlMaskException.Code.IO_ERROR,
          "cannot read " + what + " '" + path + "': " + e.getMessage(), e);
    }
  }
}
