package io.sqlmask.metaserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetadataServerConfig {

  @Bean
  public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(
      @Value("${metadata.api-key:}") String configuredKey) {
    ApiKeyFilter filter = new ApiKeyFilter(configuredKey);
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
