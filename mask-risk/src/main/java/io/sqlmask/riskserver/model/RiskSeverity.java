package io.sqlmask.riskserver.model;

import java.util.Locale;

/**
 * Risk severity ladder shared by rules, events and alerts. The numeric weight
 * feeds the 0-100 event risk score (sum of hit weights, capped).
 */
public enum RiskSeverity {

  INFO(2, "info"),
  LOW(5, "low"),
  MEDIUM(10, "medium"),
  HIGH(20, "high"),
  CRITICAL(40, "critical");

  private final int weight;
  private final String wire;

  RiskSeverity(int weight, String wire) {
    this.weight = weight;
    this.wire = wire;
  }

  public int weight() {
    return weight;
  }

  public String wire() {
    return wire;
  }

  /** Tolerant parse used by config and the API; unknown values map to MEDIUM. */
  public static RiskSeverity parse(String value) {
    if (value == null) {
      return MEDIUM;
    }
    try {
      return RiskSeverity.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return MEDIUM;
    }
  }

  /** @return true when this severity is at or above {@code other}'s rank. */
  public boolean atLeast(RiskSeverity other) {
    return ordinal() >= other.ordinal();
  }

  /** Max of two severities for alert escalation when a group keeps hitting. */
  public static RiskSeverity max(RiskSeverity a, RiskSeverity b) {
    return a.ordinal() >= b.ordinal() ? a : b;
  }
}
