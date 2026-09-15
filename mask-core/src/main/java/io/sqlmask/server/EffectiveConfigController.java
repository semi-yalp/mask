package io.sqlmask.server;

import io.sqlmask.config.source.EffectiveConfigResponse;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policyserver.PolicyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Policy-service data plane: the subject-parameterized compiled effective
 * config. Absent user/groups is the anonymous subject (wildcard policies only).
 */
@RestController
public class EffectiveConfigController {

  private final PolicyService service;

  public EffectiveConfigController(PolicyService service) {
    this.service = service;
  }

  @GetMapping("/api/effective/{instance}")
  public EffectiveConfigResponse effective(
      @PathVariable("instance") String instance,
      @RequestParam(value = "user", required = false) String user,
      @RequestParam(value = "groups", required = false) List<String> groups) {
    return service.effective(instance, Subject.of(user, groups));
  }
}
