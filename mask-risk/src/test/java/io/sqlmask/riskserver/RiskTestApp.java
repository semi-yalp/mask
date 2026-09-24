package io.sqlmask.riskserver;

import io.sqlmask.auth.AuthConfig;
import io.sqlmask.auth.AuthTokenService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/** Test application for the risk domain as a library. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(value = "io.sqlmask.riskserver", excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ANNOTATION, classes = TestConfiguration.class))
public class RiskTestApp {

  @Bean
  AuthConfig authConfig() {
    return AuthConfig.fromEnv();
  }

  @Bean
  AuthTokenService authTokenService(AuthConfig config) {
    return config.tokenEnabled()
        ? new AuthTokenService(config.secret(), config.tokenTtl())
        : null;
  }

  /** Bearer gate: verified console tokens are accepted on the risk API.
   * Transparent (disabled registration) when no secret is configured —
   * the no-auth default. */
  @Bean
  FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter> bearerAuthFilter(
      AuthConfig config, ObjectProvider<AuthTokenService> tokens) {
    if (!config.tokenEnabled()) {
      FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter> off =
          new FilterRegistrationBean<>(
              new io.sqlmask.auth.BearerAuthFilter(null, java.util.List.of()));
      off.setEnabled(false);
      return off;
    }
    java.util.List<io.sqlmask.auth.AuthRule> rules = new io.sqlmask.auth.AuthRule.Builder()
        .prefix("/api/risk", io.sqlmask.auth.Role.USER)
        .build();
    FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter> registration =
        new FilterRegistrationBean<>(new io.sqlmask.auth.BearerAuthFilter(
            tokens.getObject(), rules));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
