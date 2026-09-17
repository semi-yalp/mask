package io.sqlmask.policyserver.web;

import io.sqlmask.audit.AuditAdminHelper;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadataclient.MetadataClient;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.TableDef;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Imports table structures collected by the metadata service into a policy
 * instance: creates the instance (dialect from the snapshot) or replaces its
 * tables wholesale; repeated imports of identical structures still advance
 * config_version (updateInstanceTables semantics).
 */
@RestController
public class MetadataImportController {

  public record ImportRequest(String metadataBaseUrl, String metadataApiKey,
      String metadataInstance) {
  }

  public record ImportResponse(String name, String dialect,
      List<PolicyAdminController.TableDto> tables) {
  }

  private final PolicyService service;
  private final MetadataStructureFetcher fetcher;
  private final AuditAdminHelper audit;

  public MetadataImportController(PolicyService service, MetadataStructureFetcher fetcher,
      AuditAdminHelper audit) {
    this.service = service;
    this.fetcher = fetcher;
    this.audit = audit;
  }

  @PostMapping("/api/instances/{name}/import-metadata")
  public ImportResponse importMetadata(HttpServletRequest httpRequest,
      @PathVariable("name") String name, @RequestBody ImportRequest request) {
    if (request == null || request.metadataBaseUrl() == null
        || request.metadataBaseUrl().isBlank() || request.metadataInstance() == null
        || request.metadataInstance().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "metadataBaseUrl and metadataInstance are required");
    }
    return audit.adminChange(httpRequest, "IMPORT", "TABLES", name, name,
        () -> Map.of("sourceInstance", request.metadataInstance()),
        () -> doImport(name, request));
  }

  private ImportResponse doImport(String name, ImportRequest request) {
    MetadataClient.MetadataSnapshot snapshot = fetcher.fetch(request.metadataBaseUrl(),
        request.metadataApiKey(), request.metadataInstance());
    List<TableDef> tables = snapshot.tables().stream()
        .map(t -> new TableDef(t.catalog(), t.schema(), t.name(),
            t.columns().stream()
                .map(c -> new ColumnDef(c.name(), c.type())).toList()))
        .toList();
    EngineInstance existing = service.instances().stream()
        .filter(i -> i.name().equals(name)).findFirst().orElse(null);
    if (existing == null) {
      EngineInstance created = service.createInstance(name, snapshot.dialect(), tables);
      return new ImportResponse(created.name(), created.dialect(),
          PolicyAdminController.toTableDtos(created.tables()));
    }
    if (!existing.dialect().equals(snapshot.dialect())) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "instance '" + name
          + "' is dialect '" + existing.dialect() + "' but metadata instance '"
          + request.metadataInstance() + "' reports '" + snapshot.dialect() + "'");
    }
    EngineInstance updated = service.updateInstanceTables(name, tables);
    return new ImportResponse(updated.name(), updated.dialect(),
        PolicyAdminController.toTableDtos(updated.tables()));
  }
}
