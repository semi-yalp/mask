package io.sqlmask.policyserver;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Test application for the policy-admin domain as a library (replaces the
 * retired standalone PolicyServerApplication). The module test yaml excludes
 * the datasource auto-configuration; tests that need the JDBC branch supply
 * their own DataSource via @TestConfiguration.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(value = "io.sqlmask.policyserver", excludeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ANNOTATION, classes = org.springframework.boot.test.context.TestConfiguration.class))
public class PolicyAdminTestApp {
}
