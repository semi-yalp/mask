package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RuleHit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL-injection signature matching - the database-firewall pattern set
 * (Imperva/ModSecurity CRS style): every hit carries a labeled evidence
 * fragment so the console can show exactly what tripped.
 */
public final class SqliPatternRule implements RuleEvaluator {

  /** One named signature. */
  public record Signature(String label, Pattern pattern) {
  }

  private final List<Signature> signatures;

  public SqliPatternRule(List<Signature> signatures) {
    this.signatures = List.copyOf(signatures);
  }

  public static Signature sig(String label, String regex) {
    return new Signature(label, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
  }

  @Override
  public List<RuleHit> evaluate(RiskRule rule, RiskEvent event, RuleContext ctx) {
    List<RuleHit> hits = new ArrayList<>();
    String sql = event.originalSql();
    if (sql == null || sql.isBlank()) {
      return hits;
    }
    for (Signature signature : signatures) {
      Matcher matcher = signature.pattern().matcher(sql);
      if (matcher.find()) {
        String fragment = truncate(matcher.group().trim());
        hits.add(new RuleHit(rule.id(), rule.name(), rule.category(), rule.severity(),
            signature.label() + "：\"" + fragment + "\""));
      }
    }
    return hits;
  }

  private static String truncate(String s) {
    return s.length() <= 60 ? s : s.substring(0, 60) + "…";
  }
}
