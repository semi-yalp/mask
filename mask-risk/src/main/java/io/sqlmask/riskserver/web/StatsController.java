package io.sqlmask.riskserver.web;

import io.sqlmask.riskserver.stats.StatsCalculator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Dashboard aggregation endpoint. */
@RestController
@RequestMapping("/api/risk/stats")
public class StatsController {

  private final StatsCalculator stats;

  public StatsController(StatsCalculator stats) {
    this.stats = stats;
  }

  @GetMapping("/overview")
  public Map<String, Object> overview(
      @RequestParam(defaultValue = "24") int windowHours,
      @RequestParam(defaultValue = "30") int bucketMinutes) {
    return stats.overview(
        Math.max(1, Math.min(24 * 14, windowHours)),
        Math.max(5, Math.min(24 * 60, bucketMinutes)));
  }
}
