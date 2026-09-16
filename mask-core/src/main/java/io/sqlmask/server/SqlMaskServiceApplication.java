package io.sqlmask.server;

import io.sqlmask.cli.SqlMaskApplication;
import io.sqlmask.introspect.PgMetadataIntrospector;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.PolicyValidator;
import io.sqlmask.policyserver.store.InMemoryPolicyStore;
import io.sqlmask.policyserver.store.PolicyStore;
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

  @Bean
  PolicyStore policyStore() {
    return new InMemoryPolicyStore();
  }

  @Bean
  PolicyValidator policyValidator() {
    return new PolicyValidator();
  }

  @Bean
  PolicyService policyService(PolicyStore store, PolicyValidator validator) {
    return new PolicyService(store, validator);
  }

  @Bean
  MetadataStructureFetcher metadataStructureFetcher() {
    return new MetadataStructureFetcher.HttpMetadataStructureFetcher();
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<PolicyApiKeyFilter> policyApiKeyFilter() {
    org.springframework.boot.web.servlet.FilterRegistrationBean<PolicyApiKeyFilter> registration =
        new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new PolicyApiKeyFilter(
            System.getenv("SQLMASK_ADMIN_API_KEY"), System.getenv("SQLMASK_DATA_API_KEY")));
    registration.addUrlPatterns("/api/instances/*", "/api/effective/*");
    registration.setOrder(1);
    return registration;
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<InstanceRewriteApiKeyFilter>
  instanceRewriteApiKeyFilter() {
    org.springframework.boot.web.servlet.FilterRegistrationBean<InstanceRewriteApiKeyFilter> registration =
        new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
            new InstanceRewriteApiKeyFilter(System.getenv("SQLMASK_REWRITE_API_KEY")));
    registration.addUrlPatterns("/api/rewrite/instances/*");
    registration.setOrder(2);
    return registration;
  }
}
