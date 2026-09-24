package io.sqlmask.riskserver.web;

import io.sqlmask.riskserver.engine.BaselineService;
import io.sqlmask.riskserver.engine.UserProfile;
import io.sqlmask.riskserver.store.MemoryRiskStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Console view of the learned UEBA profiles: per-user baseline rate, 24-hour
 * activity histogram, median result size, plus a live "bursting" indicator
 * computed over the last ten minutes.
 */
@RestController
@RequestMapping("/api/risk/ueba")
public class UebaController {

  private static final int BURST_WINDOW_MINUTES = 10;
  private static final int BURST_FLOOR = 8;

  private final BaselineService baseline;
  private final MemoryRiskStore store;

  public UebaController(BaselineService baseline, MemoryRiskStore store) {
    this.baseline = baseline;
    this.store = store;
  }

  @GetMapping("/profiles")
  public Map<String, Object> profiles(@RequestParam(defaultValue = "12") int limit) {
    long since = System.currentTimeMillis() - BURST_WINDOW_MINUTES * 60_000L;
    List<UserProfile> sorted = new ArrayList<>(baseline.profiles().values());
    sorted.sort(Comparator.comparingLong(UserProfile::events).reversed());

    List<Map<String, Object>> rows = new ArrayList<>();
    for (UserProfile profile : sorted.subList(0, Math.min(Math.max(1, limit), sorted.size()))) {
      long currentWindow = store.countEventsSince(since, e -> profile.user().equals(e.user()));
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("user", profile.user());
      row.put("events", profile.events());
      row.put("ratePerHour", profile.ratePerHour());
      row.put("histogram", profile.histogram());
      row.put("maxHourCount", profile.maxHourCount());
      row.put("rowCountSamples", profile.rowCountSamples());
      row.put("medianRowCount", profile.medianRowCount());
      row.put("currentWindowCount", currentWindow);
      row.put("bursting", currentWindow >= BURST_FLOOR);
      rows.add(row);
    }
    return Map.of("generatedAt", System.currentTimeMillis(), "profiles", rows);
  }
}
