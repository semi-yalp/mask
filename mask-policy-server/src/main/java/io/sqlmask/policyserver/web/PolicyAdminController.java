package io.sqlmask.policyserver.web;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.connection.ConnectionTestResult;
import io.sqlmask.policyserver.connection.EngineAccess;
import io.sqlmask.policyserver.model.ConnectionConfig;
import io.sqlmask.policyserver.model.ConnectionStatus;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.web.AdminDtos.ConnectionDto;
import io.sqlmask.policyserver.web.AdminDtos.ConnectionTestResponse;
import io.sqlmask.policyserver.web.AdminDtos.InstanceCreateRequest;
import io.sqlmask.policyserver.web.AdminDtos.InstanceDto;
import io.sqlmask.policyserver.web.AdminDtos.MetadataFetchResponse;
import io.sqlmask.policyserver.web.AdminDtos.PolicyDto;
import io.sqlmask.policyserver.web.AdminDtos.PolicyVersionDto;
import io.sqlmask.policyserver.web.AdminDtos.RollbackRequest;
import io.sqlmask.policyserver.web.AdminDtos.TablesRequest;
import io.sqlmask.policyserver.web.AdminDtos.UpdateConnectionRequest;
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

/**
 * Management-plane REST: engine connection tests, instances, policies with
 * version history and rollback. Every route delegates to {@link PolicyService}
 * (or {@link EngineAccess} for a standalone connection test) and maps failures
 * through the shared {@code {code,message}} error shape.
 */
@RestController
@RequestMapping("/api")
public class PolicyAdminController {

  private final PolicyService service;
  private final EngineAccess access;

  public PolicyAdminController(PolicyService service, EngineAccess access) {
    this.service = service;
    this.access = access;
  }

  public record ConnectionTestRequestBody(ConnectionDto connection) {
  }

  @PostMapping("/connections/test")
  public ConnectionTestResponse testConnection(@RequestBody ConnectionTestRequestBody body) {
    ConnectionDto dto = body == null ? null : body.connection();
    ConnectionConfig cfg = AdminDtos.toConnection(dto);
    if (cfg == null || cfg.host() == null || cfg.host().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONNECTION_FAILED,
          "connection is required");
    }
    ConnectionTestResult result = access.test(cfg);
    return new ConnectionTestResponse(result.ok(), result.dialect(), result.latencyMs(),
        result.warnings());
  }

  @PostMapping("/instances")
  public InstanceDto createInstance(@RequestBody InstanceCreateRequest request) {
    ConnectionConfig connection = AdminDtos.toConnection(request.connection());
    String dialect = connection != null ? connection.dialect() : request.dialect();
    if (dialect == null || dialect.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "dialect is required (in the connection, or as a top-level field for metadata-only instances)");
    }
    boolean fetch = request.fetchMetadata() != null && request.fetchMetadata();
    List<io.sqlmask.policyserver.model.TableDef> inlineTables =
        AdminDtos.toTables(request.tables());
    EngineInstance instance = service.createInstance(request.name(), dialect, connection, fetch,
        access);
    if (!inlineTables.isEmpty()) {
      // Metadata-only instance with a manually supplied table set.
      instance = service.replaceTables(request.name(), inlineTables);
    }
    return toInstanceDto(instance);
  }

  @GetMapping("/instances")
  public List<InstanceDto> listInstances() {
    return service.instances().stream().map(PolicyAdminController::toInstanceDto).toList();
  }

  @GetMapping("/instances/{name}")
  public InstanceDto getInstance(@PathVariable String name) {
    return toInstanceDto(service.instance(name));
  }

  @PutMapping("/instances/{name}/connection")
  public InstanceDto updateConnection(@PathVariable String name,
      @RequestBody UpdateConnectionRequest request) {
    ConnectionConfig cfg = AdminDtos.toConnection(
        request == null ? null : request.connection());
    if (cfg == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "connection is required");
    }
    return toInstanceDto(service.updateConnection(name, cfg, access));
  }

  @PostMapping("/instances/{name}/connection/test")
  public InstanceDto retestConnection(@PathVariable String name) {
    service.retestConnection(name, access);
    return toInstanceDto(service.instance(name));
  }

  @PostMapping("/instances/{name}/metadata-fetch")
  public MetadataFetchResponse metadataFetch(@PathVariable String name) {
    EngineInstance instance = service.metadataFetch(name, access);
    return new MetadataFetchResponse(name, instance.tables().size(),
        service.configVersion(name));
  }

  @PutMapping("/instances/{name}/tables")
  public InstanceDto replaceTables(@PathVariable String name,
      @RequestBody TablesRequest request) {
    EngineInstance updated = service.replaceTables(name,
        AdminDtos.toTables(request == null ? List.of() : request.tables()));
    return toInstanceDto(updated);
  }

  @DeleteMapping("/instances/{name}")
  public void deleteInstance(@PathVariable String name) {
    service.deleteInstance(name);
  }

  @PostMapping("/instances/{name}/policies")
  public PolicyDto createPolicy(@PathVariable String name, @RequestBody PolicyDto body) {
    return AdminDtos.toPolicyDto(service.createPolicy(name, toEntity(body, 0)));
  }

  @GetMapping("/instances/{name}/policies")
  public List<PolicyDto> listPolicies(@PathVariable String name) {
    return service.policies(name).stream().map(AdminDtos::toPolicyDto).toList();
  }

  @GetMapping("/instances/{name}/policies/{policy}")
  public PolicyDto getPolicy(@PathVariable String name, @PathVariable String policy) {
    return AdminDtos.toPolicyDto(service.policy(name, policy)
        .orElseThrow(() -> new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "policy '" + policy + "' not found")));
  }

  @PutMapping("/instances/{name}/policies/{policy}")
  public PolicyDto updatePolicy(@PathVariable String name, @PathVariable String policy,
      @RequestBody PolicyDto body) {
    int version = body.currentVersion() == null ? 0 : body.currentVersion();
    return AdminDtos.toPolicyDto(
        service.updatePolicy(name, policy, toEntityForUpdate(body, version, policy)));
  }

  @PostMapping("/instances/{name}/policies/{policy}/rollback")
  public PolicyDto rollback(@PathVariable String name, @PathVariable String policy,
      @RequestBody RollbackRequest request) {
    return AdminDtos.toPolicyDto(service.rollbackPolicy(name, policy, request.version()));
  }

  @GetMapping("/instances/{name}/policies/{policy}/versions")
  public List<PolicyVersionDto> versions(@PathVariable String name,
      @PathVariable String policy) {
    return service.policyVersions(name, policy).stream()
        .map(AdminDtos::toVersionDto).toList();
  }

  @DeleteMapping("/instances/{name}/policies/{policy}")
  public void deletePolicy(@PathVariable String name, @PathVariable String policy) {
    service.deletePolicy(name, policy);
  }

  private static PolicyEntity toEntityForUpdate(PolicyDto dto, int version, String pathName) {
    if (dto.name() != null && !dto.name().equals(pathName)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy name in body ('" + dto.name() + "') must match the path policy '" + pathName
              + "' (policy names are immutable)");
    }
    return toEntity(dto, version);
  }

  private static PolicyEntity toEntity(PolicyDto dto, int version) {
    return new PolicyEntity(dto.name(), AdminDtos.parseAccessType(dto.accessType()),
        parseType(dto.policyType()), dto.isEnabled(), dto.priority(),
        AdminDtos.toResource(dto.resource()), AdminDtos.toSubject(dto.subjects()), dto.udf(),
        dto.arguments(), dto.filterExpr(), version);
  }

  private static PolicyType parseType(String type) {
    if (type == null) {
      return PolicyType.DATAMASK;
    }
    return switch (type.trim().toLowerCase(Locale.ROOT)) {
      case "datamask" -> PolicyType.DATAMASK;
      case "row_filter" -> PolicyType.ROW_FILTER;
      default -> throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unknown policy type '" + type + "' (expected datamask | row_filter)");
    };
  }

  private static InstanceDto toInstanceDto(EngineInstance instance) {
    String status = instance.status() == null ? ConnectionStatus.UNCONNECTED.name()
        : instance.status().name();
    return new InstanceDto(instance.name(), instance.dialect(), status,
        AdminDtos.toTableDtos(instance.tables()));
  }
}