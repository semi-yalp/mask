package io.sqlmask.riskserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All tunables ({@code risk.*}) for the risk service. */
@ConfigurationProperties(prefix = "risk")
public class RiskProperties {

  /** Shared API key for /api/risk/**; blank = open with a startup warning. */
  private String apiKey = "";

  private final Alerting alerting = new Alerting();
  private final Store store = new Store();
  private final Demo demo = new Demo();
  private final Notify notify = new Notify();
  private final Block block = new Block();
  private final Ueba ueba = new Ueba();

  public static class Alerting {
    /** Hits at or above this severity become alerts; lower ones only mark events. */
    private String minSeverity = "MEDIUM";

    /** Same rule × user folds into one alert while the last hit is this fresh. */
    private long cooldownMinutes = 15;

    public String getMinSeverity() {
      return minSeverity;
    }

    public void setMinSeverity(String minSeverity) {
      this.minSeverity = minSeverity;
    }

    public long getCooldownMinutes() {
      return cooldownMinutes;
    }

    public void setCooldownMinutes(long cooldownMinutes) {
      this.cooldownMinutes = cooldownMinutes;
    }
  }

  public static class Store {
    private int maxEvents = 50_000;

    /** Snapshot file for restart persistence; blank disables persistence. */
    private String persistencePath = "";

    /** Newest events kept in the snapshot (the rest are replay-window only). */
    private int maxPersistedEvents = 10_000;

    public int getMaxEvents() {
      return maxEvents;
    }

    public void setMaxEvents(int maxEvents) {
      this.maxEvents = maxEvents;
    }

    public String getPersistencePath() {
      return persistencePath;
    }

    public void setPersistencePath(String persistencePath) {
      this.persistencePath = persistencePath;
    }

    public int getMaxPersistedEvents() {
      return maxPersistedEvents;
    }

    public void setMaxPersistedEvents(int maxPersistedEvents) {
      this.maxPersistedEvents = maxPersistedEvents;
    }
  }

  public static class Demo {
    private boolean seedOnStart = true;

    public boolean isSeedOnStart() {
      return seedOnStart;
    }

    public void setSeedOnStart(boolean seedOnStart) {
      this.seedOnStart = seedOnStart;
    }
  }

  /** Outbound alert notifications ({@code risk.notify.*}). */
  public static class Notify {
    /** Webhook receiving new-alert payloads; blank = local log only. */
    private String webhookUrl = "";

    /** Only alerts at or above this severity notify. */
    private String minSeverity = "HIGH";

    public String getWebhookUrl() {
      return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
      this.webhookUrl = webhookUrl;
    }

    public String getMinSeverity() {
      return minSeverity;
    }

    public void setMinSeverity(String minSeverity) {
      this.minSeverity = minSeverity;
    }
  }

  /** Policy-service linked blocking ({@code risk.block.*}). */
  public static class Block {
    /** Policy-service management base URL (e.g. http://127.0.0.1:8081). */
    private String baseUrl = "";

    /** Admin API key for the policy service management plane. */
    private String apiKey = "";

    /** Instance the one-click block targets by default. */
    private String defaultInstance = "crm";

    /** File persisting the active block list; blank disables persistence. */
    private String statePath = "";

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getDefaultInstance() {
      return defaultInstance;
    }

    public void setDefaultInstance(String defaultInstance) {
      this.defaultInstance = defaultInstance;
    }

    public String getStatePath() {
      return statePath;
    }

    public void setStatePath(String statePath) {
      this.statePath = statePath;
    }
  }

  /** UEBA profile computation ({@code risk.ueba.*}). */
  public static class Ueba {
    /** How often the per-user profiles are recomputed from the event window. */
    private int refreshSeconds = 300;

    public int getRefreshSeconds() {
      return refreshSeconds;
    }

    public void setRefreshSeconds(int refreshSeconds) {
      this.refreshSeconds = refreshSeconds;
    }
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public Alerting getAlerting() {
    return alerting;
  }

  public Store getStore() {
    return store;
  }

  public Demo getDemo() {
    return demo;
  }

  public Notify getNotify() {
    return notify;
  }

  public Block getBlock() {
    return block;
  }

  public Ueba getUeba() {
    return ueba;
  }
}
