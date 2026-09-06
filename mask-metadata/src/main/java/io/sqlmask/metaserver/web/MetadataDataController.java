package io.sqlmask.metaserver.web;

import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.metaserver.service.StructureService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Data plane for consumers such as the policy service (spec §4.2). The payload
 * intentionally carries no rowFilter: row filters are policy-domain data.
 */
@RestController
@RequestMapping("/api/metadata")
public class MetadataDataController {

  private final MetadataService instances;
  private final StructureService structures;

  public MetadataDataController(MetadataService instances, StructureService structures) {
    this.instances = instances;
    this.structures = structures;
  }

  @GetMapping("/instances")
  public List<MetadataDtos.InstanceSummaryResponse> list() {
    return instances.list().stream()
        .map(row -> new MetadataDtos.InstanceSummaryResponse(row.name(), row.dialect(),
            row.metadataVersion()))
        .toList();
  }

  @GetMapping("/instances/{name}")
  public MetadataDtos.MetadataResponse structure(@PathVariable("name") String name) {
    InstanceRow row = instances.get(name);
    List<TableStructure> tables = structures.load(name);
    return new MetadataDtos.MetadataResponse(row.name(), row.dialect(), row.metadataVersion(),
        tables.stream().map(MetadataDataController::toPayload).toList());
  }

  @GetMapping("/instances/{name}/version")
  public MetadataDtos.VersionResponse version(@PathVariable("name") String name) {
    InstanceRow row = instances.get(name);
    return new MetadataDtos.VersionResponse(row.name(), row.metadataVersion());
  }

  private static MetadataDtos.TablePayload toPayload(TableStructure table) {
    return new MetadataDtos.TablePayload(table.catalog(), table.schema(), table.name(),
        table.columns().stream()
            .map(c -> new MetadataDtos.ColumnPayload(c.name(), c.type()))
            .toList());
  }
}
