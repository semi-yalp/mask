package io.sqlmask.server;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.introspect.ConnectionSpec;
import io.sqlmask.introspect.IntrospectionResult;
import io.sqlmask.introspect.MetadataYamlGenerator;
import io.sqlmask.introspect.PgMetadataIntrospector;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Pulls table/column metadata from a live PostgreSQL database and returns a
 * skeleton YAML for the editor. The password lives only inside this request;
 * nothing is logged and nothing is echoed back.
 */
@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

  private final PgMetadataIntrospector introspector;

  public MetadataController(PgMetadataIntrospector introspector) {
    this.introspector = introspector;
  }

  @PostMapping("/pull")
  public MetadataPullResponse pull(@RequestBody MetadataPullRequest request) {
    if (request == null || request.database() == null || request.database().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "database is required");
    }
    if (request.user() == null || request.user().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "user is required");
    }
    if (request.password() == null || request.password().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "password is required");
    }
    ConnectionSpec spec = new ConnectionSpec("postgresql",
        request.host() == null || request.host().isBlank() ? "127.0.0.1" : request.host(),
        request.port() == null ? 5432 : request.port(),
        request.database(), request.user(), request.password(),
        request.schemas() == null ? List.of() : request.schemas(),
        request.includeViews(), false, "disable", 10);
    IntrospectionResult result = introspector.introspect(spec);
    int columnCount = result.tables().stream().mapToInt(t -> t.columns().size()).sum();
    return new MetadataPullResponse(new MetadataYamlGenerator().generate(result),
        result.tables().size(), columnCount, result.warnings(), result.catalog());
  }

  public record MetadataPullRequest(String host, Integer port, String database, String user,
      String password, List<String> schemas, boolean includeViews) {
  }

  public record MetadataPullResponse(String yaml, int tableCount, int columnCount,
      List<String> warnings, String catalog) {
  }
}
