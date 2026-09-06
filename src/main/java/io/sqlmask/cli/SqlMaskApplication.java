package io.sqlmask.cli;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.introspect.ConnectionSpec;
import io.sqlmask.introspect.IntrospectionResult;
import io.sqlmask.introspect.MetadataYamlGenerator;
import io.sqlmask.introspect.PgMetadataIntrospector;
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
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI entry point. Argument parsing happens here; the rewrite pipeline is
 * orchestrated by {@link SqlMaskRunner}.
 */
@Command(
    name = "sql-mask",
    mixinStandardHelpOptions = true,
    version = "sql-mask 0.1.0",
    description = "Two modes: rewrite PostgreSQL SELECT queries so configured masking "
        + "UDFs are applied on the outermost projection (--sql/--input with --metadata), "
        + "or pull table/column metadata from a live PostgreSQL database and emit a "
        + "YAML skeleton (--pull-metadata). The rewrite mode never executes SQL; "
        + "the metadata pull opens a read-only connection.")
public final class SqlMaskApplication implements Callable<Integer> {

  @Option(names = "--metadata", paramLabel = "<path>",
      description = "Path to the YAML file declaring tables, columns and masking policies.")
  private Path metadataPath;

  @Option(names = "--pull-metadata",
      description = "Pull table/column metadata from PostgreSQL and emit a metadata "
          + "YAML skeleton to --output instead of rewriting SQL.")
  private boolean pullMetadata;

  @Option(names = "--host", defaultValue = "127.0.0.1",
      description = "PostgreSQL host for --pull-metadata (default 127.0.0.1).")
  private String host;

  @Option(names = "--port", defaultValue = "5432",
      description = "PostgreSQL port for --pull-metadata (default 5432).")
  private int port;

  @Option(names = "--database", paramLabel = "<db>",
      description = "Database to introspect (catalog in the generated YAML).")
  private String database;

  @Option(names = "--user", paramLabel = "<user>",
      description = "PostgreSQL user for --pull-metadata.")
  private String user;

  @Option(names = "--password", paramLabel = "<pw>",
      description = "Password for --pull-metadata; falls back to $PGPASSWORD.")
  private String password;

  @Option(names = "--schema", arity = "1..*", paramLabel = "<schema>",
      description = "Schema filter; repeatable. Absent means all non-system schemas.")
  private List<String> schemas;

  @Option(names = "--include-views",
      description = "Also include views and materialized views.")
  private boolean includeViews;

  @Option(names = "--strict",
      description = "Fail when any column type degrades to varchar.")
  private boolean strict;

  @Option(names = "--sslmode", defaultValue = "disable",
      description = "JDBC sslmode: disable|require|prefer|verify-full (default disable).")
  private String sslmode;

  @Option(names = "--connect-timeout", defaultValue = "10",
      description = "Connection timeout in seconds (default 10).")
  private int connectTimeout;

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
    if (pullMetadata) {
      if (sql != null || inputPath != null) {
        err.println("sql-mask: --pull-metadata cannot be combined with --sql/--input");
        return 2;
      }
      return executePullMetadata(out, err);
    }
    if (metadataPath == null) {
      err.println("sql-mask: --metadata is required for rewriting");
      return 2;
    }
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

  /**
   * Export mode: introspect one PostgreSQL database and write the metadata
   * YAML skeleton to {@code --output}. Mutual exclusion with --sql/--input was
   * already enforced in {@link #execute}; the file is written only after
   * introspection and the strict check succeed, so failure paths never
   * create or overwrite the output.
   */
  private int executePullMetadata(PrintStream out, PrintStream err) throws IOException {
    if (database == null || database.isBlank() || user == null || user.isBlank()) {
      err.println("sql-mask: --pull-metadata requires --database and --user");
      return 2;
    }
    if (outputPath == null) {
      err.println("sql-mask: --pull-metadata requires --output");
      return 2;
    }
    String resolvedPassword = password != null ? password : System.getenv("PGPASSWORD");
    if (resolvedPassword == null || resolvedPassword.isBlank()) {
      err.println("sql-mask: provide --password or set PGPASSWORD");
      return 2;
    }
    ConnectionSpec spec = new ConnectionSpec("postgresql", host, port, database, user, resolvedPassword,
        schemas == null ? List.of() : schemas, includeViews, strict, sslmode, connectTimeout);
    IntrospectionResult result;
    try {
      result = new PgMetadataIntrospector().introspect(spec);
    } catch (SqlMaskException e) {
      err.println("sql-mask: [" + e.getCode() + "] " + e.getMessage());
      return 1;
    }
    if (strict && result.warnings().stream().anyMatch(w -> w.endsWith("degraded to varchar"))) {
      err.println("sql-mask: [STRICT_DEGRADED] " + result.warnings().size()
          + " column(s) degraded; rerun without --strict to export anyway");
      result.warnings().forEach(err::println);
      return 1;
    }
    result.warnings().forEach(err::println);
    String yaml = new MetadataYamlGenerator().generate(result);
    Files.writeString(outputPath, yaml + "\n", StandardCharsets.UTF_8);
    int columnCount = result.tables().stream().mapToInt(t -> t.columns().size()).sum();
    out.println("introspected " + result.tables().size() + " tables / "
        + columnCount + " columns / " + result.warnings().size() + " warnings");
    return 0;
  }
}
