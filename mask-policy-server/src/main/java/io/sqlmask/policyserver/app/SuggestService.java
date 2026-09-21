package io.sqlmask.policyserver.app;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.match.GlobMatcher;
import io.sqlmask.policyserver.connection.EngineAccess;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import io.sqlmask.policyserver.store.PolicyStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Policy-authoring autocomplete over an instance's metadata. Two sources:
 * the stored snapshot (fast, preferred) or, when the snapshot is empty and a
 * connection exists, a live read-only engine listing (slow, tagged
 * {@code source=live}). Manual entry always remains possible — this endpoint
 * only <em>suggests</em>; it never rejects an otherwise-freeform resource.
 * A live lookup that fails surfaces {@code CONNECTION_FAILED}, never a 500.
 */
public final class SuggestService {

  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 200;

  private final PolicyStore store;
  private final EngineAccess access;

  public SuggestService(PolicyStore store, EngineAccess access) {
    this.store = store;
    this.access = access;
  }

  public SuggestResult suggest(String instanceName, String kind, String q, String schema,
      String table, int limit) {
    EngineInstance instance = store.findInstance(instanceName)
        .orElseThrow(() -> new SqlMaskException(
            SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
            "instance '" + instanceName + "' not found"));
    int cap = limit > 0 ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT;
    String query = normalize(q);
    String schemaFilter = normalize(schema);
    String tableFilter = normalize(table);
    return switch (kind == null ? "" : kind) {
      case "table" -> tables(instance, query, schemaFilter, cap);
      case "column" -> columns(instance, query, schemaFilter, tableFilter, cap);
      case "udf" -> udfs(instance, query, cap);
      default -> throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "unknown suggest kind '" + kind + "' (expected table | column | udf)");
    };
  }

  public record SuggestItem(String name, String schema, String table, String type) {
  }

  public record SuggestResult(String source, List<SuggestItem> items) {
  }

  private SuggestResult tables(EngineInstance instance, String query, String schema, int cap) {
    String source = "snapshot";
    List<TableDef> tables = instance.tables();
    if (tables.isEmpty() && instance.connection() != null) {
      tables = access.fetch(instance.connection());
      source = "live";
    }
    List<SuggestItem> items = new ArrayList<>();
    for (TableDef t : tables) {
      if (!matches(schema, normalize(t.schema())) || !matches(query, normalize(t.name()))) {
        continue;
      }
      items.add(new SuggestItem(t.name(), t.schema(), t.name(), "table"));
      if (items.size() >= cap) {
        break;
      }
    }
    return new SuggestResult(source, List.copyOf(items));
  }

  private SuggestResult columns(EngineInstance instance, String query, String schema,
      String tableFilter, int cap) {
    String source = "snapshot";
    List<TableDef> tables = instance.tables();
    if (tables.isEmpty() && instance.connection() != null) {
      tables = access.fetch(instance.connection());
      source = "live";
    }
    TableDef target = null;
    for (TableDef t : tables) {
      if (matches(schema, normalize(t.schema())) && matches(tableFilter, normalize(t.name()))) {
        target = t;
        break;
      }
    }
    if (target == null) {
      return new SuggestResult(source, List.of());
    }
    List<SuggestItem> items = new ArrayList<>();
    for (ColumnDef c : target.columns()) {
      if (!matches(query, normalize(c.name()))) {
        continue;
      }
      items.add(new SuggestItem(c.name(), target.schema(), target.name(), c.typeDeclaration()));
      if (items.size() >= cap) {
        break;
      }
    }
    return new SuggestResult(source, List.copyOf(items));
  }

  private SuggestResult udfs(EngineInstance instance, String query, int cap) {
    List<SuggestItem> items = new ArrayList<>();
    for (UdfDefinition udf : store.listUdfs(instance.name())) {
      if (!matches(query, normalize(udf.name()))) {
        continue;
      }
      items.add(new SuggestItem(udf.name(), null, null, "udf"));
      if (items.size() >= cap) {
        break;
      }
    }
    return new SuggestResult("snapshot", List.copyOf(items));
  }

  private static boolean matches(String pattern, String value) {
    if (pattern == null || pattern.isEmpty()) {
      return true;
    }
    if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) {
      // A plain string is a prefix suggestion; globs are full wildcard matches.
      return value.startsWith(pattern);
    }
    return GlobMatcher.matches(pattern, value);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}