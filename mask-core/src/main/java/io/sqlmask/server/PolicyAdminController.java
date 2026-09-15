package io.sqlmask.server;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Instance and policy admin CRUD — the policy service's management surface,
 * delegating every mutation to the validating PolicyService facade.
 */
@RestController
@RequestMapping("/api/instances")
public class PolicyAdminController {

  public record ColumnDto(String name, String type) {
  }

  public record TableDto(String catalog, String schema, String name, List<ColumnDto> columns) {
  }

  public record InstanceDto(String name, String dialect, List<TableDto> tables) {
  }

  public record TablesDto(List<TableDto> tables) {
  }

  public record SubjectDto(Set<String> users, Set<String> groups) {
  }

  public record ResourceDto(String catalog, String schema, String table, List<String> columns) {
  }

  public record PolicyDto(String name, String policyType, boolean isEnabled,
      ResourceDto resource, SubjectDto subjects, String udf, List<Object> arguments,
      String filterExpr) {
  }

  private final PolicyService service;

  public PolicyAdminController(PolicyService service) {
    this.service = service;
  }

  @PostMapping
  public InstanceDto create(@RequestBody InstanceDto request) {
    return toDto(service.createInstance(request.name(), request.dialect(), toTables(request.tables())));
  }

  @GetMapping
  public List<InstanceDto> list() {
    return service.instances().stream().map(PolicyAdminController::toDto).toList();
  }

  @GetMapping("/{name}")
  public InstanceDto get(@PathVariable("name") String name) {
    return toDto(service.instance(name));
  }

  @PutMapping("/{name}/tables")
  public InstanceDto replaceTables(@PathVariable("name") String name,
      @RequestBody TablesDto request) {
    return toDto(service.updateInstanceTables(name, toTables(request.tables())));
  }

  @DeleteMapping("/{name}")
  public void delete(@PathVariable("name") String name) {
    service.deleteInstance(name);
  }

  @PostMapping("/{name}/policies")
  public PolicyDto createPolicy(@PathVariable("name") String name,
      @RequestBody PolicyDto request) {
    return toDto(service.createPolicy(name, toModel(request)));
  }

  @GetMapping("/{name}/policies")
  public List<PolicyDto> listPolicies(@PathVariable("name") String name) {
    return service.policies(name).stream().map(PolicyAdminController::toDto).toList();
  }

  @GetMapping("/{name}/policies/{policy}")
  public PolicyDto getPolicy(@PathVariable("name") String name, @PathVariable("policy") String policy) {
    return service.policies(name).stream()
        .filter(p -> p.name().equals(policy)).findFirst().map(PolicyAdminController::toDto)
        .orElseThrow(() -> new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "policy '" + policy + "' not found in instance '" + name + "'"));
  }

  @PutMapping("/{name}/policies/{policy}")
  public PolicyDto updatePolicy(@PathVariable("name") String name,
      @PathVariable("policy") String policy, @RequestBody PolicyDto request) {
    return toDto(service.updatePolicy(name, policy, toModel(request)));
  }

  @DeleteMapping("/{name}/policies/{policy}")
  public void deletePolicy(@PathVariable("name") String name, @PathVariable("policy") String policy) {
    service.deletePolicy(name, policy);
  }

  private static List<TableDef> toTables(List<TableDto> tables) {
    if (tables == null) {
      return List.of();
    }
    return tables.stream()
        .map(t -> new TableDef(t.catalog(), t.schema(), t.name(),
            (t.columns() == null ? List.<ColumnDto>of() : t.columns()).stream()
                .map(c -> new ColumnDef(c.name(), c.type())).toList()))
        .toList();
  }

  private static PolicyEntity toModel(PolicyDto dto) {
    requireResource(dto);
    SubjectSelector subjects = dto.subjects() == null ? null
        : new SubjectSelector(dto.subjects().users(), dto.subjects().groups());
    return new PolicyEntity(dto.name(), parseType(dto.policyType()), dto.isEnabled(),
        new ResourceSelector(dto.resource().catalog(), dto.resource().schema(),
            dto.resource().table(), dto.resource().columns() == null
                ? List.of() : dto.resource().columns()),
        subjects, dto.udf(), dto.arguments(), dto.filterExpr());
  }

  /**
   * Input guard: a missing resource would surface as an unwrapped NPE while
   * reading its fields (HTTP 500); reject it here as a 400. Mirrors
   * UdfController's null-safe input guards.
   */
  private static void requireResource(PolicyDto dto) {
    if (dto.resource() == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy '" + dto.name() + "': resource is required");
    }
  }

  private static PolicyType parseType(String raw) {
    try {
      return PolicyType.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unknown policyType '" + raw + "' (expected: DATAMASK, ROW_FILTER)");
    }
  }

  private static PolicyDto toDto(PolicyEntity p) {
    return new PolicyDto(p.name(), p.policyType().name().toLowerCase(Locale.ROOT),
        p.enabled(),
        new ResourceDto(p.resource().catalog(), p.resource().schema(),
            p.resource().table(), p.resource().columns()),
        new SubjectDto(p.subjects().users(), p.subjects().groups()),
        p.udf(), p.arguments(), p.filterExpr());
  }

  private static InstanceDto toDto(EngineInstance i) {
    return new InstanceDto(i.name(), i.dialect(), i.tables().stream()
        .map(t -> new TableDto(t.catalog(), t.schema(), t.name(), t.columns().stream()
            .map(c -> new ColumnDto(c.name(), c.typeDeclaration())).toList()))
        .toList());
  }
}
