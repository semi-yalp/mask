import io.masklite.MaskLite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * In-JVM warm benchmark driver for mask-lite: loads the three bench
 * configurations (mask / rowfilter / both) into ONE hot JVM and interleaves
 * measurement rounds across the modes, so per-mode numbers share the same JIT
 * state and GC schedule and are directly comparable — no JVM startup, no
 * process spawn (the cold side of that is what run-case.sh measures end to
 * end).
 *
 * <pre>
 * java -cp mask-lite.jar;classes WarmBench config corpus/queries 20 3 outdir
 * </pre>
 *
 * Output: {@code outdir/warm-&lt;mode&gt;.tsv}, one line per query
 * {@code qid ok errcode meanMs p50Ms minMs maxMs iterations}, plus a leading
 * {@code #} header comment. A failed rewrite records its own wall time and
 * the error code — the cost of failing is part of the benchmark.
 */
public final class WarmBench {

  private static final String[] MODES = {"mask", "rowfilter", "both"};

  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      System.err.println("usage: WarmBench <config-dir> <queries-dir> <iterations> [warmup] [outdir]");
      System.exit(2);
    }
    Path configDir = Path.of(args[0]);
    Path queriesDir = Path.of(args[1]);
    int iterations = Integer.parseInt(args[2]);
    int warmup = args.length > 3 ? Integer.parseInt(args[3]) : 3;
    Path outDir = Path.of(args.length > 4 ? args[4] : "results");

    List<Path> queryFiles;
    try (Stream<Path> stream = Files.list(queriesDir)) {
      queryFiles = stream.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
    }
    List<Query> queries = new ArrayList<>();
    for (Path file : queryFiles) {
      queries.add(new Query(
          file.getFileName().toString().replace(".sql", ""),
          Files.readString(file, StandardCharsets.UTF_8)));
    }

    Map<String, MaskLite> masks = new LinkedHashMap<>();
    for (String mode : MODES) {
      masks.put(mode, MaskLite.fromYamlFile(configDir.resolve("tpcds-" + mode + ".yaml")));
    }

    for (int i = 0; i < warmup; i++) {
      for (MaskLite mask : masks.values()) {
        for (Query query : queries) {
          try {
            mask.rewrite(query.sql);
          } catch (RuntimeException e) {
            // failing queries are data, not driver crashes: the measured
            // rounds below record their error code and cost
          }
        }
      }
    }

    Map<String, Map<String, List<Long>>> samples = new LinkedHashMap<>();
    Map<String, Map<String, String>> errcodes = new LinkedHashMap<>();
    for (String mode : MODES) {
      samples.put(mode, new LinkedHashMap<>());
      errcodes.put(mode, new LinkedHashMap<>());
    }
    for (int i = 0; i < iterations; i++) {
      for (Map.Entry<String, MaskLite> entry : masks.entrySet()) {
        String mode = entry.getKey();
        MaskLite mask = entry.getValue();
        for (Query query : queries) {
          long start = System.nanoTime();
          String errcode = "";
          try {
            mask.rewrite(query.sql);
          } catch (RuntimeException e) {
            errcode = e instanceof io.masklite.error.SqlMaskException sqlMask
                ? sqlMask.getCode().name()
                : "INTERNAL_" + e.getClass().getSimpleName();
          }
          long elapsed = System.nanoTime() - start;
          samples.get(mode).computeIfAbsent(query.id, k -> new ArrayList<>()).add(elapsed);
          if (!errcode.isEmpty()) {
            errcodes.get(mode).put(query.id, errcode);
          }
        }
      }
    }

    for (Map.Entry<String, Map<String, List<Long>>> entry : samples.entrySet()) {
      Path report = outDir.resolve("warm-" + entry.getKey() + ".tsv");
      StringBuilder out = new StringBuilder("#qid\tok\terrcode\tmeanMs\tp50Ms\tminMs\tmaxMs\titerations\n");
      for (Map.Entry<String, List<Long>> q : entry.getValue().entrySet()) {
        List<Long> nanos = new ArrayList<>(q.getValue());
        Collections.sort(nanos);
        double mean = nanos.stream().mapToLong(Long::longValue).average().orElse(0);
        double p50 = nanos.get(nanos.size() / 2);
        double min = nanos.get(0);
        double max = nanos.get(nanos.size() - 1);
        String errcode = errcodes.get(entry.getKey()).getOrDefault(q.getKey(), "");
        out.append(q.getKey()).append('\t')
            .append(errcode.isEmpty()).append('\t')
            .append(errcode).append('\t')
            .append(String.format("%.3f", mean / 1e6)).append('\t')
            .append(String.format("%.3f", p50 / 1e6)).append('\t')
            .append(String.format("%.3f", min / 1e6)).append('\t')
            .append(String.format("%.3f", max / 1e6)).append('\t')
            .append(iterations).append('\n');
      }
      Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
      System.out.println("wrote " + report);
    }
  }

  private record Query(String id, String sql) {
  }
}
