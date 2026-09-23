package io.sqlmask.riskserver.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One audited SQL access enriched with detection results: the raw audit fields
 * (same shape the mask-audit pipeline emits), the extracted SQL features
 * (tables, sensitive columns, SELECT *), and the rule hits + score produced by
 * the engine.
 */
public record RiskEvent(
    String id,
    Instant timestamp,
    String service,
    String eventType,
    String outcome,
    Long durationMs,
    String sourceIp,
    String user,
    List<String> groups,
    String authKind,
    String dialect,
    Integer statementCount,
    Boolean masked,
    Boolean rowFiltered,
    String originalSql,
    String rewrittenSql,
    Boolean sqlTruncated,
    String errorCode,
    String errorMessage,
    String instance,
    Map<String, Object> detail,
    Long rowCount,
    boolean selectStar,
    List<String> matchedTables,
    List<SensitiveColumn> sensitiveColumns,
    List<RuleHit> hits,
    int riskScore) {

  public RiskEvent {
    groups = groups == null ? List.of() : List.copyOf(groups);
    detail = detail == null ? Map.of() : detail;
    matchedTables = matchedTables == null ? List.of() : List.copyOf(matchedTables);
    sensitiveColumns = sensitiveColumns == null ? List.of() : List.copyOf(sensitiveColumns);
    hits = hits == null ? List.of() : List.copyOf(hits);
  }

  public boolean flagged() {
    return !hits.isEmpty();
  }

  /** Highest severity among hits (INFO when clean). */
  public RiskSeverity topSeverity() {
    RiskSeverity top = RiskSeverity.INFO;
    for (RuleHit hit : hits) {
      top = RiskSeverity.max(top, hit.severity());
    }
    return top;
  }

  /** Sensitive columns at or above the given sensitivity. */
  public List<SensitiveColumn> columnsAtLeast(RiskSeverity level) {
    List<SensitiveColumn> out = new ArrayList<>();
    for (SensitiveColumn column : sensitiveColumns) {
      if (column.sensitivity().atLeast(level)) {
        out.add(column);
      }
    }
    return out;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Copy-builder used when the engine attaches enrichment/hits/score. */
  public Builder toBuilder() {
    return new Builder(this);
  }

  /** Fluent builder over the ~20 optional fields. */
  public static final class Builder {
    private String id;
    private Instant timestamp;
    private String service;
    private String eventType;
    private String outcome;
    private Long durationMs;
    private String sourceIp;
    private String user;
    private List<String> groups;
    private String authKind;
    private String dialect;
    private Integer statementCount;
    private Boolean masked;
    private Boolean rowFiltered;
    private String originalSql;
    private String rewrittenSql;
    private Boolean sqlTruncated;
    private String errorCode;
    private String errorMessage;
    private String instance;
    private Map<String, Object> detail;
    private Long rowCount;
    private boolean selectStar;
    private List<String> matchedTables;
    private List<SensitiveColumn> sensitiveColumns;
    private List<RuleHit> hits;
    private int riskScore;

    private Builder() {
    }

    private Builder(RiskEvent source) {
      this.id = source.id;
      this.timestamp = source.timestamp;
      this.service = source.service;
      this.eventType = source.eventType;
      this.outcome = source.outcome;
      this.durationMs = source.durationMs;
      this.sourceIp = source.sourceIp;
      this.user = source.user;
      this.groups = source.groups;
      this.authKind = source.authKind;
      this.dialect = source.dialect;
      this.statementCount = source.statementCount;
      this.masked = source.masked;
      this.rowFiltered = source.rowFiltered;
      this.originalSql = source.originalSql;
      this.rewrittenSql = source.rewrittenSql;
      this.sqlTruncated = source.sqlTruncated;
      this.errorCode = source.errorCode;
      this.errorMessage = source.errorMessage;
      this.instance = source.instance;
      this.detail = source.detail;
      this.rowCount = source.rowCount;
      this.selectStar = source.selectStar;
      this.matchedTables = source.matchedTables;
      this.sensitiveColumns = source.sensitiveColumns;
      this.hits = source.hits;
      this.riskScore = source.riskScore;
    }

    public Builder id(String id) {
      this.id = id;
      return this;
    }

    public Builder timestamp(Instant timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    public Builder service(String service) {
      this.service = service;
      return this;
    }

    public Builder eventType(String eventType) {
      this.eventType = eventType;
      return this;
    }

    public Builder outcome(String outcome) {
      this.outcome = outcome;
      return this;
    }

    public Builder durationMs(Long durationMs) {
      this.durationMs = durationMs;
      return this;
    }

    public Builder sourceIp(String sourceIp) {
      this.sourceIp = sourceIp;
      return this;
    }

    public Builder user(String user) {
      this.user = user;
      return this;
    }

    public Builder groups(List<String> groups) {
      this.groups = groups;
      return this;
    }

    public Builder authKind(String authKind) {
      this.authKind = authKind;
      return this;
    }

    public Builder dialect(String dialect) {
      this.dialect = dialect;
      return this;
    }

    public Builder statementCount(Integer statementCount) {
      this.statementCount = statementCount;
      return this;
    }

    public Builder masked(Boolean masked) {
      this.masked = masked;
      return this;
    }

    public Builder rowFiltered(Boolean rowFiltered) {
      this.rowFiltered = rowFiltered;
      return this;
    }

    public Builder originalSql(String originalSql) {
      this.originalSql = originalSql;
      return this;
    }

    public Builder rewrittenSql(String rewrittenSql) {
      this.rewrittenSql = rewrittenSql;
      return this;
    }

    public Builder sqlTruncated(Boolean sqlTruncated) {
      this.sqlTruncated = sqlTruncated;
      return this;
    }

    public Builder errorCode(String errorCode) {
      this.errorCode = errorCode;
      return this;
    }

    public Builder errorMessage(String errorMessage) {
      this.errorMessage = errorMessage;
      return this;
    }

    public Builder instance(String instance) {
      this.instance = instance;
      return this;
    }

    public Builder detail(Map<String, Object> detail) {
      this.detail = detail;
      return this;
    }

    public Builder rowCount(Long rowCount) {
      this.rowCount = rowCount;
      return this;
    }

    public Builder selectStar(boolean selectStar) {
      this.selectStar = selectStar;
      return this;
    }

    public Builder matchedTables(List<String> matchedTables) {
      this.matchedTables = matchedTables;
      return this;
    }

    public Builder sensitiveColumns(List<SensitiveColumn> sensitiveColumns) {
      this.sensitiveColumns = sensitiveColumns;
      return this;
    }

    public Builder hits(List<RuleHit> hits) {
      this.hits = hits;
      return this;
    }

    public Builder riskScore(int riskScore) {
      this.riskScore = riskScore;
      return this;
    }

    public RiskEvent build() {
      return new RiskEvent(id, timestamp == null ? Instant.now() : timestamp,
          service, eventType, outcome, durationMs, sourceIp, user, groups, authKind,
          dialect, statementCount, masked, rowFiltered, originalSql, rewrittenSql,
          sqlTruncated, errorCode, errorMessage, instance, detail, rowCount,
          selectStar, matchedTables, sensitiveColumns, hits, riskScore);
    }
  }
}
