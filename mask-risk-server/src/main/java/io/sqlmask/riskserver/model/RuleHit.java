package io.sqlmask.riskserver.model;

/**
 * One rule firing against one event: which rule, how severe, and the concrete
 * evidence (matched fragment / computed threshold) shown in the console.
 */
public record RuleHit(
    String ruleId,
    String ruleName,
    String category,
    RiskSeverity severity,
    String evidence) {

  public RuleHit {
    evidence = evidence == null ? "" : evidence;
  }
}
