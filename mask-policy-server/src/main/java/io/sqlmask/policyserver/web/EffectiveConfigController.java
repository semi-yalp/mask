package io.sqlmask.policyserver.web;

import io.sqlmask.config.source.EffectiveConfigResponse;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policyserver.PolicyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Data plane: the compiled effective config for an instance, subject-filtered
 * and stamped with the store's {@code config_version}, consumed by the rewrite
 * service's LRU/refresh client.
 */
@RestController
@RequestMapping("/api/effective")
public class EffectiveConfigController {

  private final PolicyService service;

  public EffectiveConfigController(PolicyService service) {
    this.service = service;
  }

  @GetMapping("/{instance}")
  public EffectiveConfigResponse effective(@PathVariable String instance,
      @RequestParam(required = false) String user,
      @RequestParam(required = false) String groups) {
    List<String> groupList = groups == null || groups.isBlank()
        ? List.of()
        : Arrays.stream(groups.split(",")).map(String::trim).filter(g -> !g.isEmpty()).toList();
    return service.effective(instance, Subject.of(user, groupList));
  }
}