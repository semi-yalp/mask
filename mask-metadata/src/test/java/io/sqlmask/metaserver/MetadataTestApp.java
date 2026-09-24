package io.sqlmask.metaserver;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** Test application for the metadata domain as a library. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(value = "io.sqlmask.metaserver", excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ANNOTATION, classes = org.springframework.boot.test.context.TestConfiguration.class))
public class MetadataTestApp {
}
