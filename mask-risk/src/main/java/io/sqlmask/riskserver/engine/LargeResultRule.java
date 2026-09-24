package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RuleHit;

import java.util.ArrayList;
import java.util.List;

/**
 * LARGE_RESULT: rows returned by the executed query exceed the threshold -
 * the mass-exfiltration signal (feeds on the QUERY event's detail.rowCount).
 */
public final class LargeResultRule implements RuleEvaluator {

  @Override
  public List<RuleHit> evaluate(RiskRule rule, RiskEvent event, RuleContext ctx) {
    List<RuleHit> hits = new ArrayList<>();
    Long rowCount = event.rowCount();
    if (rowCount == null) {
      return hits;
    }
    long threshold = rule.paramInt("thresholdRows", 1000);
    if (rowCount >= threshold) {
      hits.add(new RuleHit(rule.id(), rule.name(), rule.category(), rule.severity(),
          String.format("单次查询返回 %d 行，超过阈值 %d 行", rowCount, threshold)));
    }
    return hits;
  }
}
