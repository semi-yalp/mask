package io.sqlmask.policyserver.udf;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.introspect.NetworkGuard;
import io.sqlmask.policyserver.web.PolicyApiExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * UDF 中心 HTTP surface on top of {@link UdfCenterService}: template listing,
 * engine import, registry resync and template/DDL deploy. The request names
 * the engine target by host/port/database/user plus a
 * {@code SQLMASK_*}-password environment-variable reference — the password
 * itself never travels in the request body and never appears in logs or
 * errors. The target host passes the {@link NetworkGuard} egress check
 * (link-local denied by default), mirroring the metadata pull endpoints.
 *
 * <p>Errors follow the module contract via {@link PolicyApiExceptionHandler}:
 * malformed requests are 400; an unreachable or failing engine surfaces as
 * {@code INTROSPECT_ERROR}, which maps to 502 — the engine is an upstream
 * this service depends on.</p>
 */
@RestController
@RequestMapping("/api/instances/{instance}/udfs")
public class UdfCenterController {

  /** Engine-location fields shared by the import/resync and deploy bodies. */
  public interface EngineTarget {
    String engine();
    String host();
    Integer port();
    String database();
    String user();
    String passwordRef();
  }

  public record ImportRequest(String engine, String host, Integer port, String database,
      String user, String passwordRef) implements EngineTarget {
  }

  public record DeployRequest(String template, String ddl, String engine, String host,
      Integer port, String database, String user, String passwordRef) implements EngineTarget {
  }

  private final UdfCenterService service;
  private final NetworkGuard.Policy networkGuard;

  public UdfCenterController(UdfCenterService service,
      @Value("${sqlmask.network-guard:link-local}") String networkGuard) {
    this.service = service;
    this.networkGuard = NetworkGuard.parsePolicy(networkGuard);
  }

  /** Static template catalog — no engine round trip, so the instance is not resolved. */
  @GetMapping("/templates")
  public Map<String, Object> templates() {
    return Map.of("templates", UdfTemplates.names());
  }

  @PostMapping("/import")
  public UdfCenterService.ImportResult importFromEngine(@PathVariable("instance") String instance,
      @RequestBody ImportRequest request) {
    return withConnection(request, connection ->
        service.importFromEngine(instance, connection, request.engine()));
  }

  @PostMapping("/resync")
  public UdfCenterService.ResyncResult resync(@PathVariable("instance") String instance,
      @RequestBody ImportRequest request) {
    return withConnection(request, connection ->
        service.resync(instance, connection, request.engine()));
  }

  @PostMapping("/deploy")
  public UdfCenterService.DeployResult deploy(@PathVariable("instance") String instance,
      @RequestBody DeployRequest request) {
    if (request == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "request body is required");
    }
    boolean wantsTemplate = request.template() != null && !request.template().isBlank();
    boolean wantsDdl = request.ddl() != null && !request.ddl().isBlank();
    if (!wantsTemplate && !wantsDdl) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "deploy requires either 'template' or 'ddl'");
    }
    // unknown template names are rejected before any connection is opened
    if (wantsTemplate && UdfTemplates.template(request.template()).isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "unknown udf template '"
          + request.template() + "' (available: " + UdfTemplates.names() + ")");
    }
    return withConnection(request, connection -> wantsTemplate
        ? service.deployTemplate(instance, request.template(), connection)
        : service.deployDdl(instance, request.ddl(), connection));
  }

  @FunctionalInterface
  private interface ConnectionCall<T> {
    T apply(Connection connection) throws SQLException;
  }

  /**
   * Validates the engine target, resolves the password reference and runs the
   * call against one short-lived connection. The URL keeps no credentials
   * (they go through JDBC properties) and carries a 10-second connect
   * timeout — seconds for the PostgreSQL driver, milliseconds for Connector/J
   * (see {@code ConnectionSpec}).
   */
  private <T> T withConnection(EngineTarget target, ConnectionCall<T> call) {
    if (target == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "request body is required");
    }
    String engine = target.engine() == null ? "" : target.engine().trim().toLowerCase(Locale.ROOT);
    if (!engine.equals("postgresql") && !engine.equals("mysql")) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "engine is required (supported: postgresql, mysql)");
    }
    if (target.host() == null || target.host().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "host is required");
    }
    if (target.database() == null || target.database().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "database is required");
    }
    if (target.user() == null || target.user().isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "user is required");
    }
    int port = target.port() == null ? ("mysql".equals(engine) ? 3306 : 5432) : target.port();
    String url = "mysql".equals(engine)
        ? "jdbc:mysql://" + target.host() + ":" + port + "/" + target.database()
            + "?connectTimeout=10000&socketTimeout=60000"
        : "jdbc:postgresql://" + target.host() + ":" + port + "/" + target.database()
            + "?connectTimeout=10";
    NetworkGuard.checkHost(target.host(), networkGuard);
    String password = EnvPassword.resolve(target.passwordRef());
    DriverManager.setLoginTimeout(10);
    Properties props = new Properties();
    props.setProperty("user", target.user());
    props.setProperty("password", password);
    try (Connection connection = DriverManager.getConnection(url, props)) {
      return call.apply(connection);
    } catch (SQLException e) {
      throw new SqlMaskException(SqlMaskException.Code.INTROSPECT_ERROR,
          "engine connection failed: " + sanitize(e.getMessage()), e);
    }
  }

  /** Strips anything that may carry connection details from driver messages. */
  private static String sanitize(String message) {
    if (message == null) {
      return "unknown error";
    }
    int urlIndex = message.indexOf("jdbc:");
    return urlIndex >= 0 ? message.substring(0, urlIndex).trim() : message;
  }
}
