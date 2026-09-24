package io.sqlmask.riskserver.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A grouped, stateful alert (open → acknowledged → resolved). Grouping key is
 * rule × user: repeat hits inside the cooldown window fold into the existing
 * open alert instead of spawning duplicates - the Alertmanager pattern.
 */
public final class Alert {

  public static final String STATUS_OPEN = "OPEN";
  public static final String STATUS_ACKNOWLEDGED = "ACKNOWLEDGED";
  public static final String STATUS_RESOLVED = "RESOLVED";

  private final String id;
  private final String ruleId;
  private final String ruleName;
  private final String category;
  private RiskSeverity severity;
  private final String user;
  private final String sourceIp;
  private final String title;
  private final String description;
  private final String sqlSnippet;
  private final long createdAt;
  private long updatedAt;
  private long lastHitAt;
  private String status = STATUS_OPEN;
  private String ackNote = "";
  private final List<String> eventIds = new ArrayList<>();
  private int eventCount;

  public Alert(String id, String ruleId, String ruleName, String category,
      RiskSeverity severity, String user, String sourceIp, String title,
      String description, String sqlSnippet, long createdAt, String firstEventId) {
    this.id = id;
    this.ruleId = ruleId;
    this.ruleName = ruleName;
    this.category = category;
    this.severity = severity;
    this.user = user;
    this.sourceIp = sourceIp;
    this.title = title;
    this.description = description;
    this.sqlSnippet = sqlSnippet == null ? "" : sqlSnippet;
    this.createdAt = createdAt;
    this.updatedAt = createdAt;
    this.lastHitAt = createdAt;
    if (firstEventId != null) {
      this.eventIds.add(firstEventId);
      this.eventCount = 1;
    }
  }

  /** Folds one more event hit into this open/acknowledged alert. */
  public void addHit(String eventId, RiskSeverity hitSeverity, long timestamp) {
    if (eventId != null && (eventIds.size() < 50 || eventIds.contains(eventId))) {
      eventIds.add(eventId);
    }
    eventCount++;
    lastHitAt = Math.max(lastHitAt, timestamp);
    updatedAt = lastHitAt;
    severity = RiskSeverity.max(severity, hitSeverity);
  }

  /**
   * Rebuilds an alert with its full persisted state (status workflow, note,
   * folded event ids and counts) - used by the snapshot store on reload.
   */
  public static Alert restore(String id, String ruleId, String ruleName, String category,
      RiskSeverity severity, String user, String sourceIp, String title, String description,
      String sqlSnippet, long createdAt, long updatedAt, long lastHitAt, String status,
      String ackNote, List<String> eventIds, int eventCount) {
    Alert alert = new Alert(id, ruleId, ruleName, category, severity, user, sourceIp,
        title, description, sqlSnippet, createdAt, null);
    alert.updatedAt = updatedAt;
    alert.lastHitAt = lastHitAt;
    if (STATUS_ACKNOWLEDGED.equals(status) || STATUS_RESOLVED.equals(status)) {
      alert.status = status;
    }
    alert.ackNote = ackNote == null ? "" : ackNote;
    if (eventIds != null) {
      eventIds.stream().filter(java.util.Objects::nonNull).limit(50).forEach(alert.eventIds::add);
    }
    alert.eventCount = Math.max(eventCount, alert.eventIds.size());
    return alert;
  }

  public boolean transition(String newStatus, String note) {
    if (STATUS_ACKNOWLEDGED.equals(newStatus) && STATUS_OPEN.equals(status)) {
      status = STATUS_ACKNOWLEDGED;
    } else if (STATUS_RESOLVED.equals(newStatus)) {
      status = STATUS_RESOLVED;
    } else if (STATUS_OPEN.equals(newStatus) && STATUS_ACKNOWLEDGED.equals(status)) {
      status = STATUS_OPEN;
    } else {
      return false;
    }
    if (note != null && !note.isBlank()) {
      ackNote = note;
    }
    updatedAt = System.currentTimeMillis();
    return true;
  }

  public String id() {
    return id;
  }

  public String ruleId() {
    return ruleId;
  }

  public String ruleName() {
    return ruleName;
  }

  public String category() {
    return category;
  }

  public RiskSeverity severity() {
    return severity;
  }

  public String user() {
    return user;
  }

  public String sourceIp() {
    return sourceIp;
  }

  public String title() {
    return title;
  }

  public String description() {
    return description;
  }

  public String sqlSnippet() {
    return sqlSnippet;
  }

  public long createdAt() {
    return createdAt;
  }

  public long updatedAt() {
    return updatedAt;
  }

  public long lastHitAt() {
    return lastHitAt;
  }

  public String status() {
    return status;
  }

  public String ackNote() {
    return ackNote;
  }

  public List<String> eventIds() {
    return List.copyOf(eventIds);
  }

  public int eventCount() {
    return eventCount;
  }
}
