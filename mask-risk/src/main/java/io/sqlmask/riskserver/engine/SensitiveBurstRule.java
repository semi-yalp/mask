package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RuleHit;
import io.sqlmask.riskserver.model.SensitiveColumn;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SENSITIVE_BURST: the same principal touching sensitive columns at or above
 * {@code sensitivityLevel} more than {@code count} times inside a sliding
 * {@code windowSeconds} window - the classic abnormal-repeated-query signal.
 * Fires on threshold multiples (10th, 20th, … hit) so one burst yields one hit
 * per multiple, not one per event.
 */
public final class SensitiveBurstRule implements RuleEvaluator {

  @Override
  public List<RuleHit> evaluate(RiskRule rule, RiskEvent event, RuleContext ctx) {
    List<RuleHit> hits = new ArrayList<>();
    String user = event.user();
    if (user == null || event.sensitiveColumns().isEmpty()) {
      return hits;
    }
    String level = rule.paramStr("sensitivityLevel", "MEDIUM");
    io.sqlmask.riskserver.model.RiskSeverity min =
        io.sqlmask.riskserver.model.RiskSeverity.parse(level);
    if (event.columnsAtLeast(min).isEmpty()) {
      return hits;
    }

    int windowSeconds = rule.paramInt("windowSeconds", 60);
    int threshold = rule.paramInt("count", 10);
    long count = ctx.countEvents(
        event.timestamp().minusSeconds(windowSeconds),
        e -> user.equals(e.user()) && !e.columnsAtLeast(min).isEmpty());
    if (count >= threshold && (count % threshold == 0 || count == threshold)) {
      List<String> columns = new ArrayList<>();
      for (SensitiveColumn column : event.columnsAtLeast(io.sqlmask.riskserver.model.RiskSeverity.HIGH)) {
        columns.add(column.columnKey());
      }
      if (columns.isEmpty()) {
        for (SensitiveColumn column : event.columnsAtLeast(min)) {
          columns.add(column.columnKey());
        }
      }
      hits.add(new RuleHit(rule.id(), rule.name(), rule.category(), rule.severity(),
          String.format("用户 %s 在 %ds 内第 %d 次访问敏感列（阈值 %d），最近触碰：%s",
              user, windowSeconds, count, threshold, String.join(", ", columns))));
    }
    return hits;
  }
}
