package io.sqlmask.cli;

import io.sqlmask.error.SqlMaskException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * CLI entry point. Argument parsing happens here; the rewrite pipeline is
 * orchestrated by {@link SqlMaskRunner}.
 */
@Command(
    name = "sql-mask",
    mixinStandardHelpOptions = true,
    version = "sql-mask 0.1.0",
    description = "Rewrites PostgreSQL SELECT queries so configured masking UDFs "
        + "are applied on the outermost projection. The tool never executes SQL.")
public final class SqlMaskApplication implements Callable<Integer> {

  @Option(names = "--metadata", required = true, paramLabel = "<path>",
      description = "Path to the YAML file declaring tables, columns and masking policies.")
  private Path metadataPath;

  @Option(names = "--sql", paramLabel = "<sql>",
      description = "SQL text to rewrite. Exactly one of --sql/--input must be given.")
  private String sql;

  @Option(names = "--input", paramLabel = "<path>",
      description = "UTF-8 file containing the SQL statements to rewrite. "
          + "Exactly one of --sql/--input must be given.")
  private Path inputPath;

  @Option(names = "--output", paramLabel = "<path>",
      description = "Write the rewritten SQL to this file instead of stdout.")
  private Path outputPath;

  @Option(names = "--dialect", defaultValue = "postgresql",
      description = "Target dialect; only 'postgresql' is supported in this version.")
  private String dialect;

  private PrintStream outStream = System.out;
  private PrintStream errStream = System.err;

  public static void main(String[] args) {
    System.exit(new SqlMaskApplication().run(args, System.in, System.out, System.err));
  }

  /**
   * Testable entry point. Returns the process exit code: 0 on success,
   * non-zero when parsing, validation, rewriting or I/O fails. Output is
   * written only after the whole input has been processed successfully.
   */
  public int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
    outStream = out;
    errStream = err;
    CommandLine commandLine = new CommandLine(this);
    commandLine.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
    commandLine.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
    try {
      return commandLine.execute(args);
    } catch (RuntimeException e) {
      err.println("sql-mask: " + e.getMessage());
      return 1;
    }
  }

  /** Invoked by picocli after successful argument parsing. */
  @Override
  public Integer call() {
    try {
      return execute(outStream, errStream);
    } catch (SqlMaskException e) {
      errStream.println("sql-mask: [" + e.getCode() + "] " + e.getMessage());
      return 1;
    } catch (Exception e) {
      errStream.println("sql-mask: unexpected error: " + e.getMessage());
      return 1;
    }
  }

  private int execute(PrintStream out, PrintStream err) throws IOException {
    if ((sql == null) == (inputPath == null)) {
      err.println("sql-mask: specify exactly one of --sql or --input");
      return 2;
    }
    if (!"postgresql".equalsIgnoreCase(dialect)) {
      err.println("sql-mask: unsupported dialect '" + dialect + "' "
          + "(only 'postgresql' is supported in this version)");
      return 2;
    }

    CliOptions options = new CliOptions(metadataPath, sql, inputPath, outputPath, dialect);
    // the runner aborts on the first failing statement, so this returns only
    // when the entire input succeeded; output happens after that point
    String result = new SqlMaskRunner().run(options);

    if (options.output().isPresent()) {
      Path output = options.output().get();
      Files.writeString(output, result.isEmpty() ? "" : result + "\n", StandardCharsets.UTF_8);
    } else if (!result.isEmpty()) {
      out.println(result);
    }
    return 0;
  }
}
