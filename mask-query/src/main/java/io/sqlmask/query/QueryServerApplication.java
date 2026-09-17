package io.sqlmask.query;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class QueryServerApplication {
  public static void main(String[] args) {
    SpringApplication.run(QueryServerApplication.class, args);
  }
}
