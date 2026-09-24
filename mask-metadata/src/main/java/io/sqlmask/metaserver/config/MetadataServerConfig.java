package io.sqlmask.metaserver.config;

import io.sqlmask.common.web.ApiKeyFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetadataServerConfig {

  /** Per-plane key gate: /api/metadata/** uses the data key, everything
   *  else the admin key; the legacy single key (METADATA_API_KEY) keeps
   *  existing single-key deployments working on both planes. */
  @Bean
<<<<<<< HEAD
  public FilterRegistrationBean<ApiKeyFilter> adminPlaneFilter(
      @Value("${metadata.api-key:}") String legacyKey,
      @Value("${metadata.admin-api-key:}") String adminKey) {
    // per-plane keys win; the legacy single key (METADATA_API_KEY) keeps
    // existing single-key deployments working on both planes
    String effective = adminKey == null || adminKey.isBlank() ? legacyKey : adminKey;
    FilterRegistrationBean<ApiKeyFilter> registration =
        new FilterRegistrationBean<>(ApiKeyFilter.failClosed(effective));
    registration.addUrlPatterns("/api/instances/*");
    registration.setOrder(1);
    return registration;
  }

  /** Data plane (/api/metadata/**) has its own key, also fail-closed. */
  @Bean
  public FilterRegistrationBean<ApiKeyFilter> dataPlaneFilter(
      @Value("${metadata.api-key:}") String legacyKey,
      @Value("${metadata.data-api-key:}") String dataKey) {
    String effective = dataKey == null || dataKey.isBlank() ? legacyKey : dataKey;
    FilterRegistrationBean<ApiKeyFilter> registration =
        new FilterRegistrationBean<>(ApiKeyFilter.failClosed(effective));
    registration.addUrlPatterns("/api/metadata/*");
=======
  public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(
      @Value("${metadata.api-key:}") String legacyKey,
      @Value("${metadata.admin-api-key:}") String adminKey,
      @Value("${metadata.data-api-key:}") String dataKey) {
    String admin = adminKey == null || adminKey.isBlank() ? legacyKey : adminKey;
    String data = dataKey == null || dataKey.isBlank() ? legacyKey : dataKey;
    FilterRegistrationBean<ApiKeyFilter> registration =
        new FilterRegistrationBean<>(ApiKeyFilter.surfaces(
            new ApiKeyFilter.Surface("/api/metadata", data),
            new ApiKeyFilter.Surface("/", admin)));
    registration.addUrlPatterns("/api/*");
>>>>>>> origin/main
    registration.setOrder(1);
    return registration;
  }

  // ---- console-user (LDAP) authentication & authorization ----

  @Bean
  public io.sqlmask.auth.AuthConfig authConfig() {
    return io.sqlmask.auth.AuthConfig.fromEnv();
  }

  /** Present only when MASK_AUTH_SECRET is strong enough; null ⇒ feature off. */
  @Bean
  public io.sqlmask.auth.AuthTokenService authTokenService(io.sqlmask.auth.AuthConfig config) {
    return config.tokenEnabled()
        ? new io.sqlmask.auth.AuthTokenService(config.secret(), config.tokenTtl())
        : null;
  }

  /**
   * Bearer gate ahead of the API-key filter: logged-in console users may read
   * the metadata surfaces, admins may write (instance CRUD, collection).
   */
  @Bean
  public FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter> bearerAuthFilter(
      io.sqlmask.auth.AuthConfig config,
      org.springframework.beans.factory.ObjectProvider<io.sqlmask.auth.AuthTokenService> tokens) {
    if (!config.tokenEnabled()) {
      // disabled registration (not a null @Bean): a NullBean here breaks the
      // MockMvc builder's FilterRegistrationBean collection in default contexts
      FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter> off = new FilterRegistrationBean<>();
      off.setEnabled(false);
      return off; // no MASK_AUTH_SECRET → behaviour identical to the pre-LDAP build
    }
    java.util.List<io.sqlmask.auth.AuthRule> rules = new io.sqlmask.auth.AuthRule.Builder()
        .readOnly("/api/instances", io.sqlmask.auth.Role.USER, io.sqlmask.auth.Role.ADMIN)
        .prefix("/api/metadata", io.sqlmask.auth.Role.USER)
        .build();
    FilterRegistrationBean<io.sqlmask.auth.BearerAuthFilter> registration =
        new FilterRegistrationBean<>(new io.sqlmask.auth.BearerAuthFilter(
            tokens.getObject(), rules));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(0);
    return registration;
  }

  @Bean
  public io.sqlmask.metaserver.service.IntrospectorFactory introspectorFactory() {
    return io.sqlmask.introspect.MetadataIntrospectors::byEngine;
  }
}
