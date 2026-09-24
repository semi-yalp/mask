package io.sqlmask.server.grant;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.service.MetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified authorization over the metadata layer: entries live once here, and
 * per-engine GRANT statements are compiled from them. Preview is the default
 * surface; {@code apply} executes the compiled DDL against the instance's
 * connection (PostgreSQL/MySQL today) and is explicit per request.
 */
@Service
public class GrantService {

  private static final Logger log = LoggerFactory.getLogger(GrantService.class);

  private final GrantStore store;
  private final GrantCompiler compiler;
  private final MetadataService metadata;

  public GrantService(GrantStore store, GrantCompiler compiler, MetadataService metadata) {
    this.store = store;
    this.compiler = compiler;
    this.metadata = metadata;
  }

  public GrantEntry create(String instance, GrantEntry.PrincipalType principalType,
      String principal, GrantEntry.ResourceType resourceType, String resourceId,
      GrantEntry.Privilege privilege, String grantedBy) {
    requireInstance(instance);
    GrantEntry entry = new GrantEntry(0, instance, principalType, principal,
        resourceType, resourceId, privilege, grantedBy, null);
    entry.validate();
    return store.findExact(instance, principalType, principal, resourceType, resourceId,
        privilege)
        .orElseGet(() -> store.insert(entry));
  }

  public List<GrantEntry> byPrincipal(String instance, String principalType, String principal) {
    requireInstance(instance);
    return store.find(instance, principalType, principal);
  }

  public List<GrantEntry> byInstance(String instance) {
    requireInstance(instance);
    return store.listByInstance(instance);
  }

  public boolean delete(String instance, long id) {
    requireInstance(instance);
    return store.delete(id);
  }

  /** Compiled GRANT statements for one principal on one instance. */
  public List<GrantCompiler.CompiledStatement> preview(String instance, String principalType,
      String principal) {
    requireInstance(instance);
    InstanceRow row = metadata.get(instance);
    List<GrantEntry> entries = store.find(instance, principalType, principal);
    if (entries.isEmpty()) {
      return List.of();
    }
    return compiler.compile(row.dialect(), entries);
  }

  /** Executes the compiled DDL against the instance connection. */
  public Map<String, Object> apply(String instance, String principalType, String principal) {
    InstanceRow row = metadata.get(instance);
    ConnectionInfo c = row.connection();
    if (c == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "instance '" + instance + "' has no connection; cannot apply grants");
    }
    List<GrantCompiler.CompiledStatement> statements = preview(instance, principalType, principal);
    String password = System.getenv(
        c.passwordRef() == null || !c.passwordRef().startsWith("SQLMASK_")
            ? "SQLMASK_PASSWORD" : c.passwordRef());
    if (password == null || password.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "passwordRef '" + c.passwordRef() + "' is not set in the environment; cannot connect");
    }
    String url = jdbcUrl(row, c);
    List<String> executed = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    try (Connection connection = java.sql.DriverManager.getConnection(url, c.dbUser(), password)) {
      for (GrantCompiler.CompiledStatement cs : statements) {
        if (cs.sql().startsWith("--") || cs.sql().startsWith("CREATE ROLE g_")) {
          // comments are informational; roles may already exist
        }
        if (cs.sql().startsWith("--")) {
          continue;
        }
        try (Statement statement = connection.createStatement()) {
          statement.execute(cs.sql());
          executed.add(cs.sql());
        } catch (Exception e) {
          log.warn("grant apply failed on '{}': {} — {}", instance, cs.sql(), e.getMessage());
          failed.add(cs.sql() + " -- " + e.getMessage());
        }
      }
    } catch (Exception e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "cannot connect to apply grants on '" + instance + "': " + e.getMessage(), e);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("instance", instance);
    result.put("executed", executed);
    result.put("failed", failed);
    result.put("dialect", row.dialect());
    return result;
  }

  /** Principal × privilege matrix over the instance's grant entries. */
  public List<Map<String, Object>> matrix(String instance) {
    List<Map<String, Object>> rows = new ArrayList<>();
    Map<String, List<GrantEntry>> byPrincipal = new LinkedHashMap<>();
    for (GrantEntry entry : store.listByInstance(instance)) {
      byPrincipal.computeIfAbsent(
          entry.principalType().name().toLowerCase() + ":" + entry.principal(),
          k -> new ArrayList<>()).add(entry);
    }
    byPrincipal.forEach((principalKey, entries) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("principal", entries.get(0).principal());
      row.put("principalType", entries.get(0).principalType().name());
      row.put("grants", entries.stream().map(e -> Map.of(
          "resourceType", e.resourceType().name(),
          "resourceId", e.resourceId(),
          "privilege", e.privilege().name())).toList());
      rows.add(row);
    });
    return rows;
  }

  private void requireInstance(String instance) {
    metadata.get(instance); // throws METADATA_INSTANCE_NOT_FOUND
  }

  private static String jdbcUrl(InstanceRow row, ConnectionInfo c) {
    return switch (row.effectiveEngine()) {
      case "postgresql" -> "jdbc:postgresql://" + c.host() + ":" + c.port() + "/" + c.database();
      case "mysql", "starrocks" ->
          "jdbc:mysql://" + c.host() + ":" + c.port() + "/" + c.database() + "?connectTimeout=10000";
      default -> throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "grant apply is not supported for engine '" + row.effectiveEngine() + "'");
    };
  }
}
