package io.sqlmask.metaserver.web;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.metaserver.service.MetadataYamlImporter;
import io.sqlmask.metaserver.service.StructureService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin plane: instance CRUD and YAML import (spec §4.1). Collection lives in
 * CollectController; data plane in MetadataDataController.
 */
@RestController
@RequestMapping("/api/instances")
public class MetadataAdminController {

  private final MetadataService instances;
  private final StructureService structures;
  private final MetadataYamlImporter importer;

  public MetadataAdminController(MetadataService instances, StructureService structures,
      MetadataYamlImporter importer) {
    this.instances = instances;
    this.structures = structures;
    this.importer = importer;
  }

  @PostMapping
  public MetadataDtos.InstanceDetailResponse create(
      @RequestBody MetadataDtos.InstanceCreateRequest request) {
    if (request == null || request.name() == null || request.dialect() == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "name and dialect are required");
    }
    InstanceRow row = instances.create(request.name(), request.dialect(),
        ofNullable(request.connection()));
    return detail(row);
  }

  @GetMapping
  public List<MetadataDtos.InstanceSummaryResponse> list() {
    return instances.list().stream()
        .map(row -> new MetadataDtos.InstanceSummaryResponse(row.name(), row.dialect(),
            row.metadataVersion()))
        .toList();
  }

  @GetMapping("/{name}")
  public MetadataDtos.InstanceDetailResponse get(@PathVariable("name") String name) {
    return detail(instances.get(name));
  }

  @PutMapping("/{name}")
  public MetadataDtos.InstanceDetailResponse update(@PathVariable("name") String name,
      @RequestBody MetadataDtos.InstanceUpdateRequest request) {
    ConnectionInfo connection = ofNullable(request == null ? null : request.connection());
    return detail(instances.updateConnection(name, connection));
  }

  @DeleteMapping("/{name}")
  public MetadataDtos.InstanceSummaryResponse delete(@PathVariable("name") String name) {
    InstanceRow row = instances.get(name);
    instances.delete(name);
    return new MetadataDtos.InstanceSummaryResponse(row.name(), row.dialect(),
        row.metadataVersion());
  }

  @PostMapping("/import")
  public MetadataDtos.ImportResponse importYaml(
      @RequestBody MetadataDtos.InstanceImportRequest request) {
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
    instances.create(request.name(), request.dialect(),
        ofNullable(request.connection()));
    long version = structures.replace(request.name().trim(), tables);
    int columnCount = tables.stream().mapToInt(t -> t.columns().size()).sum();
    return new MetadataDtos.ImportResponse(request.name().trim(), tables.size(), columnCount,
        version);
  }

  static ConnectionInfo ofNullable(MetadataDtos.ConnectionRequest request) {
    return request == null ? null : ConnectionInfo.ofNullable(request.host(), request.port(),
        request.database(), request.dbUser(), request.passwordRef(), request.sslmode(),
        request.connectTimeoutSeconds(), request.schemas(), request.includeViews());
  }

  private MetadataDtos.InstanceDetailResponse detail(InstanceRow row) {
    return new MetadataDtos.InstanceDetailResponse(row.name(), row.dialect(),
        row.metadataVersion(), row.connection(), structures.load(row.name()));
  }
}
