package io.sqlmask.metaserver.web;

import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.service.MetadataService;
import io.sqlmask.metaserver.store.MetaStore;
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

  public MetadataDataController(MetadataService instances) {
    this.instances = instances;
  }

  @GetMapping("/instances")
  public List<MetadataDtos.InstanceSummaryResponse> list() {
    return instances.list().stream()
        .map(row -> new MetadataDtos.InstanceSummaryResponse(row.name(), row.dialect(),
            row.effectiveEngine(), row.metadataVersion(), row.connection() != null))
        .toList();
  }

  @GetMapping("/instances/{name}")
  public MetadataDtos.MetadataResponse structure(@PathVariable("name") String name) {
    // one consistent snapshot: version and tables always pair (M7)
    MetaStore.InstanceSnapshot snapshot = instances.snapshot(name);
    InstanceRow row = snapshot.instance();
    return new MetadataDtos.MetadataResponse(row.name(), row.dialect(), row.metadataVersion(),
        snapshot.tables().stream().map(MetadataDataController::toPayload).toList(),
        new MetadataDtos.GatewayOptionsResponse(row.submitter(), row.onRewriteFailure(),
            row.topN(), row.insertOverwrite()));
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
