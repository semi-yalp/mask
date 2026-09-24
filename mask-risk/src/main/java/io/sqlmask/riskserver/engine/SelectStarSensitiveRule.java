package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RuleHit;

import java.util.ArrayList;
import java.util.List;

/**
 * SELECT_STAR_SENSITIVE: {@code SELECT *} against a table that owns sensitive
 * columns - broad exposure that bypasses least-privilege projections.
 */
public final class SelectStarSensitiveRule implements RuleEvaluator {

  @Override
  public List<RuleHit> evaluate(RiskRule rule, RiskEvent event, RuleContext ctx) {
    List<RuleHit> hits = new ArrayList<>();
    if (!event.selectStar() || event.sensitiveColumns().isEmpty()) {
      return hits;
    }
    List<String> columns = new ArrayList<>();
    event.sensitiveColumns().forEach(c -> columns.add(c.columnKey()));
    hits.add(new RuleHit(rule.id(), rule.name(), rule.category(), rule.severity(),
        "SELECT * 全列拉取，暴露敏感列：" + String.join(", ", columns)));
    return hits;
  }
}
