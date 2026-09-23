package io.sqlmask.metaserver.config;

import io.sqlmask.common.web.ApiKeyFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetadataServerConfig {

  /** Fail-closed gate: an unconfigured server key rejects every request. */
  @Bean
  public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(
      @Value("${metadata.api-key:}") String configuredKey) {
    FilterRegistrationBean<ApiKeyFilter> registration =
        new FilterRegistrationBean<>(ApiKeyFilter.failClosed(configuredKey));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(1);
    return registration;
  }

  @Bean
  public io.sqlmask.metaserver.service.IntrospectorFactory introspectorFactory() {
    return io.sqlmask.introspect.MetadataIntrospectors::byEngine;
  }
}
