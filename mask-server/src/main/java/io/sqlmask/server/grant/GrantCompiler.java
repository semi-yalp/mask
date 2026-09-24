package io.sqlmask.server.grant;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles unified grant entries into per-engine GRANT statements.
 *
 * <p>Dialect coverage (v1): PostgreSQL and MySQL produce executable DDL;
 * hive/sparksql/trino produce best-effort text for preview only (engine
 * authorization models vary — Ranger/Sentry/Trino's system-level access
 * control — so applying is intentionally not wired for them yet).
 * GROUP principals become PG group roles (g_ prefix) / are noted for MySQL
 * (role support is 8.0+ only).
 */
@Component
public class GrantCompiler {

  /** One compiled statement with the principal it was generated for. */
  public record CompiledStatement(String principal, String sql) {
  }

  public List<CompiledStatement> compile(String dialect, List<GrantEntry> entries) {
    String d = dialect == null ? "" : dialect.toLowerCase();
    return switch (d) {
      case "postgresql" -> compilePostgres(entries);
      case "mysql" -> compileMysql(entries);
      case "hive", "sparksql", "trino" -> compilePreview(d, entries);
      default -> throw new IllegalArgumentException("unsupported dialect '" + dialect + "'");
    };
  }

  // ---- PostgreSQL: group roles + table/column privileges ----

  private List<CompiledStatement> compilePostgres(List<GrantEntry> entries) {
    List<CompiledStatement> out = new ArrayList<>();
    entries.stream().filter(e -> e.principalType() == GrantEntry.PrincipalType.GROUP)
        .map(GrantEntry::principal)
        .distinct()
        .forEach(group -> out.add(new CompiledStatement(group,
            "CREATE ROLE " + quote("g_" + group) + " NOLOGIN;")));
    for (GrantEntry e : entries) {
      String principal = e.principalType() == GrantEntry.PrincipalType.GROUP
          ? quote("g_" + safe(e.principal()))
          : quote(e.principal());
      String[] parts = e.resourceId().split("\\.");
      switch (e.resourceType()) {
        case TABLE, COLUMN -> {
          String table = quote(parts[0]) + "." + quote(parts[1]) + "." + quote(parts[2]);
          if (e.resourceType() == GrantEntry.ResourceType.COLUMN) {
            out.add(new CompiledStatement(e.principal(),
                "GRANT " + privilege(e) + " (" + quote(parts[3]) + ") ON " + table
                    + " TO " + principal + ";"));
          } else {
            out.add(new CompiledStatement(e.principal(),
                "GRANT " + privilege(e) + " ON " + table + " TO " + principal + ";"));
          }
        }
        case SCHEMA -> out.add(new CompiledStatement(e.principal(),
            "GRANT USAGE ON SCHEMA " + quote(parts[0]) + "." + quote(parts[1])
                + " TO " + principal + ";"));
        case CATALOG -> out.add(new CompiledStatement(e.principal(),
            "-- PostgreSQL has no catalog-level GRANT: grant per database instead"));
      }
    }
    return out;
  }

  // ---- MySQL: database/table privileges ----

  private List<CompiledStatement> compileMysql(List<GrantEntry> entries) {
    List<CompiledStatement> out = new ArrayList<>();
    for (GrantEntry e : entries) {
      String user = "'" + safe(e.principal()) + "'@'%'";
      String[] parts = e.resourceId().split("\\.");
      switch (e.resourceType()) {
        case TABLE -> out.add(new CompiledStatement(e.principal(),
            "GRANT " + privilege(e) + " ON " + backtick(parts[0]) + "." + backtick(parts[1])
                + "." + backtick(parts[2]) + " TO " + user + ";"));
        case SCHEMA -> out.add(new CompiledStatement(e.principal(),
            "GRANT " + privilege(e) + " ON " + backtick(parts[0]) + "." + backtick(parts[1])
                + ".* TO " + user + ";"));
        case CATALOG -> out.add(new CompiledStatement(e.principal(),
            "GRANT " + privilege(e) + " ON " + backtick(parts[0]) + ".* TO " + user + ";"));
        case COLUMN -> out.add(new CompiledStatement(e.principal(),
            "-- MySQL column grants exist but are rarely useful; grant at table level instead"));
      }
    }
    return out;
  }

  private List<CompiledStatement> compilePreview(String dialect, List<GrantEntry> entries) {
    List<CompiledStatement> out = new ArrayList<>();
    for (GrantEntry e : entries) {
      out.add(new CompiledStatement(e.principal(),
          "-- " + dialect + " preview: " + e.privilege() + " on " + e.resourceId()
              + " for " + e.principalType().name().toLowerCase() + " " + e.principal()
              + " (engine-specific grant model; apply is not wired yet)"));
    }
    return out;
  }

  private static String privilege(GrantEntry e) {
    return e.privilege() == GrantEntry.Privilege.ALL ? "ALL PRIVILEGES" : e.privilege().name();
  }

  /** Identifier safety: allow word chars only, refuse everything else. */
  private static String safe(String principal) {
    if (!principal.matches("[A-Za-z0-9_\\-.]+")) {
      throw new IllegalArgumentException("unsafe principal name: " + principal);
    }
    return principal.toLowerCase();
  }

  private static String quote(String identifier) {
    return "\"" + safe(identifier) + "\"";
  }

  private static String backtick(String identifier) {
    return "`" + safe(identifier) + "`";
  }
}
