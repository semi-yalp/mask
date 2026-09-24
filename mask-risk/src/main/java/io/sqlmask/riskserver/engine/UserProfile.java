package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskEvent;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One principal's learned behavior profile (UEBA): overall access rate over
 * the observed span, a 24-hour activity histogram, and the typical result-set
 * size for executed queries. Derived from the stored event window - never
 * persisted itself, always recomputable.
 */
public record UserProfile(
    String user,
    long events,
    double ratePerHour,
    int[] histogram,
    long rowCountSamples,
    Long medianRowCount) {

  public UserProfile {
    histogram = histogram == null ? new int[24] : histogram;
  }

  public int maxHourCount() {
    int max = 0;
    for (int count : histogram) {
      max = Math.max(max, count);
    }
    return max;
  }

  /** Groups events by principal and derives each profile. */
  public static Map<String, UserProfile> compute(List<RiskEvent> events) {
    Map<String, List<RiskEvent>> byUser = new LinkedHashMap<>();
    for (RiskEvent event : events) {
      if (event.user() != null && !event.user().isBlank()) {
        byUser.computeIfAbsent(event.user(), k -> new ArrayList<>()).add(event);
      }
    }
    Map<String, UserProfile> profiles = new LinkedHashMap<>();
    byUser.forEach((user, userEvents) -> profiles.put(user, computeOne(userEvents)));
    return profiles;
  }

  private static UserProfile computeOne(List<RiskEvent> events) {
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;
    int[] histogram = new int[24];
    List<Long> rowCounts = new ArrayList<>();
    for (RiskEvent event : events) {
      long ts = event.timestamp().toEpochMilli();
      min = Math.min(min, ts);
      max = Math.max(max, ts);
      histogram[event.timestamp().atZone(ZoneId.systemDefault()).getHour()]++;
      if ("QUERY".equals(event.eventType()) && event.rowCount() != null) {
        rowCounts.add(event.rowCount());
      }
    }
    double spanHours = Math.max(1.0, Math.ceil((max - min) / 3_600_000.0));
    Long median = null;
    if (!rowCounts.isEmpty()) {
      rowCounts.sort(Comparator.naturalOrder());
      int mid = rowCounts.size() / 2;
      median = rowCounts.size() % 2 == 1
          ? rowCounts.get(mid)
          : (rowCounts.get(mid - 1) + rowCounts.get(mid)) / 2;
    }
    return new UserProfile(events.get(0).user(), events.size(),
        Math.round(events.size() / spanHours * 100.0) / 100.0,
        histogram, rowCounts.size(), median);
  }
}
