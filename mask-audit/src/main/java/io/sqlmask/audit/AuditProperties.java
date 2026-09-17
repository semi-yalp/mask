package io.sqlmask.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code audit.*} settings (spec §4.5). Defaults equal the documented
 * application.yml values so a bare jar with no config audits to localhost.
 */
@ConfigurationProperties("audit")
public class AuditProperties {

  private boolean enabled = true;
  private String indexPrefix = "mask-audit";
  private int queueCapacity = 10000;
  private int batchSize = 200;
  private long flushIntervalMs = 2000;
  private int sqlMaxChars = 8192;
  private final EffectivePull effectivePull = new EffectivePull();
  private final Elasticsearch elasticsearch = new Elasticsearch();

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public String getIndexPrefix() { return indexPrefix; }
  public void setIndexPrefix(String indexPrefix) { this.indexPrefix = indexPrefix; }
  public int getQueueCapacity() { return queueCapacity; }
  public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
  public int getBatchSize() { return batchSize; }
  public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
  public long getFlushIntervalMs() { return flushIntervalMs; }
  public void setFlushIntervalMs(long flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }
  public int getSqlMaxChars() { return sqlMaxChars; }
  public void setSqlMaxChars(int sqlMaxChars) { this.sqlMaxChars = sqlMaxChars; }

  /** {@code audit.effective-pull.enabled} (spec §5.1): off means zero EFFECTIVE_PULL events. */
  public boolean isEffectivePullEnabled() { return effectivePull.isEnabled(); }

  public EffectivePull getEffectivePull() { return effectivePull; }

  public Elasticsearch getElasticsearch() { return elasticsearch; }

  /** Nested {@code audit.effective-pull.*} binding shape (matches application.yml). */
  public static class EffectivePull {
    private boolean enabled = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
  }

  public static class Elasticsearch {
    private String url = "http://127.0.0.1:9200";
    private String apiKey = "";
    private String username = "";
    private String password = "";

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
  }
}
