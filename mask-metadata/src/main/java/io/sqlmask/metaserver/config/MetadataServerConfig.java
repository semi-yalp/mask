package io.sqlmask.metaserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetadataServerConfig {

  @Bean
  public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(
      @Value("${metadata.api-key:}") String legacyKey,
      @Value("${metadata.admin-api-key:}") String adminKey,
      @Value("${metadata.data-api-key:}") String dataKey) {
    // per-plane keys win; the legacy single key (METADATA_API_KEY) keeps
    // existing single-key deployments working on both planes
    ApiKeyFilter filter = new ApiKeyFilter(
        adminKey == null || adminKey.isBlank() ? legacyKey : adminKey,
        dataKey == null || dataKey.isBlank() ? legacyKey : dataKey);
    FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>(filter);
    registration.addUrlPatterns("/api/*");
    registration.setOrder(1);
    return registration;
  }

  @Bean
  public io.sqlmask.metaserver.service.IntrospectorFactory introspectorFactory() {
    return io.sqlmask.introspect.MetadataIntrospectors::byEngine;
  }
}
