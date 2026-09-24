package io.sqlmask.server;

import io.sqlmask.cli.SqlMaskApplication;
import io.sqlmask.introspect.PgMetadataIntrospector;
import io.sqlmask.rewrite.RewriteEngine;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

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
@EnableScheduling
public class SqlMaskServiceApplication {

  private static final List<String> CLI_OPTIONS = List.of(
      "--metadata", "--policies", "--groups", "--sql", "--input", "--output", "--dialect",
      "--help", "--version", "--pull-metadata", "--instance", "--policy-service");

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

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.common.web.ApiKeyFilter>
      auditApiKeyFilter() {
    String adminKey = System.getenv("SQLMASK_ADMIN_API_KEY");
    if (adminKey == null || adminKey.isBlank()) {
      org.slf4j.LoggerFactory.getLogger(SqlMaskServiceApplication.class).warn(
          "SQLMASK_ADMIN_API_KEY is not configured: the audit query surface (/api/audit/**) "
              + "is OPEN to anyone who can reach this service");
    }
    org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.common.web.ApiKeyFilter> registration =
        new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
            io.sqlmask.common.web.ApiKeyFilter.failOpen(adminKey));
    registration.addUrlPatterns("/api/audit/*");
    registration.setOrder(1);
    return registration;
  }

  @Bean
  io.sqlmask.audit.AuditAdminHelper auditAdminHelper(io.sqlmask.audit.AuditRecorder recorder) {
    return new io.sqlmask.audit.AuditAdminHelper(recorder,
        "sql-mask",
        e -> e instanceof io.sqlmask.error.SqlMaskException sme
            ? sme.getCode().name()
            : e.getClass().getSimpleName());
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.common.web.ApiKeyFilter>
      instanceRewriteApiKeyFilter() {
    String rewriteKey = System.getenv("SQLMASK_REWRITE_API_KEY");
    if (rewriteKey == null || rewriteKey.isBlank()) {
      org.slf4j.LoggerFactory.getLogger(SqlMaskServiceApplication.class).warn(
          "SQLMASK_REWRITE_API_KEY is not configured: the instance rewrite surface "
              + "(/api/rewrite/instances/**) is OPEN to anyone who can reach this service");
    }
    org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.common.web.ApiKeyFilter> registration =
        new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
            io.sqlmask.common.web.ApiKeyFilter.failOpen(rewriteKey));
    registration.addUrlPatterns("/api/rewrite/instances/*");
    registration.setOrder(2);
    return registration;
  }

  // ---- console-user (LDAP) authentication & authorization ----

  @Bean
  io.sqlmask.auth.AuthConfig authConfig() {
    return io.sqlmask.auth.AuthConfig.fromEnv();
  }

  /** Present only when MASK_AUTH_SECRET is strong enough; null ⇒ feature off. */
  @Bean
  io.sqlmask.auth.AuthTokenService authTokenService(io.sqlmask.auth.AuthConfig config) {
    return config.tokenEnabled()
        ? new io.sqlmask.auth.AuthTokenService(config.secret(), config.tokenTtl())
        : null;
  }

  /**
   * Bearer gate ahead of the API-key filters. Console rules here: audit
   * search for auditors and above, both rewrite surfaces for any logged-in
   * user (the instance rewrite's policy subject then comes from the token).
   */
  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter>
  bearerAuthFilter(io.sqlmask.auth.AuthConfig config,
      org.springframework.beans.factory.ObjectProvider<io.sqlmask.auth.AuthTokenService> tokens) {
    if (!config.tokenEnabled()) {
      // disabled registration (not a null @Bean): a NullBean here breaks the
      // MockMvc builder's FilterRegistrationBean collection in default contexts
      org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter> off =
          // a null-filter registration crashes real Tomcat (addFilter(getFilter())
          // runs before setEnabled applies) - register a transparent instance instead
          new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
              new io.sqlmask.auth.BearerAuthFilter(null, java.util.List.of()));
      off.setEnabled(false);
      return off; // no MASK_AUTH_SECRET → behaviour identical to the pre-LDAP build
    }
    java.util.List<io.sqlmask.auth.AuthRule> rules = new io.sqlmask.auth.AuthRule.Builder()
        .prefix("/api/audit", io.sqlmask.auth.Role.AUDITOR)
        .prefix("/api/rewrite", io.sqlmask.auth.Role.USER)
        .build();
    org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter>
        registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
            new io.sqlmask.auth.BearerAuthFilter(
                tokens.getObject(), rules));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(0);
    org.slf4j.LoggerFactory.getLogger(SqlMaskServiceApplication.class).info(
        "MASK_AUTH_SECRET is configured: LDAP bearer-token auth is armed "
            + "(rules: /api/audit=AUDITOR, /api/rewrite*=USER)");
    return registration;
  }
}
