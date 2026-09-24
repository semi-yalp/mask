package io.sqlmask.server;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.introspect.ConnectionSpec;
import io.sqlmask.introspect.IntrospectionResult;
import io.sqlmask.introspect.MetadataIntrospector;
import io.sqlmask.introspect.MetadataIntrospectors;
import io.sqlmask.introspect.MetadataYamlGenerator;
import io.sqlmask.introspect.NetworkGuard;
import io.sqlmask.introspect.PgMetadataIntrospector;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Pulls table/column metadata from a live PostgreSQL, MySQL or Trino database
 * and returns a skeleton YAML for the editor. The password lives only inside
 * this request; nothing is logged and nothing is echoed back. The target host
 * passes the {@link NetworkGuard} egress check (link-local denied by default).
 */
@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

  private final PgMetadataIntrospector introspector;
  private final NetworkGuard.Policy networkGuard;

  public MetadataController(PgMetadataIntrospector introspector,
      @org.springframework.beans.factory.annotation.Value(
          "${sqlmask.network-guard:link-local}") String networkGuard) {
    this.introspector = introspector;
    this.networkGuard = NetworkGuard.parsePolicy(networkGuard);
  }

  @PostMapping("/pull")
  public MetadataPullResponse pull(@RequestBody MetadataPullRequest request) {
    if (request == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "database is required");
    }
    String resolvedEngine = request.engine() == null || request.engine().isBlank()
        ? "postgresql" : request.engine();
    // the engine registry (mirroring DialectProfiles) rejects unknown engines
    // with CONFIG_ERROR; postgresql keeps the injected bean (Spring wiring +
    // test stubs)
    MetadataIntrospector engineIntrospector = "postgresql".equals(resolvedEngine)
        ? introspector
        : MetadataIntrospectors.byEngine(resolvedEngine);
    if (request.database() == null || request.database().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "database is required");
    }
    if (request.user() == null || request.user().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "user is required");
    }
    if (request.password() == null || request.password().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "password is required");
    }
    String host = request.host() == null || request.host().isBlank()
        ? "127.0.0.1" : request.host();
    NetworkGuard.checkHost(host, networkGuard);
    ConnectionSpec spec = new ConnectionSpec(resolvedEngine,
        host,
        request.port() == null ? 5432 : request.port(),
        request.database(), request.user(), request.password(),
        request.schemas() == null ? List.of() : request.schemas(),
        request.includeViews(), false, request.sslmode(), 10);
    IntrospectionResult result = engineIntrospector.introspect(spec);
    int columnCount = result.tables().stream().mapToInt(t -> t.columns().size()).sum();
    return new MetadataPullResponse(new MetadataYamlGenerator().generate(result),
        result.tables().size(), columnCount, result.warnings(), result.catalog());
  }

  public record MetadataPullRequest(String engine, String host, Integer port, String database,
      String user, String password, List<String> schemas, boolean includeViews,
      String sslmode) {
  }

  public record MetadataPullResponse(String yaml, int tableCount, int columnCount,
      List<String> warnings, String catalog) {
  }
}
