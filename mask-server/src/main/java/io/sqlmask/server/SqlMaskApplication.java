package io.sqlmask.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;

/**
 * The sqlmask modular-monolith entry point: every domain (metadata, policy
 * admin, query gateway, risk, audit, auth) lives in this one process over the
 * pure mask-engine kernel, sharing one storage datasource (PostgreSQL by
 * configuration, H2 file fallback for zero-config local runs) and one port.
 *
 * <p>The same executable jar serves both modes:
 * <ul>
 * <li>no CLI arguments ({@code java -jar sqlmask-server.jar}) starts the web
 * service (with the bundled UI when built into the jar);</li>
 * <li>CLI arguments ({@code java -jar sqlmask-server.jar --metadata ... --sql ...},
 * or {@code --pull-metadata ...} for metadata export mode) delegate to
 * {@link SqlMaskApplication} for one-shot command line use.</li>
 * </ul>
 *
 * <p>Authentication is OFF by default (functionality first); see
 * {@code mask.auth.mode} for the simple/ldap opt-in modes.
 */
@SpringBootApplication(scanBasePackages = "io.sqlmask")
@ConfigurationPropertiesScan(basePackages = "io.sqlmask")
@EnableScheduling
public class SqlMaskApplication {

  private static final java.util.List<String> CLI_OPTIONS = java.util.List.of(
      "--metadata", "--policies", "--groups", "--sql", "--input", "--output", "--dialect",
      "--help", "--version", "--pull-metadata", "--instance", "--policy-service",
      "--engine", "--host", "--port", "--database", "--user", "--password", "--schema",
      "--include-views", "--strict", "--sslmode", "--connect-timeout");

  public static void main(String[] args) {
    if (looksLikeCliInvocation(args)) {
      System.exit(new io.sqlmask.cli.SqlMaskApplication().run(args, System.in, System.out, System.err));
    } else {
      SpringApplication app = new SpringApplication(SqlMaskApplication.class);
      // Lowest-precedence defaults: real configuration (env vars, command
      // line, application.yml) always wins. A zero-config start therefore
      // boots on a local H2 file instead of failing for want of a database.
      app.setDefaultProperties(Map.of(
          "spring.datasource.url", "jdbc:h2:file:./data/sqlmask;MODE=PostgreSQL;AUTO_SERVER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
          "spring.datasource.username", "sa",
          "spring.datasource.password", "",
          "spring.sql.init.mode", "always"));
      app.run(args);
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
}
