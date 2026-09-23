package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RuleHit;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * OFF_HOURS_ACCESS: the event's local hour falls outside the configured
 * business window. Judged on the event timestamp (not wall clock) so replayed
 * history and live traffic score identically.
 */
public final class OffHoursRule implements RuleEvaluator {

  @Override
  public List<RuleHit> evaluate(RiskRule rule, RiskEvent event, RuleContext ctx) {
    List<RuleHit> hits = new ArrayList<>();
    int start = rule.paramInt("startHour", 8);
    int end = rule.paramInt("endHour", 19);
    ZonedDateTime local = event.timestamp().atZone(java.time.ZoneId.systemDefault());
    int hour = local.getHour();
    boolean offHours = start <= end
        ? (hour < start || hour >= end)
        : (hour >= end && hour < start);   // overnight window, e.g. 19-8
    if (offHours) {
      hits.add(new RuleHit(rule.id(), rule.name(), rule.category(), rule.severity(),
          String.format("访问发生在本地时间 %02d:%02d，业务时段为 [%02d:00, %02d:00)",
              hour, local.getMinute(), start, end)));
    }
    return hits;
  }
}
