package io.masklite;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Classloader 加载方式验证：宿主进程类路径上没有任何 calcite / io.masklite
 * 类时，仅凭自包含 shaded jar（URLClassLoader，不委托应用类路径）即可反射
 * 加载并执行改写。
 */
class ClassloaderIT {

  private static Path shadedJar() {
    Path jar = Path.of("target", "mask-lite-0.1.0-SNAPSHOT.jar");
    assertTrue(Files.isRegularFile(jar),
        "shaded jar missing; run `mvn package` first: " + jar.toAbsolutePath());
    return jar;
  }

  @Test
  void loadsFromShadedJarViaUrlClassLoaderAndRewrites() throws Exception {
    URL jarUrl = shadedJar().toUri().toURL();
    // parent = platform loader only: everything (io.masklite, calcite,
    // snakeyaml, generated parser) must come from the shaded jar itself
    try (URLClassLoader loader = new URLClassLoader(new URL[] {jarUrl},
        ClassLoader.getPlatformClassLoader())) {
      Class<?> maskLite = Class.forName("io.masklite.MaskLite", true, loader);
      assertEquals(loader, maskLite.getClassLoader(),
          "MaskLite must be loaded by the URLClassLoader, not the app classpath");
      // dependency isolation: calcite comes from the shaded jar too
      assertEquals(loader, Class.forName("org.apache.calcite.sql.SqlSelect", true, loader)
          .getClassLoader());

      Method fromYaml = maskLite.getMethod("fromYaml", String.class);
      Method rewrite = maskLite.getMethod("rewrite", String.class);
      String config = Files.readString(
          Path.of(ClassloaderIT.class.getResource("/tpcds/configs/tpcds-both.yaml").toURI()));

      Object mask = fromYaml.invoke(null, config);
      assertNotNull(mask);
      String rewritten = (String) rewrite.invoke(mask,
          "SELECT c_customer_id, c_email_address, c_last_name FROM customer LIMIT 3");
      assertTrue(rewritten.contains("mask_hash("), "c_customer_id should be hash-masked:\n" + rewritten);
      assertTrue(rewritten.contains("mask_email("), "c_email_address should be email-masked:\n" + rewritten);
      assertTrue(rewritten.contains("mask_name("), "c_last_name should be name-masked:\n" + rewritten);
    }
  }
}
