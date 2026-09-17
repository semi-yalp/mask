package io.sqlmask.metaserver.web;

import io.sqlmask.metaserver.service.CollectService;
import io.sqlmask.metaserver.service.MetadataService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Collection trigger: uses the stored connection reference, never request-borne passwords. */
@RestController
@RequestMapping("/api/instances")
public class CollectController {

  private final CollectService collectService;
  private final MetadataService instances;
  private final io.sqlmask.audit.AuditAdminHelper audit;

  public CollectController(CollectService collectService, MetadataService instances,
      io.sqlmask.audit.AuditAdminHelper audit) {
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
        () -> {
          MetadataDtos.CollectResponse response = collectService.collect(name);
          return response;
        });
  }
}
