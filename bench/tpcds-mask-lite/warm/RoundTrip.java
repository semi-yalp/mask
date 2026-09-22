import io.masklite.dialect.Dialect;
import io.masklite.dialect.DialectRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Round-trip check: every rewritten output (one statement per file, the
 * bench's {@code qNN.out} files) must be re-parseable by the same production
 * PostgreSQL parser mask-lite itself uses. Failures here mean the rewriter
 * emitted SQL its own dialect cannot read — a hard bug, not a policy choice.
 *
 * <pre>
 * java -cp mask-lite.jar;classes RoundTrip results/raw/lite/mask
 * </pre>
 *
 * Output per file: {@code qid OK} or {@code qid FAIL <message>}; exit code is
 * 0 only when everything parses.
 */
public final class RoundTrip {

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("usage: RoundTrip <dir-of-.out-files>");
      System.exit(2);
    }
    Dialect dialect = DialectRegistry.create(DialectRegistry.DEFAULT);
    List<Path> files;
    try (var stream = Files.list(Path.of(args[0]))) {
      files = stream.filter(p -> p.getFileName().toString().endsWith(".out")).sorted().toList();
    }
    int failures = 0;
    for (Path file : files) {
      String qid = file.getFileName().toString().replace(".out", "");
      Path codeFile = file.resolveSibling(qid + ".code");
      if (Files.exists(codeFile) && !"0".equals(Files.readString(codeFile).trim())) {
        // rewrite already failed in the cold matrix — nothing was emitted
        System.out.println(qid + "\tSKIPPED");
        continue;
      }
      String sql = Files.readString(file, StandardCharsets.UTF_8).trim();
      if (sql.endsWith(";")) {
        // the CLI join emits one trailing ';' per statement; the parser
        // accepts a single bare statement
        sql = sql.substring(0, sql.length() - 1).trim();
      }
      if (sql.isEmpty()) {
        System.out.println(qid + "\tFAIL\tempty output");
        failures++;
        continue;
      }
      try {
        dialect.parse(sql, 1);
        System.out.println(qid + "\tOK");
      } catch (Exception e) {
        String message = String.valueOf(e.getMessage()).replace('\n', ' ');
        message = message.length() > 160 ? message.substring(0, 160) : message;
        System.out.println(qid + "\tFAIL\t" + message);
        failures++;
      }
    }
    System.out.println("#total\t" + files.size() + "\tfailures\t" + failures);
    System.exit(failures == 0 ? 0 : 1);
  }
}
