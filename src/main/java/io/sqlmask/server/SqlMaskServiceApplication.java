package io.sqlmask.server;

import io.sqlmask.cli.SqlMaskApplication;
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
 * <li>CLI arguments ({@code java -jar sql-mask.jar --metadata ... --sql ...})
 * delegate to {@link SqlMaskApplication} for one-shot command line use.</li>
 * </ul>
 */
@SpringBootApplication
public class SqlMaskServiceApplication {

  private static final List<String> CLI_OPTIONS = List.of(
      "--metadata", "--sql", "--input", "--output", "--dialect", "--help", "--version");

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
}
