package io.sqlmask.metaserver.web;

import io.sqlmask.metaserver.service.CollectService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Collection trigger: uses the stored connection reference, never request-borne passwords. */
@RestController
@RequestMapping("/api/instances")
public class CollectController {

  private final CollectService collectService;

  public CollectController(CollectService collectService) {
    this.collectService = collectService;
  }

  @PostMapping("/{name}/collect")
  public MetadataDtos.CollectResponse collect(@PathVariable("name") String name) {
    return collectService.collect(name);
  }
}
