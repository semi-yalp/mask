package io.sqlmask.buildtools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;

/**
 * Merges {@code META-INF/spring.factories} across jars with a per-key union of
 * the comma-separated values.
 *
 * <p>spring.factories is a properties file whose values are comma-separated
 * class lists, and several framework jars each declare the same key
 * (e.g. {@code org.springframework.boot.env.EnvironmentPostProcessor} is
 * declared independently by spring-boot, spring-boot-autoconfigure and
 * spring-boot-actuator-autoconfigure). Concatenating the files
 * (AppendingTransformer) yields duplicate keys, and java.util.Properties then
 * silently keeps only the last value — dropping entries such as
 * {@code ConfigDataEnvironmentPostProcessor}, which kills application.yml
 * loading. Shade's built-in PropertiesTransformer keeps a single value per key
 * too, just with configurable ordering. Only a per-key union preserves every
 * jar's entries, which is what this transformer does: values are split on
 * commas, trimmed, deduplicated, and re-joined in first-seen order.
 */
public class SpringFactoriesTransformer implements ResourceTransformer {

  private static final String FACTORIES_RESOURCE = "META-INF/spring.factories";

  private final Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();
  private boolean seen;

  @Override
  public boolean canTransformResource(String resource) {
    return FACTORIES_RESOURCE.equals(resource);
  }

  @Override
  public void processResource(String resource, InputStream is, List<Relocator> relocators)
      throws IOException {
    seen = true;
    Properties props = new Properties();
    props.load(is);
    for (String key : props.stringPropertyNames()) {
      String value = props.getProperty(key);
      if (value == null || value.isBlank()) {
        continue;
      }
      LinkedHashSet<String> values = merged.computeIfAbsent(key, k -> new LinkedHashSet<>());
      for (String part : value.split(",")) {
        String v = part.trim();
        if (!v.isEmpty()) {
          values.add(v);
        }
      }
    }
  }

  @Override
  public boolean hasTransformedResource() {
    return seen;
  }

  @Override
  public void modifyOutputStream(JarOutputStream jos) throws IOException {
    jos.putNextEntry(new JarEntry(FACTORIES_RESOURCE));
    OutputStream out = jos;
    for (Map.Entry<String, LinkedHashSet<String>> entry : merged.entrySet()) {
      out.write((entry.getKey() + "=" + String.join(",", entry.getValue()) + "\n")
          .getBytes(StandardCharsets.UTF_8));
    }
    jos.closeEntry();
  }
}
