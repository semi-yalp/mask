package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RuleHit;

import java.util.List;

/**
 * One detection strategy. Implementations must be side-effect free apart from
 * the explicit {@link RuleContext} capabilities (window counting via the store,
 * first-access baseline registration) and must never throw on odd input.
 */
@FunctionalInterface
public interface RuleEvaluator {

  /** @return the hits this rule produces for the event (empty when clean). */
  List<RuleHit> evaluate(RiskRule rule, RiskEvent event, RuleContext ctx);
}
