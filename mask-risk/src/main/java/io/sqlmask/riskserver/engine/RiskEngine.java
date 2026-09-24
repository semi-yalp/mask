package io.sqlmask.riskserver.engine;

import io.sqlmask.riskserver.model.RiskEvent;
import io.sqlmask.riskserver.model.RiskRule;
import io.sqlmask.riskserver.model.RiskSeverity;
import io.sqlmask.riskserver.model.RuleHit;
import io.sqlmask.riskserver.store.MemoryRiskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The ingest orchestrator: parse → enrich → store → evaluate every enabled
 * rule → score → group alerts. Ingest is serialized so window rules see a
 * consistent stream; one bad document only drops itself.
 */
public class RiskEngine {

  private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

  /** Result of ingesting a batch of documents. */
  public record IngestSummary(int accepted, int rejected, int flagged, int hits,
      int alertsCreated, int alertsUpdated) {
  }

  private final MemoryRiskStore store;
  private final SqlFeatureExtractor extractor = new SqlFeatureExtractor();
  private final Map<String, RuleEvaluator> evaluators = BuiltInRules.evaluators();
  private final AlertManager alerts;
  private final BaselineService baseline;

  public RiskEngine(MemoryRiskStore store, AlertManager alerts) {
    this(store, alerts, null);
  }

  public RiskEngine(MemoryRiskStore store, AlertManager alerts, BaselineService baseline) {
    this.store = store;
    this.alerts = alerts;
    this.baseline = baseline;
  }

  public MemoryRiskStore store() {
    return store;
  }

  public AlertManager alertManager() {
    return alerts;
  }

  /** Ingests raw audit documents (map shape); malformed entries are rejected. */
  public synchronized IngestSummary ingest(List<Map<String, Object>> documents) {
    int accepted = 0;
    int rejected = 0;
    int flagged = 0;
    int totalHits = 0;
    int created = 0;
    int updated = 0;
    for (Map<String, Object> doc : documents) {
      try {
        Evaluation evaluation = evaluateInternal(IngestParser.parse(doc));
        accepted++;
        if (evaluation.event().flagged()) {
          flagged++;
        }
        totalHits += evaluation.event().hits().size();
        created += evaluation.alertsCreated();
        updated += evaluation.alertsUpdated();
      } catch (IllegalArgumentException e) {
        rejected++;
        log.debug("risk: rejected ingest document: {}", e.getMessage());
      }
    }
    return new IngestSummary(accepted, rejected, flagged, totalHits, created, updated);
  }

  /** Parses, enriches and scores one already-built event (demo seeding path). */
  public synchronized RiskEvent evaluate(RiskEvent.Builder parsed) {
    return evaluateInternal(parsed).event();
  }

  /** One full evaluation: enrich → store → rules → score → alert grouping. */
  private Evaluation evaluateInternal(RiskEvent.Builder parsed) {
    if (parsed.build().id() == null) {
      parsed.id(store.nextEventId());
    }
    RiskEvent enriched = extractor.enrich(parsed.build(), store.snapshotSensitiveColumns());
    store.appendEvent(enriched);

    List<RuleHit> hits = new ArrayList<>();
    RuleContext ctx = new RuleContext(store, false, baseline);
    for (RiskRule rule : store.snapshotRules()) {
      if (!rule.enabled()) {
        continue;
      }
      RuleEvaluator evaluator = rule.spec() != null
          ? evaluators.get("CUSTOM")
          : evaluators.get(rule.id());
      if (evaluator == null) {
        continue;
      }
      try {
        hits.addAll(evaluator.evaluate(rule, enriched, ctx));
      } catch (RuntimeException e) {
        log.warn("risk: rule {} evaluation failed: {}", rule.id(), e.toString());
      }
    }

    int score = 0;
    for (RuleHit hit : hits) {
      score += hit.severity().weight();
    }
    score = Math.min(100, score);

    RiskEvent scored = enriched.toBuilder().hits(hits).riskScore(score).build();
    store.updateEvent(scored);
    int[] alertCounts = alerts.process(scored, hits);
    return new Evaluation(scored, alertCounts[0], alertCounts[1]);
  }

  /** Event plus the alert-grouping outcome of one evaluation. */
  private record Evaluation(RiskEvent event, int alertsCreated, int alertsUpdated) {
  }

  /**
   * Dry-runs a (possibly unsaved) rule against recent events for the console's
   * rule-test feature - no events, alerts or baseline side effects.
   */
  public synchronized Map<String, Object> testRule(RiskRule rule, int limit) {
    RuleEvaluator evaluator = rule.spec() != null
        ? evaluators.get("CUSTOM")
        : evaluators.get(rule.id());
    if (evaluator == null) {
      throw new IllegalArgumentException("no evaluator for rule " + rule.id());
    }
    List<RiskEvent> recent = store.snapshotEvents();
    List<RiskEvent> scope = recent.subList(Math.max(0, recent.size() - limit), recent.size());
    int matches = 0;
    List<Map<String, Object>> samples = new ArrayList<>();
    RuleContext ctx = new RuleContext(store, true, baseline);
    for (RiskEvent event : scope) {
      List<RuleHit> hits;
      try {
        hits = evaluator.evaluate(rule, event, ctx);
      } catch (RuntimeException e) {
        continue;
      }
      if (!hits.isEmpty()) {
        matches++;
        if (samples.size() < 5) {
          samples.add(Map.of(
              "eventId", event.id(),
              "timestamp", event.timestamp().toString(),
              "user", Optional.ofNullable(event.user()).orElse(""),
              "sql", Optional.ofNullable(event.originalSql()).orElse(""),
              "evidence", hits.get(0).evidence()));
        }
      }
    }
    return Map.of("scanned", scope.size(), "matched", matches, "samples", samples);
  }
}
