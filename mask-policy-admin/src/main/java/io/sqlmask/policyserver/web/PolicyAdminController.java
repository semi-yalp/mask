package io.sqlmask.policyserver.web;

import io.sqlmask.audit.AuditAdminHelper;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.metrics.AdminMetrics;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Instance and policy admin CRUD — the policy service's management surface,
 * delegating every mutation to the validating PolicyService facade. Every
 * mutation emits one ADMIN_CHANGE audit event (spec §5.1).
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

  public record PolicyDto(String name, String policyType, boolean isEnabled, Integer priority,
      ResourceDto resource, SubjectDto subjects, String udf, List<Object> arguments,
      String filterExpr) {
  }

  private final PolicyService service;
  private final AuditAdminHelper audit;
  private final AdminMetrics adminMetrics;

  public PolicyAdminController(PolicyService service, @org.springframework.beans.factory.annotation.Qualifier("policyAuditAdminHelper") AuditAdminHelper audit,
      AdminMetrics adminMetrics) {
    this.service = service;
    this.audit = audit;
    this.adminMetrics = adminMetrics;
  }

  @PostMapping
  public InstanceDto create(HttpServletRequest httpRequest, @RequestBody InstanceDto request) {
    return adminMetrics.record("INSTANCE", "CREATE", () -> {
      requireText(request.name(), "instance name");
      requireText(request.dialect(), "instance dialect");
      List<TableDef> tables = toTables(request.tables());
      return audit.adminChange(httpRequest, "CREATE", "INSTANCE", null, request.name(),
          () -> Map.of("dialect", request.dialect(), "tableCount", tables.size()),
          () -> toDto(service.createInstance(request.name(), request.dialect(), tables)));
    });
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
  public InstanceDto replaceTables(HttpServletRequest httpRequest,
      @PathVariable("name") String name, @RequestBody TablesDto request) {
    return adminMetrics.record("TABLES", "REPLACE_TABLES", () -> {
      List<TableDef> tables = toTables(request == null ? null : request.tables());
      return audit.adminChange(httpRequest, "REPLACE_TABLES", "TABLES", name, name,
          () -> Map.of("tableCount", tables.size()),
          () -> toDto(service.updateInstanceTables(name, tables)));
    });
  }

  @DeleteMapping("/{name}")
  public void delete(HttpServletRequest httpRequest, @PathVariable("name") String name) {
    adminMetrics.record("INSTANCE", "DELETE", () -> {
      audit.adminChange(httpRequest, "DELETE", "INSTANCE", null, name,
          Map::of, () -> {
            service.deleteInstance(name);
            return null;
          });
    });
  }

  @PostMapping("/{name}/policies")
  public PolicyDto createPolicy(HttpServletRequest httpRequest,
      @PathVariable("name") String name, @RequestBody PolicyDto request) {
    return adminMetrics.record("POLICY", "CREATE", () ->
        audit.adminChange(httpRequest, "CREATE", "POLICY", name,
            request == null ? null : request.name(),
            () -> policyDetail(request),
            () -> toDto(service.createPolicy(name, toModel(request)))));
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
  public PolicyDto updatePolicy(HttpServletRequest httpRequest,
      @PathVariable("name") String name, @PathVariable("policy") String policy,
      @RequestBody PolicyDto request) {
    return adminMetrics.record("POLICY", "UPDATE", () ->
        audit.adminChange(httpRequest, "UPDATE", "POLICY", name, policy,
            () -> policyDetail(request),
            () -> toDto(service.updatePolicy(name, policy, toModel(request)))));
  }

  @DeleteMapping("/{name}/policies/{policy}")
  public void deletePolicy(HttpServletRequest httpRequest, @PathVariable("name") String name,
      @PathVariable("policy") String policy) {
    adminMetrics.record("POLICY", "DELETE", () -> {
      audit.adminChange(httpRequest, "DELETE", "POLICY", name, policy, Map::of, () -> {
        service.deletePolicy(name, policy);
        return null;
      });
    });
  }

  /** Audit summary of a policy body — no udf arguments (may be sensitive). */
  private static Map<String, Object> policyDetail(PolicyDto dto) {
    if (dto == null) {
      return Map.of();
    }
    var detail = new java.util.LinkedHashMap<String, Object>();
    detail.put("policyType", dto.policyType());
    detail.put("enabled", dto.isEnabled());
    if (dto.resource() != null) {
      detail.put("resource", Map.of("catalog", String.valueOf(dto.resource().catalog()),
          "schema", String.valueOf(dto.resource().schema()),
          "table", String.valueOf(dto.resource().table()),
          "columns", dto.resource().columns() == null ? List.of() : dto.resource().columns()));
    }
    if (dto.subjects() != null) {
      detail.put("subjects", Map.of("users", dto.subjects().users() == null ? List.of()
              : dto.subjects().users(),
          "groups", dto.subjects().groups() == null ? List.of() : dto.subjects().groups()));
    }
    return detail;
  }

  private static List<TableDef> toTables(List<TableDto> tables) {
    if (tables == null) {
      return List.of();
    }
    List<TableDef> defs = new ArrayList<>(tables.size());
    for (int i = 0; i < tables.size(); i++) {
      TableDto t = tables.get(i);
      String tableRef = "table '"
          + (t.name() == null || t.name().isBlank() ? "#" + i : t.name()) + "'";
      requireText(t.catalog(), tableRef + ": catalog");
      requireText(t.schema(), tableRef + ": schema");
      requireText(t.name(), tableRef + ": name");
      List<ColumnDto> columns = t.columns() == null ? List.of() : t.columns();
      List<ColumnDef> columnDefs = new ArrayList<>(columns.size());
      for (int j = 0; j < columns.size(); j++) {
        ColumnDto c = columns.get(j);
        requireText(c.name(), tableRef + ": column '#" + j + "': name");
        columnDefs.add(new ColumnDef(c.name(), c.type()));
      }
      defs.add(new TableDef(t.catalog(), t.schema(), t.name(), columnDefs));
    }
    return defs;
  }

  private static PolicyEntity toModel(PolicyDto dto) {
    requireResource(dto);
    return new PolicyEntity(dto.name(), parseType(dto.policyType()), dto.isEnabled(),
        dto.priority(),
        new ResourceSelector(dto.resource().catalog(), dto.resource().schema(),
            dto.resource().table(), dto.resource().columns() == null
                ? List.of() : dto.resource().columns()),
        toSelector(dto.subjects(), dto.name()), dto.udf(), dto.arguments(), dto.filterExpr());
  }

  /**
   * {@code null} subjects mean "everyone"; the console submits empty users and
   * groups arrays for the same intent (its hint reads 空=任意), so both empty
   * sets normalize to the wildcard instead of failing SubjectSelector's
   * non-empty guard with a 400.
   */
  private static SubjectSelector toSelector(SubjectDto subjects, String policyName) {
    if (subjects == null) {
      return null;
    }
    boolean noUsers = subjects.users() == null || subjects.users().isEmpty();
    boolean noGroups = subjects.groups() == null || subjects.groups().isEmpty();
    if (noUsers && noGroups) {
      return null;
    }
    return new SubjectSelector(
        noUsers ? Set.of() : subjects.users(),
        noGroups ? Set.of() : subjects.groups());
  }

  /**
   * Input guard: a missing resource or blank resource identifier would surface
   * as an unwrapped NPE/IllegalArgumentException (HTTP 500) inside validation;
   * reject it here as a 400. Mirrors UdfController's null-safe input guards.
   */
  private static void requireResource(PolicyDto dto) {
    if (dto.resource() == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy '" + dto.name() + "': resource is required");
    }
    requireText(dto.resource().catalog(), "policy '" + dto.name() + "': resource catalog");
    requireText(dto.resource().schema(), "policy '" + dto.name() + "': resource schema");
    requireText(dto.resource().table(), "policy '" + dto.name() + "': resource table");
  }

  /** Input guard: blank identifiers only fail later inside
   * {@code ColumnKey.normalize} with a bare IllegalArgumentException (HTTP 500);
   * reject them here as a 400 {@code CONFIG_ERROR}. */
  private static void requireText(String value, String what) {
    if (value == null || value.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, what + " is required");
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
        p.enabled(), p.priority(),
        new ResourceDto(p.resource().catalog(), p.resource().schema(),
            p.resource().table(), p.resource().columns()),
        new SubjectDto(p.subjects().users(), p.subjects().groups()),
        p.udf(), p.arguments(), p.filterExpr());
  }

  private static InstanceDto toDto(EngineInstance i) {
    return new InstanceDto(i.name(), i.dialect(), toTableDtos(i.tables()));
  }

  /** Shared with MetadataImportController so both surfaces emit `type`, not `typeDeclaration`. */
  static List<TableDto> toTableDtos(List<TableDef> tables) {
    return tables.stream()
        .map(t -> new TableDto(t.catalog(), t.schema(), t.name(), t.columns().stream()
            .map(c -> new ColumnDto(c.name(), c.typeDeclaration())).toList()))
        .toList();
  }
}
