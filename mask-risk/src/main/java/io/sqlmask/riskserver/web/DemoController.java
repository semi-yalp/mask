package io.sqlmask.riskserver.web;

import io.sqlmask.riskserver.demo.AttackScenarios;
import io.sqlmask.riskserver.demo.DemoSeeder;
import io.sqlmask.riskserver.engine.RiskEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Demo endpoints: scenario catalog, one-click attack simulation, reseed/reset. */
@RestController
@RequestMapping("/api/risk/demo")
public class DemoController {

  private final DemoSeeder seeder;
  private final AttackScenarios scenarios;
  private final RiskEngine engine;

  public DemoController(DemoSeeder seeder, AttackScenarios scenarios, RiskEngine engine) {
    this.seeder = seeder;
    this.scenarios = scenarios;
    this.engine = engine;
  }

  @GetMapping("/scenarios")
  public List<Map<String, Object>> catalog() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (AttackScenarios.ScenarioInfo info : AttackScenarios.catalog()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", info.id());
      row.put("name", info.name());
      row.put("description", info.description());
      row.put("severity", info.severity());
      out.add(row);
    }
    return out;
  }

  /** Injects one scenario's events with current timestamps (live dashboard). */
  @PostMapping("/simulate/{scenarioId}")
  public Map<String, Object> simulate(@PathVariable String scenarioId) {
    RiskEngine.IngestSummary summary = scenarios.run(scenarioId);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("scenario", scenarioId);
    out.put("accepted", summary.accepted());
    out.put("flagged", summary.flagged());
    out.put("hits", summary.hits());
    out.put("alertsCreated", summary.alertsCreated());
    out.put("alertsUpdated", summary.alertsUpdated());
    return out;
  }

  /** Full reseed: default rules + assets + 48h story (wipes custom rules). */
  @PostMapping("/seed")
  public Map<String, Object> seed() {
    int events = seeder.seed();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("seededEvents", events);
    out.put("alerts", engine.store().snapshotAlerts().size());
    return out;
  }

  /** Clears events/alerts/baseline only (rules and assets untouched). */
  @PostMapping("/reset")
  public Map<String, Object> reset() {
    engine.store().clearAll();
    return Map.of("cleared", true);
  }
}
