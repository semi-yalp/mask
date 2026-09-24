package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RuleHit;

import java.util.ArrayList;
import java.util.List;

/**
 * MASK_BYPASS: high-sensitivity columns were touched but the pipeline reports
 * {@code masked=false} - a policy gap or an intentional bypass; the data went
 * out in the clear.
 */
public final class MaskBypassRule implements RuleEvaluator {

  @Override
  public List<RuleHit> evaluate(RiskRule rule, RiskEvent event, RuleContext ctx) {
    List<RuleHit> hits = new ArrayList<>();
    if (event.sensitiveColumns().isEmpty()) {
      return hits;
    }
    io.sqlmask.riskserver.model.RiskSeverity min =
        io.sqlmask.riskserver.model.RiskSeverity.parse(rule.paramStr("sensitivityLevel", "HIGH"));
    List<io.sqlmask.riskserver.model.SensitiveColumn> high = event.columnsAtLeast(min);
    if (high.isEmpty()) {
      return hits;
    }
    if (Boolean.TRUE.equals(event.masked())) {
      return hits;
    }
    List<String> columns = new ArrayList<>();
    high.forEach(c -> columns.add(c.columnKey()));
    hits.add(new RuleHit(rule.id(), rule.name(), rule.category(), rule.severity(),
        "高敏列未脱敏直出（masked=false）：" + String.join(", ", columns)));
    return hits;
  }
}
