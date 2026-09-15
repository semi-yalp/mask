package io.masklite;

import io.masklite.error.SqlMaskException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;


/**
 * Command-line entry point.
 *
 * <pre>
 * java -jar mask-lite.jar --metadata metadata.yaml --sql 'SELECT ...'
 * java -jar mask-lite.jar --metadata metadata.yaml &lt; input.sql
 * </pre>
 *
 * Rewritten SQL goes to stdout; diagnostics go to stderr with exit code 1.
 */
public final class Main {

  public static void main(String[] args) {
    try {
      String output = run(args);
      System.out.print(output);
      if (!output.isEmpty()) {
        System.out.println();
      }
      System.out.flush();
    } catch (SqlMaskException e) {
      System.err.println("mask-lite: " + e.getCode() + ": " + e.getMessage());
      System.exit(1);
    } catch (CliUsageException e) {
      System.err.println(e.getMessage());
      System.err.println("usage: java -jar mask-lite.jar --metadata <metadata.yaml> [--sql '<sql>']"
          + "   (reads stdin when --sql is absent)");
      System.exit(2);
    } catch (IOException e) {
      System.err.println("mask-lite: IO_ERROR: " + e.getMessage());
      System.exit(1);
    }
  }

  static String run(String[] args) throws IOException {
    Path metadata = null;
    String sql = null;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg) {
        case "--metadata" -> {
          if (++i >= args.length) {
            throw new CliUsageException("--metadata requires a file path");
          }
          metadata = Path.of(args[i]);
        }
        case "--sql" -> {
          if (++i >= args.length) {
            throw new CliUsageException("--sql requires an argument");
          }
          sql = args[i];
        }
        default -> throw new CliUsageException("unknown argument '" + arg + "'");
      }
    }
    if (metadata == null) {
      throw new CliUsageException("--metadata is required");
    }
    if (sql == null) {
      sql = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    }
    return MaskLite.fromYamlFile(metadata).rewrite(sql);
  }

  private static final class CliUsageException extends RuntimeException {
    CliUsageException(String message) {
      super(message);
    }
  }

  private Main() {
  }
}
