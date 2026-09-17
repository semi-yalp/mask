package io.sqlmask.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Query guardrails; non-positive or missing values fall back to defaults. */
@ConfigurationProperties(prefix = "query")
public record QueryProperties(Integer timeoutSeconds, Integer maxRows, Integer maxRowsHard,
    Integer fetchSize, Integer maxConcurrentPerInstance) {

  public QueryProperties {
    if (timeoutSeconds == null || timeoutSeconds <= 0) timeoutSeconds = 30;
    if (maxRows == null || maxRows <= 0) maxRows = 1000;
    if (maxRowsHard == null || maxRowsHard <= 0) maxRowsHard = 10000;
    if (fetchSize == null || fetchSize <= 0) fetchSize = 500;
    if (maxConcurrentPerInstance == null || maxConcurrentPerInstance <= 0) {
      maxConcurrentPerInstance = 10;
    }
  }
}
