package io.sqlmask.riskserver.web;

import io.sqlmask.riskserver.engine.RiskEngine;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Internal data-plane ingest: accepts one audit document or an array (the
 * shape mask-audit's forwarder posts). Every document runs through the rule
 * engine synchronously; malformed entries are counted as rejected, never fail
 * the whole batch.
 */
@RestController
@RequestMapping("/api/risk/ingest")
public class IngestController {

  private final RiskEngine engine;

  public IngestController(RiskEngine engine) {
    this.engine = engine;
  }

  @PostMapping
  public Map<String, Object> ingest(@RequestBody Object body) {
    List<Map<String, Object>> docs = normalize(body);
    if (docs.isEmpty()) {
      throw new IllegalArgumentException(
          "body must be one audit document or an array of documents");
    }
    RiskEngine.IngestSummary summary = engine.ingest(docs);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("accepted", summary.accepted());
    out.put("rejected", summary.rejected());
    out.put("flagged", summary.flagged());
    out.put("hits", summary.hits());
    out.put("alertsCreated", summary.alertsCreated());
    out.put("alertsUpdated", summary.alertsUpdated());
    return out;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> normalize(Object body) {
    List<Map<String, Object>> docs = new ArrayList<>();
    if (body instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map) {
          docs.add((Map<String, Object>) item);
        }
      }
    } else if (body instanceof Map) {
      docs.add((Map<String, Object>) body);
    }
    return docs;
  }
}
