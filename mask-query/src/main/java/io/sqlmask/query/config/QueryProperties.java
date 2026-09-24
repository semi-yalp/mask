package io.sqlmask.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Query guardrails; non-positive or missing values fall back to defaults.
 * {@code httpSubmitterUrl} arms the optional http submitter (instances with
 * {@code submitter: http} fail with a structured error while it is blank).
 */
@ConfigurationProperties(prefix = "query")
public record QueryProperties(Integer timeoutSeconds, Integer maxRows, Integer maxRowsHard,
    Integer fetchSize, Integer maxConcurrentPerInstance, String httpSubmitterUrl) {

  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public QueryProperties {
    if (timeoutSeconds == null || timeoutSeconds <= 0) timeoutSeconds = 30;
    if (maxRows == null || maxRows <= 0) maxRows = 1000;
    if (maxRowsHard == null || maxRowsHard <= 0) maxRowsHard = 10000;
    // the "hard" cap must cap the default too, or it is not hard
    if (maxRows > maxRowsHard) maxRows = maxRowsHard;
    if (fetchSize == null || fetchSize <= 0) fetchSize = 500;
    if (maxConcurrentPerInstance == null || maxConcurrentPerInstance <= 0) {
      maxConcurrentPerInstance = 10;
    }
    if (httpSubmitterUrl != null && httpSubmitterUrl.isBlank()) httpSubmitterUrl = null;
  }

  /** Legacy-arity constructor used by tests and embedders. */
  public QueryProperties(Integer timeoutSeconds, Integer maxRows, Integer maxRowsHard,
      Integer fetchSize, Integer maxConcurrentPerInstance) {
    this(timeoutSeconds, maxRows, maxRowsHard, fetchSize, maxConcurrentPerInstance, null);
  }
}
