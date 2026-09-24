package io.sqlmask.metaserver.web;

import io.sqlmask.audit.AuditAdminHelper;
import io.sqlmask.metaserver.service.CollectService;
import io.sqlmask.metaserver.service.MetadataService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Collection trigger: uses the stored connection reference, never request-borne
 * passwords. Every trigger emits one COLLECT ADMIN_CHANGE audit event.
 */
@RestController
@RequestMapping("/api/meta/instances")
public class CollectController {

  private final CollectService collectService;
  private final MetadataService instances;
  private final AuditAdminHelper audit;

  public CollectController(CollectService collectService, MetadataService instances,
      @org.springframework.beans.factory.annotation.Qualifier("metadataAuditAdminHelper")
      AuditAdminHelper audit) {
    this.collectService = collectService;
    this.instances = instances;
    this.audit = audit;
  }

  @PostMapping("/{name}/collect")
  public MetadataDtos.CollectResponse collect(HttpServletRequest httpRequest,
      @PathVariable("name") String name) {
    return audit.adminChange(httpRequest, "COLLECT", "INSTANCE", null, name,
        () -> {
          var row = instances.get(name);
          var connection = row.connection();
          return connection == null
              ? Map.of("engine", row.dialect())
              : Map.of("engine", row.dialect(), "database", String.valueOf(connection.database()));
        },
        () -> collectService.collect(name));
  }
}
