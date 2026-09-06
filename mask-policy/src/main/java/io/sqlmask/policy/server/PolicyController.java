package io.sqlmask.policy.server;

import io.sqlmask.policy.PolicyException;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.store.PolicyYamlLoader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Policy YAML validation endpoint. Lives in mask-policy so the combined jar
 * (core's component scan) and a future standalone policy service expose the
 * same contract. Errors are PolicyException; the combined jar maps them via
 * ApiExceptionHandler to 400 CONFIG_ERROR.
 */
@RestController
@RequestMapping("/api/policies")
public class PolicyController {

  private final PolicyYamlLoader loader = new PolicyYamlLoader();

  @PostMapping("/parse")
  public PolicyParseResponse parse(@RequestBody PolicyParseRequest request) {
    if (request == null || request.policyYaml() == null || request.policyYaml().isBlank()) {
      throw new PolicyException("policyYaml is required");
    }
    return toResponse(loader.parse(request.policyYaml(), "policies.yaml"));
  }

  private PolicyParseResponse toResponse(List<Policy> policies) {
    return new PolicyParseResponse(policies.stream().map(p -> new PolicyDto(
        p.name(), p.enabled(), p.priority(), p.type().name().toLowerCase(Locale.ROOT),
        p.resources().stream()
            .map(r -> new ResourceDto(r.catalog(), r.schema(), r.table(), r.column()))
            .toList(),
        p.dataMaskItems().size() + p.rowFilterItems().size())).toList());
  }

  public record PolicyParseRequest(String policyYaml) {
  }

  public record PolicyParseResponse(List<PolicyDto> policies) {
  }

  public record PolicyDto(String name, boolean enabled, int priority, String type,
      List<ResourceDto> resources, int itemCount) {
  }

  public record ResourceDto(String catalog, String schema, String table, String column) {
  }
}
