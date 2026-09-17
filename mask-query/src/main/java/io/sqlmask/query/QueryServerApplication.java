package io.sqlmask.query;

import io.sqlmask.query.config.UpstreamProperties;
import io.sqlmask.query.metadata.MetadataServiceClient;
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
    return new MetadataServiceClient(props.metadataBaseUrl(), props.metadataApiKey());
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
