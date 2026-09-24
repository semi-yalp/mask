package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RuleHit;
import io.sqlmask.riskserver.model.RiskSeverity;
import io.sqlmask.riskserver.model.SensitiveColumn;

import java.util.ArrayList;
import java.util.List;

/**
 * NEW_SUBJECT_SENSITIVE: UEBA-style first-access baseline - a principal touches
 * a HIGH-sensitivity column for the first time ever. Low severity on its own,
 * valuable as a correlation input (first access + off hours + burst = attack).
 */
public final class NewSubjectSensitiveRule implements RuleEvaluator {

  @Override
  public List<RuleHit> evaluate(RiskRule rule, RiskEvent event, RuleContext ctx) {
    List<RuleHit> hits = new ArrayList<>();
    String user = event.user();
    if (user == null) {
      return hits;
    }
    RiskSeverity min = RiskSeverity.parse(rule.paramStr("sensitivityLevel", "HIGH"));
    List<String> firstTouches = new ArrayList<>();
    for (SensitiveColumn column : event.columnsAtLeast(min)) {
      boolean seenBefore = ctx.registerBaseline(column.columnKey(), user);
      if (!seenBefore) {
        firstTouches.add(column.columnKey());
      }
    }
    if (!firstTouches.isEmpty()) {
      hits.add(new RuleHit(rule.id(), rule.name(), rule.category(), rule.severity(),
          "新主体首次访问高敏列：" + String.join(", ", firstTouches)));
    }
    return hits;
  }
}
