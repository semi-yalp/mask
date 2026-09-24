package io.sqlmask.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code audit.*} settings (spec §4.5). Disabled by default: a bare jar with
 * no config runs the Noop recorder; set {@code audit.enabled=true} (and the
 * elasticsearch block) to point the pipeline at ES.
 */
@ConfigurationProperties("audit")
public class AuditProperties {

  private boolean enabled = false;
  /** Audit storage backend: "jdbc" (shared datasource) or "es". */
  private String store = "es";
  private String indexPrefix = "mask-audit";
  private int indexReplicas = 1;
  private int queueCapacity = 10000;
  private int batchSize = 200;
  private long flushIntervalMs = 2000;
  private int sqlMaxChars = 8192;
  private boolean effectivePullEnabled = true;
  private final Elasticsearch elasticsearch = new Elasticsearch();

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public String getStore() { return store; }
  public void setStore(String store) { this.store = store == null ? "es" : store; }
  /** True when the pipeline targets the shared SQL datasource. */
  public boolean isJdbcStore() { return "jdbc".equalsIgnoreCase(store); }
  public String getIndexPrefix() { return indexPrefix; }
  public void setIndexPrefix(String indexPrefix) { this.indexPrefix = indexPrefix; }
  public int getIndexReplicas() { return indexReplicas; }
  public void setIndexReplicas(int indexReplicas) { this.indexReplicas = indexReplicas; }
  public int getQueueCapacity() { return queueCapacity; }
  public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
  public int getBatchSize() { return batchSize; }
  public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
  public long getFlushIntervalMs() { return flushIntervalMs; }
  public void setFlushIntervalMs(long flushIntervalMs) { this.flushIntervalMs = flushIntervalMs; }
  public int getSqlMaxChars() { return sqlMaxChars; }
  public void setSqlMaxChars(int sqlMaxChars) { this.sqlMaxChars = sqlMaxChars; }
  public boolean isEffectivePullEnabled() { return effectivePullEnabled; }
  public void setEffectivePullEnabled(boolean effectivePullEnabled) {
    this.effectivePullEnabled = effectivePullEnabled;
  }
  public Elasticsearch getElasticsearch() { return elasticsearch; }

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
