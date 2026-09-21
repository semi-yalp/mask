package io.sqlmask.policyserver.web;

import io.sqlmask.policyserver.app.SuggestService;
import io.sqlmask.policyserver.app.SuggestService.SuggestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Policy-authoring autocomplete: suggest tables/columns/UDFs for an instance,
 * backed by the snapshot or a live engine listing. Suggest is purely
 * advisory — manual resource entry remains always allowed.
 */
@RestController
@RequestMapping("/api/instances/{name}")
public class SuggestController {

  private final SuggestService service;

  public SuggestController(SuggestService service) {
    this.service = service;
  }

  @GetMapping("/suggest")
  public SuggestResult suggest(@PathVariable String name,
      @RequestParam String kind,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String schema,
      @RequestParam(required = false) String table,
      @RequestParam(required = false, defaultValue = "20") int limit) {
    return service.suggest(name, kind, q, schema, table, limit);
  }
}