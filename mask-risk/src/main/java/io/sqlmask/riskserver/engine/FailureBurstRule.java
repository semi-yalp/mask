package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RuleHit;

import java.util.ArrayList;
import java.util.List;

/**
 * FAILURE_BURST: repeated failures by one principal inside a sliding window -
 * parse/validation errors in quick succession usually mean probing, enumeration
 * or automated exploit tooling rather than a human analyst.
 */
public final class FailureBurstRule implements RuleEvaluator {

  @Override
  public List<RuleHit> evaluate(RiskRule rule, RiskEvent event, RuleContext ctx) {
    List<RuleHit> hits = new ArrayList<>();
    String user = event.user();
    if (user == null || !"FAILURE".equals(event.outcome())) {
      return hits;
    }
    int windowSeconds = rule.paramInt("windowSeconds", 60);
    int threshold = rule.paramInt("count", 5);
    long count = ctx.countEvents(
        event.timestamp().minusSeconds(windowSeconds),
        e -> user.equals(e.user()) && "FAILURE".equals(e.outcome()));
    if (count >= threshold && (count % threshold == 0 || count == threshold)) {
      String code = event.errorCode() == null ? "UNKNOWN" : event.errorCode();
      hits.add(new RuleHit(rule.id(), rule.name(), rule.category(), rule.severity(),
          String.format("用户 %s 在 %ds 内累计 %d 次失败（阈值 %d），最近错误码：%s",
              user, windowSeconds, count, threshold, code)));
    }
    return hits;
  }
}
