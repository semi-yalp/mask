package io.sqlmask.metaserver.web;

import io.sqlmask.audit.AuditAdminHelper;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.metrics.AdminMetrics;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.metaserver.service.MetadataYamlImporter;
import io.sqlmask.metaserver.service.StructureService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin plane: instance CRUD and YAML import (spec §4.1). Collection lives in
 * CollectController; data plane in MetadataDataController. Every mutation
 * emits one ADMIN_CHANGE audit event (spec §5.2) and one management-plane
 * metric (spec §3.2); the metric wraps the audit so audit semantics are
 * untouched.
 */
@RestController
@RequestMapping("/api/instances")
public class MetadataAdminController {

  private final MetadataService instances;
  private final StructureService structures;
  private final MetadataYamlImporter importer;
  private final AuditAdminHelper audit;
  private final AdminMetrics adminMetrics;

  public MetadataAdminController(MetadataService instances, StructureService structures,
      MetadataYamlImporter importer, AuditAdminHelper audit, AdminMetrics adminMetrics) {
    this.instances = instances;
    this.structures = structures;
    this.importer = importer;
    this.audit = audit;
    this.adminMetrics = adminMetrics;
  }

  @PostMapping
  public MetadataDtos.InstanceDetailResponse create(HttpServletRequest httpRequest,
      @RequestBody MetadataDtos.InstanceCreateRequest request) {
    return adminMetrics.record("METADATA", "CREATE", () -> doCreate(httpRequest, request));
  }

  private MetadataDtos.InstanceDetailResponse doCreate(HttpServletRequest httpRequest,
      MetadataDtos.InstanceCreateRequest request) {
    if (request == null || request.name() == null || request.dialect() == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "name and dialect are required");
    }
    return audit.adminChange(httpRequest, "CREATE", "INSTANCE", null, request.name(),
        () -> Map.of("dialect", request.dialect()),
        () -> {
          InstanceRow row = instances.create(request.name(), request.dialect(), request.engine(),
              ofNullable(request.connection()));
          return detail(row);
        });
  }

  @GetMapping
  public List<MetadataDtos.InstanceSummaryResponse> list() {
    return instances.list().stream()
        .map(row -> new MetadataDtos.InstanceSummaryResponse(row.name(), row.dialect(),
            row.effectiveEngine(), row.metadataVersion()))
        .toList();
  }

  @GetMapping("/{name}")
  public MetadataDtos.InstanceDetailResponse get(@PathVariable("name") String name) {
    return detail(instances.get(name));
  }

  @PutMapping("/{name}")
  public MetadataDtos.InstanceDetailResponse update(HttpServletRequest httpRequest,
      @PathVariable("name") String name,
      @RequestBody MetadataDtos.InstanceUpdateRequest request) {
    return adminMetrics.record("METADATA", "UPDATE", () -> doUpdate(httpRequest, name, request));
  }

  private MetadataDtos.InstanceDetailResponse doUpdate(HttpServletRequest httpRequest,
      String name, MetadataDtos.InstanceUpdateRequest request) {
    return audit.adminChange(httpRequest, "UPDATE", "INSTANCE", null, name, Map::of,
        () -> {
          ConnectionInfo connection = ofNullable(request == null ? null : request.connection());
          return detail(instances.updateConnection(name, connection));
        });
  }

  @DeleteMapping("/{name}")
  public MetadataDtos.InstanceSummaryResponse delete(HttpServletRequest httpRequest,
      @PathVariable("name") String name) {
    return adminMetrics.record("METADATA", "DELETE", () -> doDelete(httpRequest, name));
  }

  private MetadataDtos.InstanceSummaryResponse doDelete(HttpServletRequest httpRequest,
      String name) {
    return audit.adminChange(httpRequest, "DELETE", "INSTANCE", null, name, Map::of,
        () -> {
          InstanceRow row = instances.get(name);
          instances.delete(name);
          return new MetadataDtos.InstanceSummaryResponse(row.name(), row.dialect(),
              row.effectiveEngine(), row.metadataVersion());
        });
  }

  @PostMapping("/import")
  public MetadataDtos.ImportResponse importYaml(HttpServletRequest httpRequest,
      @RequestBody MetadataDtos.InstanceImportRequest request) {
    return adminMetrics.record("METADATA", "IMPORT", () -> doImport(httpRequest, request));
  }

  private MetadataDtos.ImportResponse doImport(HttpServletRequest httpRequest,
      MetadataDtos.InstanceImportRequest request) {
    if (request == null || request.name() == null || request.dialect() == null
        || request.metadataYaml() == null || request.metadataYaml().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "name, dialect and metadataYaml are required");
    }
    List<TableStructure> tables = importer.parse(request.metadataYaml(), request.name());
    if (tables.isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "metadataYaml declares no tables");
    }
    int columnCount = tables.stream().mapToInt(t -> t.columns().size()).sum();
    return audit.adminChange(httpRequest, "IMPORT", "TABLES", null, request.name(),
        () -> Map.of("tableCount", tables.size(), "columnCount", columnCount),
        () -> doImportYaml(request, tables));
  }

  private MetadataDtos.ImportResponse doImportYaml(MetadataDtos.InstanceImportRequest request,
      List<TableStructure> tables) {
    instances.create(request.name(), request.dialect(), null,
        ofNullable(request.connection()));
    try {
      long version = structures.replace(request.name().trim(), tables);
      int columnCount = tables.stream().mapToInt(t -> t.columns().size()).sum();
      return new MetadataDtos.ImportResponse(request.name().trim(), tables.size(), columnCount,
          version);
    } catch (RuntimeException failure) {
      // M8: never leave an empty-shell instance behind when the structure step
      // fails — compensate by removing what create() just wrote, then rethrow
      try {
        instances.delete(request.name().trim());
      } catch (RuntimeException suppressed) {
        failure.addSuppressed(suppressed);
      }
      throw failure;
    }
  }

  static ConnectionInfo ofNullable(MetadataDtos.ConnectionRequest request) {
    return request == null ? null : ConnectionInfo.ofNullable(request.host(), request.port(),
        request.database(), request.dbUser(), request.passwordRef(), request.sslmode(),
        request.connectTimeoutSeconds(), request.schemas(), request.includeViews());
  }

  private MetadataDtos.InstanceDetailResponse detail(InstanceRow row) {
    return new MetadataDtos.InstanceDetailResponse(row.name(), row.dialect(),
        row.effectiveEngine(), row.metadataVersion(), row.connection(),
        structures.load(row.name()));
  }
}
