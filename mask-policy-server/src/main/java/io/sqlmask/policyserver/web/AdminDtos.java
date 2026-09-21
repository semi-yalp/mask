package io.sqlmask.policyserver.web;

import io.sqlmask.policyserver.model.AccessType;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyVersion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Management-plane DTO records shared across the admin controllers. Inputs
 * default defensively (missing {@code accessType} → SELECT, null lists → empty)
 * so the wire stays lenient; outputs never contain passwords (only a
 * {@code hasConnection} flag, never a passwordRef or any credential).
 */
public final class AdminDtos {

  private AdminDtos() {
  }

  public record ColumnDto(String name, String type) {
  }

  public record TableDto(String catalog, String schema, String name, List<ColumnDto> columns) {
    public TableDto {
      columns = columns == null ? List.of() : List.copyOf(columns);
    }
  }

  public record SubjectDto(Set<String> users, Set<String> groups) {
    public SubjectDto {
      users = users == null ? Set.of() : Set.copyOf(users);
      groups = groups == null ? Set.of() : Set.copyOf(groups);
    }
  }

  public record ResourceDto(String catalog, String schema, String table, List<String> columns) {
    public ResourceDto {
      columns = columns == null ? List.of() : List.copyOf(columns);
    }
  }

  public record PolicyDto(String name, String accessType, String policyType, boolean isEnabled,
      Integer priority, ResourceDto resource, SubjectDto subjects, String udf,
      List<Object> arguments, String filterExpr, Integer currentVersion) {

    public PolicyDto {
      priority = priority == null ? 0 : priority;
      arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }
  }

  public record PolicyVersionDto(int version, String changeType, Integer sourceVersion,
      Instant createdAt, PolicyDto policy) {
  }

  public record ConnectionDto(String dialect, String host, int port, String database,
      String dbUser, String passwordRef, String schemas, Boolean includeViews, String sslmode,
      Integer connectTimeoutSeconds) {
  }

  public record InstanceDto(String name, String dialect, String connectionStatus,
      List<TableDto> tables) {
    public InstanceDto {
      tables = tables == null ? List.of() : List.copyOf(tables);
    }
  }

  public record InstanceCreateRequest(String name, String dialect, ConnectionDto connection,
      List<TableDto> tables, Boolean fetchMetadata) {
  }

  public record TablesRequest(List<TableDto> tables) {
    public TablesRequest {
      tables = tables == null ? List.of() : List.copyOf(tables);
    }
  }

  public record UpdateConnectionRequest(ConnectionDto connection) {
  }

  public record RollbackRequest(int version) {
  }

  public record ConnectionTestResponse(boolean ok, String dialect, long latencyMs,
      List<String> warnings) {
    public ConnectionTestResponse {
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }

  public record MetadataFetchResponse(String name, int tableCount, long configVersion) {
  }

  /** Serde helper: low-level (untyped) accepted so controller code stays thin. */
  public static AccessType parseAccessType(String value) {
    if (value == null || value.isBlank()) {
      return AccessType.SELECT;
    }
    try {
      return AccessType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new io.sqlmask.error.SqlMaskException(
          io.sqlmask.error.SqlMaskException.Code.CONFIG_ERROR,
          "unknown accessType '" + value + "' (only SELECT is supported)");
    }
  }

  public static PolicyVersionDto toVersionDto(PolicyVersion v) {
    return new PolicyVersionDto(v.version(), v.changeType().name(), v.sourceVersion(),
        v.createdAt(), toPolicyDto(v.content()));
  }

  public static PolicyDto toPolicyDto(PolicyEntity p) {
    return new PolicyDto(p.name(), p.accessType().name(), p.policyType().name().toLowerCase(
        java.util.Locale.ROOT), p.enabled(), p.priority(),
        toResourceDto(p.resource()), toSubjectDto(p.subjects()), p.udf(), p.arguments(),
        p.filterExpr(), p.currentVersion());
  }

  public static ResourceDto toResourceDto(
      io.sqlmask.policyserver.model.ResourceSelector r) {
    return new ResourceDto(r.catalog(), r.schema(), r.table(), r.columns());
  }

  public static SubjectDto toSubjectDto(io.sqlmask.policy.model.SubjectSelector s) {
    return new SubjectDto(s.users(), s.groups());
  }

  public static io.sqlmask.policyserver.model.ResourceSelector toResource(ResourceDto r) {
    if (r == null) {
      throw new io.sqlmask.error.SqlMaskException(io.sqlmask.error.SqlMaskException.Code.CONFIG_ERROR,
          "resource is required");
    }
    return new io.sqlmask.policyserver.model.ResourceSelector(r.catalog(), r.schema(), r.table(),
        r.columns());
  }

  public static io.sqlmask.policy.model.SubjectSelector toSubject(SubjectDto s) {
    if (s == null) {
      throw new io.sqlmask.error.SqlMaskException(io.sqlmask.error.SqlMaskException.Code.CONFIG_ERROR,
          "subjects is required (use {\"users\":[\"*\"]} for everyone)");
    }
    return new io.sqlmask.policy.model.SubjectSelector(s.users(), s.groups());
  }

  public static io.sqlmask.policyserver.model.ConnectionConfig toConnection(ConnectionDto c) {
    if (c == null) {
      return null;
    }
    return new io.sqlmask.policyserver.model.ConnectionConfig(c.dialect(), c.host(), c.port(),
        c.database(), c.dbUser(), c.passwordRef(), parseSchemas(c.schemas()),
        c.includeViews() == null || c.includeViews(), c.sslmode(), c.connectTimeoutSeconds());
  }

  public static List<String> parseSchemas(String schemas) {
    if (schemas == null || schemas.isBlank()) {
      return List.of();
    }
    return List.of(schemas.split("\\s*,\\s*"));
  }

  public static List<TableDto> toTableDtos(List<io.sqlmask.policyserver.model.TableDef> tables) {
    return tables.stream()
        .map(t -> new TableDto(t.catalog(), t.schema(), t.name(),
            t.columns().stream()
                .map(c -> new ColumnDto(c.name(), c.typeDeclaration())).toList()))
        .toList();
  }

  public static List<io.sqlmask.policyserver.model.TableDef> toTables(List<TableDto> tables) {
    if (tables == null) {
      return List.of();
    }
    return tables.stream()
        .map(t -> new io.sqlmask.policyserver.model.TableDef(t.catalog(), t.schema(), t.name(),
            t.columns().stream()
                .map(c -> new io.sqlmask.policyserver.model.ColumnDef(c.name(), c.type()))
                .toList()))
        .toList();
  }

  public static Map<String, ?> notFound(String message) {
    return Map.of("code", "POLICY_INSTANCE_NOT_FOUND", "message", message);
  }
}