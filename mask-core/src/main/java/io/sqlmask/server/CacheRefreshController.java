package io.sqlmask.server;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual cache drop on the rewrite service (policy-service spec §5.2): the
 * next instance-mode request re-fetches from the policy service.
 */
@RestController
public class CacheRefreshController {

  public record RefreshRequest(String instance) {
  }

  public record RefreshResponse(int cleared) {
  }

  private final InstanceConfigSources sources;

  public CacheRefreshController(InstanceConfigSources sources) {
    this.sources = sources;
  }

  @PostMapping("/admin/cache/refresh")
  public RefreshResponse refresh(@RequestBody(required = false) RefreshRequest request) {
    return new RefreshResponse(sources.clear(request == null ? null : request.instance()));
  }
}
