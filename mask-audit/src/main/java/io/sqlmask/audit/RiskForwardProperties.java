package io.sqlmask.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the optional risk-service forwarder ({@code risk.forward.*}).
 * A blank {@code url} (the default) keeps the entire forwarding path off, so
 * services that do not opt in behave exactly as before.
 */
@ConfigurationProperties(prefix = "risk.forward")
public class RiskForwardProperties {

  /** Risk-service ingest endpoint; blank disables forwarding entirely. */
  private String url = "";

  /** Shared API key sent as {@code X-Api-Key} on every ingest call. */
  private String apiKey = "";

  private int queueCapacity = 2_048;

  private int batchSize = 100;

  private long flushIntervalMs = 1_000;

  /** SQL truncation applied before forwarding (same semantics as the ES writer). */
  private int sqlMaxChars = 8_192;

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public int getQueueCapacity() {
    return queueCapacity;
  }

  public void setQueueCapacity(int queueCapacity) {
    this.queueCapacity = queueCapacity;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public long getFlushIntervalMs() {
    return flushIntervalMs;
  }

  public void setFlushIntervalMs(long flushIntervalMs) {
    this.flushIntervalMs = flushIntervalMs;
  }

  public int getSqlMaxChars() {
    return sqlMaxChars;
  }

  public void setSqlMaxChars(int sqlMaxChars) {
    this.sqlMaxChars = sqlMaxChars;
  }

  /** {@return true when a non-blank ingest endpoint is configured} */
  public boolean enabled() {
    return url != null && !url.isBlank();
  }
}
