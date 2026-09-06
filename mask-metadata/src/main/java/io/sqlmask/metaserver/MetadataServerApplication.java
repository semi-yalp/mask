package io.sqlmask.metaserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Metadata microservice entry point: instance registry, table structures and collection. */
@SpringBootApplication
public class MetadataServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(MetadataServerApplication.class, args);
  }
}
