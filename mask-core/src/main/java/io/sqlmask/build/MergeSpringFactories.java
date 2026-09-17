package io.sqlmask.build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Normalizes META-INF/spring.factories inside the shaded fat jar. Runs via
 * exec-maven-plugin right after the shade plugin in the package phase.
 *
 * AppendingTransformer concatenates every jar's copy of the resource into ONE
 * properties file with duplicated keys; java.util.Properties resolves duplicated
 * keys last-wins, so every factory declared by all but the last jar silently
 * disappears (ConfigDataEnvironmentPostProcessor being the famous casualty:
 * application.yml silently stops loading). This step rewrites the entry with a
 * per-key union — first-seen order, duplicates dropped — the same semantics
 * SpringFactoriesLoader applies across a normal (non-shaded) classpath.
 */
public final class MergeSpringFactories {

  private static final String RESOURCE = "META-INF/spring.factories";

  public static void main(String[] args) throws IOException {
    Path jar = Path.of(args[0]);
    Path tmp = Files.createTempFile(jar.getParent(), jar.getFileName().toString(), ".merge");
    try {
      String merged = null;
      try (ZipFile zip = new ZipFile(jar.toFile())) {
        ZipEntry entry = zip.getEntry(RESOURCE);
        if (entry != null) {
          merged = union(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
        }
      }
      if (merged == null) {
        return;
      }
      try (ZipFile zip = new ZipFile(jar.toFile());
           ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        byte[] buffer = new byte[64 * 1024];
        while (entries.hasMoreElements()) {
          ZipEntry src = entries.nextElement();
          ZipEntry dst = new ZipEntry(src.getName());
          dst.setTime(src.getTime());
          out.putNextEntry(dst);
          if (src.getName().equals(RESOURCE)) {
            out.write(merged.getBytes(StandardCharsets.UTF_8));
          } else {
            try (InputStream in = zip.getInputStream(src)) {
              int n;
              while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
              }
            }
          }
          out.closeEntry();
        }
      }
      Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private static String union(String content) {
    // The files use properties line-continuations: "key=value1,\\" + newline +
    // "  value2". Join continued lines first (a line belongs to the next one when it
    // ends with an odd number of backslashes), then split key/value.
    List<String> logical = new ArrayList<>();
    StringBuilder current = null;
    for (String raw : content.split("\r?\n", -1)) {
      String line = raw.strip();
      boolean continued = endsWithLineContinuation(line);
      if (continued) {
        line = line.substring(0, line.length() - 1);
      }
      if (current == null) {
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
          continue;
        }
        current = new StringBuilder(line);
      } else {
        current.append(line);
      }
      if (!continued) {
        logical.add(current.toString());
        current = null;
      }
    }
    if (current != null) {
      logical.add(current.toString());
    }
    Map<String, Set<String>> factories = new LinkedHashMap<>();
    for (String entry : logical) {
      int eq = entry.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String key = entry.substring(0, eq).strip();
      String value = entry.substring(eq + 1).strip();
      if (value.isEmpty()) {
        continue;
      }
      Set<String> merged = factories.computeIfAbsent(key, k -> new LinkedHashSet<>());
      for (String impl : value.split(",")) {
        String name = impl.strip();
        if (!name.isEmpty()) {
          merged.add(name);
        }
      }
    }
    List<String> keys = new ArrayList<>(factories.keySet());
    Collections.sort(keys);
    StringBuilder sb = new StringBuilder();
    for (String key : keys) {
      sb.append(key).append('=').append(String.join(",", factories.get(key))).append('\n');
    }
    return sb.toString();
  }

  private static boolean endsWithLineContinuation(String line) {
    int backslashes = 0;
    for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
      backslashes++;
    }
    return backslashes % 2 == 1;
  }
}
