package io.sqlmask.query;

import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.config.UpstreamProperties;
import io.sqlmask.query.metadata.MetadataServiceClient;
import io.sqlmask.query.rewrite.RewriteServiceClient;
import io.sqlmask.query.service.QueryService;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * Test application for the query gateway domain as a library: standalone
 * (HTTP-upstream) wiring identical to the retired standalone service, so the
 * module's endpoint and end-to-end tests run without the monolith. The
 * monolith registers its own in-process {@code InstanceDirectory} /
 * {@code QueryRewriter} beans instead (mask-server QueryGatewayConfig).
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
@EnableConfigurationProperties({QueryProperties.class, UpstreamProperties.class})
@ComponentScan(value = "io.sqlmask.query", excludeFilters = @ComponentScan.Filter(
    type = org.springframework.context.annotation.FilterType.ANNOTATION,
    classes = TestConfiguration.class))
public class QueryTestApp {

  @Bean
  MetadataServiceClient metadataServiceClient(UpstreamProperties props) {
    requireBaseUrl("upstream.metadata-base-url", props.metadataBaseUrl());
    return new MetadataServiceClient(props.metadataBaseUrl(), props.metadataApiKey());
  }

  @Bean
  RewriteServiceClient rewriteServiceClient(UpstreamProperties props) {
    requireBaseUrl("upstream.rewrite-base-url", props.rewriteBaseUrl());
    return new RewriteServiceClient(props.rewriteBaseUrl(), props.rewriteApiKey());
  }

  /** Blank upstream locations otherwise surface as an obscure URI parse error
   * on the first query; fail the application start instead. */
  private static void requireBaseUrl(String configKey, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(configKey + " must be configured");
    }
  }

  /** Backs InstanceDirectory with the metadata client (same fetch contract). */
  @Bean
  QueryService.InstanceDirectory queryInstanceDirectory(MetadataServiceClient metadata) {
    return metadata::fetch;
  }

  @Bean
  QueryService.ConnectionFactory queryConnectionFactory(QueryProperties props) {
    return (engine, c, password) -> {
      // Trino's driver has no connect-timeout URL property; the global login
      // timeout is the connect backstop for every engine.
      int loginTimeout = c.connectTimeoutSeconds() <= 0 ? 10 : c.connectTimeoutSeconds();
      java.sql.DriverManager.setLoginTimeout(loginTimeout);
      return java.sql.DriverManager.getConnection(
          engine.jdbcUrl(c, props.timeoutSeconds()), c.dbUser(), password);
    };
  }

  // ---- console-user (bearer) gate: verified identities are accepted on the
  // query plane and win over caller-asserted subjects; transparent when no
  // secret is configured (the no-auth default) ----

  @Bean
  io.sqlmask.auth.AuthConfig authConfig() {
    return io.sqlmask.auth.AuthConfig.fromEnv();
  }

  @Bean
  io.sqlmask.auth.AuthTokenService authTokenService(io.sqlmask.auth.AuthConfig config) {
    return config.tokenEnabled()
        ? new io.sqlmask.auth.AuthTokenService(config.secret(), config.tokenTtl())
        : null;
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter>
  bearerAuthFilter(io.sqlmask.auth.AuthConfig config,
                   org.springframework.beans.factory.ObjectProvider<io.sqlmask.auth.AuthTokenService> tokens) {
    if (!config.tokenEnabled()) {
      org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter> off =
          new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
              new io.sqlmask.auth.BearerAuthFilter(null, java.util.List.of()));
      off.setEnabled(false);
      return off;
    }
    java.util.List<io.sqlmask.auth.AuthRule> rules = new io.sqlmask.auth.AuthRule.Builder()
        .prefix("/api/v1", io.sqlmask.auth.Role.USER)
        .build();
    org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter>
        registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
            new io.sqlmask.auth.BearerAuthFilter(tokens.getObject(), rules));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(0);
    return registration;
  }
}
