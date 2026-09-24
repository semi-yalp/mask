package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RuleHit;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * UEBA baseline-deviation detection (BEHAVIOR_BASELINE): compares the event
 * against its principal's learned profile and fires when at least one
 * dimension deviates -
 * <ul>
 *   <li><b>频率</b>: a burst (≥ burstFloor events in the deviation window)
 *       whose implied hourly rate is ≥ rateMultiplier × the baseline rate;</li>
 *   <li><b>结果集</b>: a QUERY returning ≥ rowCountMultiplier × the user's
 *       median row count (needs enough samples);</li>
 *   <li><b>时段</b>: access at an hour the user has never been seen active in
 *       (needs a substantial history).</li>
 * </ul>
 * Cold start is guarded by minHistoryEvents: principals with less history are
 * never judged.
 */
public final class BaselineDeviationRule implements RuleEvaluator {

  @Override
  public List<RuleHit> evaluate(RiskRule rule, RiskEvent event, RuleContext ctx) {
    List<RuleHit> hits = new ArrayList<>();
    String user = event.user();
    if (user == null || ctx.baseline() == null) {
      return hits;
    }
    UserProfile profile = ctx.baseline().profile(user);
    if (profile == null || profile.events() < rule.paramInt("minHistoryEvents", 20)) {
      return hits;
    }

    List<String> deviations = new ArrayList<>();

    int windowMinutes = rule.paramInt("deviationWindowMinutes", 10);
    int burstFloor = rule.paramInt("burstFloor", 8);
    int rateMultiplier = rule.paramInt("rateMultiplier", 5);
    long windowCount = ctx.countEvents(
        event.timestamp().minusSeconds(windowMinutes * 60L),
        e -> user.equals(e.user()));
    double currentRate = windowCount * (60.0 / windowMinutes);
    if (windowCount >= burstFloor && profile.ratePerHour() > 0
        && currentRate >= rateMultiplier * profile.ratePerHour()) {
      deviations.add(String.format(
          "频率偏离:近 %d 分钟 %d 次(折合 %.0f 次/小时),为个人基线 %.2f 次/小时的 %.0f 倍",
          windowMinutes, windowCount, currentRate,
          profile.ratePerHour(), currentRate / profile.ratePerHour()));
    }

    if (event.rowCount() != null
        && profile.rowCountSamples() >= rule.paramInt("rowCountSamplesMin", 10)
        && profile.medianRowCount() != null && profile.medianRowCount() > 0) {
      int volumeMultiplier = rule.paramInt("rowCountMultiplier", 5);
      if (event.rowCount() >= volumeMultiplier * profile.medianRowCount()) {
        deviations.add(String.format(
            "结果集偏离:返回 %d 行,为个人基线中位数 %d 行的 %.0f 倍",
            event.rowCount(), profile.medianRowCount(),
            (double) event.rowCount() / profile.medianRowCount()));
      }
    }

    int hourMinHistory = rule.paramInt("hourMinHistory", 30);
    int hour = event.timestamp().atZone(ZoneId.systemDefault()).getHour();
    // The profile includes the event being judged, so its own hour bucket is
    // already counted: count == 1 means this is the FIRST-ever visit at that
    // hour; anything ≥ 2 means prior activity exists.
    if (profile.events() >= hourMinHistory && profile.histogram()[hour] <= 1) {
      deviations.add(String.format(
          "时段偏离:此前 %d 次访问中从未出现于 %02d 点时段", profile.events() - 1, hour));
    }

    if (!deviations.isEmpty()) {
      hits.add(new RuleHit(rule.id(), rule.name(), rule.category(), rule.severity(),
          "UEBA " + String.join("; ", deviations)));
    }
    return hits;
  }
}
