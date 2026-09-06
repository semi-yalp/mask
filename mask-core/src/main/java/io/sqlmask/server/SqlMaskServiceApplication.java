package io.sqlmask.server;

import io.sqlmask.cli.SqlMaskApplication;
import io.sqlmask.introspect.PgMetadataIntrospector;
import io.sqlmask.rewrite.RewriteEngine;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Spring Boot entry point.
 *
 * <p>The same executable jar serves both modes:
 * <ul>
 * <li>no CLI arguments ({@code java -jar sql-mask.jar}) starts the web
 * service with the bundled UI;</li>
 * <li>CLI arguments ({@code java -jar sql-mask.jar --metadata ... --sql ...},
 * or {@code --pull-metadata ...} for metadata export mode)
 * delegate to {@link SqlMaskApplication} for one-shot command line use.</li>
 * </ul>
 */
/**
 * Spring Boot entry point.
 *
 * <p>{@code DataSourceAutoConfiguration} is excluded: the rewrite engine is
 * DB-free by design (the only database access is the read-only metadata
 * pull), and the JDBC policy store wires its own datasource when deployed —
 * an auto-configured pool with no URL would only fail context startup.
 */
@SpringBootApplication(scanBasePackages = "io.sqlmask",
    exclude = org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
public class SqlMaskServiceApplication {

  private static final List<String> CLI_OPTIONS = List.of(
      "--metadata", "--policies", "--groups", "--sql", "--input", "--output", "--dialect",
      "--help", "--version", "--pull-metadata");

  public static void main(String[] args) {
    if (looksLikeCliInvocation(args)) {
      System.exit(new SqlMaskApplication().run(args, System.in, System.out, System.err));
    } else {
      SpringApplication.run(SqlMaskServiceApplication.class, args);
    }
  }

  static boolean looksLikeCliInvocation(String[] args) {
    for (String arg : args) {
      if (CLI_OPTIONS.contains(arg)) {
        return true;
      }
    }
    return false;
  }

  @Bean
  RewriteEngine rewriteEngine() {
    return new RewriteEngine();
  }

  @Bean
  PgMetadataIntrospector pgMetadataIntrospector() {
    return new PgMetadataIntrospector();
  }
}
