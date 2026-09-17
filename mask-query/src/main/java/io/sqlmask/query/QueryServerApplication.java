package io.sqlmask.query;

import io.sqlmask.query.config.UpstreamProperties;
import io.sqlmask.query.metadata.MetadataServiceClient;
import io.sqlmask.query.rewrite.RewriteServiceClient;
import io.sqlmask.query.service.QueryService;
import io.sqlmask.query.web.QueryApiKeyFilter;
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
  QueryService.ConnectionFactory queryConnectionFactory() {
    return (engine, c, password) -> java.sql.DriverManager.getConnection(
        engine.jdbcUrl(c), c.dbUser(), password);
  }

  @Bean
  org.springframework.boot.web.servlet.FilterRegistrationBean<QueryApiKeyFilter> queryApiKeyFilter() {
    var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
        new QueryApiKeyFilter(System.getenv("SQLMASK_QUERY_API_KEY")));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(1);
    return registration;
  }
}
