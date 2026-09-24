package io.sqlmask.query;

import io.sqlmask.query.config.QueryProperties;
import io.sqlmask.query.config.UpstreamProperties;
import io.sqlmask.query.metadata.MetadataServiceClient;
import io.sqlmask.query.rewrite.RewriteServiceClient;
import io.sqlmask.query.service.QueryService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class QueryServerApplication {
  public static void main(String[] args) {
    SpringApplication.run(QueryServerApplication.class, args);
  }

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
      // timeout is the connect backstop for every engine. This service is a
      // stateless single-purpose process, so a process-wide default is safe.
      int loginTimeout = c.connectTimeoutSeconds() <= 0 ? 10 : c.connectTimeoutSeconds();
      java.sql.DriverManager.setLoginTimeout(loginTimeout);
      return java.sql.DriverManager.getConnection(
          engine.jdbcUrl(c, props.timeoutSeconds()), c.dbUser(), password);
    };
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.common.web.ApiKeyFilter>
      queryApiKeyFilter() {
    // fail-closed: an unconfigured key rejects everything (SQLMASK_QUERY_API_KEY)
    var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
        io.sqlmask.common.web.ApiKeyFilter.failClosed(System.getenv("SQLMASK_QUERY_API_KEY")));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(1);
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
   * Bearer gate ahead of the API-key filter: a logged-in console user may run
   * controlled queries, and the policy subject comes from the token instead
   * of the request body's user/groups.
   */
  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter>
  bearerAuthFilter(io.sqlmask.auth.AuthConfig config,
      org.springframework.beans.factory.ObjectProvider<io.sqlmask.auth.AuthTokenService> tokens) {
    if (!config.tokenEnabled()) {
      // disabled registration (not a null @Bean): a NullBean here breaks the
      // MockMvc builder's FilterRegistrationBean collection in default contexts
      org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter> off =
          new org.springframework.boot.web.servlet.FilterRegistrationBean<>();
      off.setEnabled(false);
      return off; // no MASK_AUTH_SECRET → behaviour identical to the pre-LDAP build
    }
    java.util.List<io.sqlmask.auth.AuthRule> rules = new io.sqlmask.auth.AuthRule.Builder()
        .prefix("/api/v1", io.sqlmask.auth.Role.USER)
        .build();
    org.springframework.boot.web.servlet.FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter>
        registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
            new io.sqlmask.auth.BearerAuthFilter(
                tokens.getObject(), rules));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(0);
    return registration;
  }
}
