package io.sqlmask.policyserver.udf;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.introspect.udf.UdfIntrospector;
import io.sqlmask.introspect.udf.UdfIntrospectors;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.UdfDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * UDF 中心 write path: imports the engine's live UDF surface into the
 * registry, diffs it against the registry (resync), and deploys the built-in
 * masking templates (or any caller-supplied DDL) onto the engine. All methods
 * receive an already-open {@link Connection}; opening and closing it is the
 * controller's job, so one request can deploy and re-import over a single
 * connection.
 *
 * <p>The introspector lookup is a constructor-injected {@link Function}
 * defaulting to {@link UdfIntrospectors#byEngine} — a seam that keeps the
 * whole chain testable without a live engine: tests inject a fake returning
 * fixed signatures while the {@link Connection} stays a Mockito mock.</p>
 *
 * <p>Import target: PostgreSQL functions are read from the {@code public}
 * schema (where {@link UdfTemplates} deploys); MySQL reads the connected
 * database ({@code Connection.getCatalog()}). Functions with zero parameters
 * are skipped — a masking UDF must at least bind the column value, and the
 * registry validator rejects them.</p>
 */
@Service
public class UdfCenterService {

  public static final String SOURCE_IMPORTED = "IMPORTED";

  private final PolicyService policies;
  private final Function<String, UdfIntrospector> lookup;

  @Autowired
  public UdfCenterService(PolicyService policies) {
    this(policies, UdfIntrospectors::byEngine);
  }

  UdfCenterService(PolicyService policies, Function<String, UdfIntrospector> lookup) {
    this.policies = policies;
    this.lookup = lookup;
  }

  /** Outcome of an engine import: created/refreshed counts plus the names
    * imported and the ones skipped (unsupported types, registry rejects). */
  public record ImportResult(int created, int updated, List<String> names,
      List<String> skipped) {
  }

  /**
   * Upserts the engine's UDF surface into the registry: unknown names are
   * created with {@code source=IMPORTED}; known names are replaced (also when
   * unchanged — the store has no separate touch method, and the replace
   * refreshes {@code lastSyncedAt}). Every write re-runs the registry's
   * validators, so an import that would break enabled policies fails loudly.
   */
  public ImportResult importFromEngine(String instance, Connection connection, String engine) {
    Map<String, List<UdfIntrospector.UdfSignature>> byName = engineSignatures(instance, connection, engine);
    Map<String, UdfDefinition> existing = new LinkedHashMap<>();
    policies.udfs(instance).forEach(u -> existing.put(u.name(), u));
    int created = 0;
    int updated = 0;
    List<String> skipped = new ArrayList<>();
    List<String> imported = new ArrayList<>();
    for (Map.Entry<String, List<UdfIntrospector.UdfSignature>> entry : byName.entrySet()) {
      UdfDefinition definition;
      try {
        definition = toDefinition(entry.getKey(), entry.getValue());
      } catch (SqlMaskException unsupported) {
        // 引擎里存在本系统类型体系不支持的函数（如 pgcrypto 的 bytea 系）：
        // 跳过并记录，不阻断其余函数的导入
        skipped.add(entry.getKey());
        continue;
      }
      try {
        if (existing.containsKey(entry.getKey())) {
          policies.replaceUdf(instance, entry.getKey(), definition);
          updated++;
        } else {
          policies.createUdf(instance, definition);
          created++;
        }
        imported.add(entry.getKey());
      } catch (SqlMaskException rejected) {
        skipped.add(entry.getKey());
      }
    }
    return new ImportResult(created, updated, List.copyOf(imported), List.copyOf(skipped));
  }

  /** Registry-vs-engine diff: neither side is modified. */
  public record ResyncResult(List<String> addedInEngine, List<String> missingInEngine,
      List<String> changedSignatures, List<UdfDefinition> registry) {
  }

  /**
   * Compares the engine's live surface with the registry without writing:
   * names present only in the engine, names present only in the registry, and
   * names on both sides whose overload sets differ.
   */
  public ResyncResult resync(String instance, Connection connection, String engine) {
    Map<String, Set<String>> engineByName = new TreeMap<>();
    engineSignatures(instance, connection, engine)
        .forEach((name, signatures) -> engineByName.put(name, shapesOf(signatures)));
    List<UdfDefinition> registry = policies.udfs(instance);
    List<String> addedInEngine = new ArrayList<>();
    List<String> missingInEngine = new ArrayList<>();
    List<String> changedSignatures = new ArrayList<>();
    for (UdfDefinition udf : registry) {
      Set<String> engineShapes = engineByName.remove(udf.name());
      if (engineShapes == null) {
        missingInEngine.add(udf.name());
      } else if (!engineShapes.equals(shapesOf(udf))) {
        changedSignatures.add(udf.name());
      }
    }
    addedInEngine.addAll(engineByName.keySet());
    return new ResyncResult(addedInEngine, missingInEngine, changedSignatures, registry);
  }

  /** Outcome of a deploy: a DDL failure is reported, not thrown. */
  public record DeployResult(String template, boolean ok, String error) {
  }

  /**
   * Deploys one built-in template onto the engine and re-imports so the
   * registry immediately reflects the new function. The whole template is a
   * single {@code CREATE OR REPLACE FUNCTION}; it goes to one
   * {@code Statement.execute} — PostgreSQL JDBC accepts the full text, and
   * splitting on semicolons would corrupt the function body. An unknown
   * template name is a {@code CONFIG_ERROR}; an engine-side DDL failure comes
   * back as {@code ok=false} with the (sanitized) driver message.
   */
  public DeployResult deployTemplate(String instance, String templateName, Connection connection) {
    String ddl = UdfTemplates.template(templateName).orElseThrow(() -> new SqlMaskException(
        SqlMaskException.Code.CONFIG_ERROR, "unknown udf template '" + templateName
            + "' (available: " + UdfTemplates.names() + ")"));
    DeployResult executed = execute(ddl, templateName, connection);
    if (!executed.ok()) {
      return executed;
    }
    // templates are PostgreSQL plpgsql/sql: the refresh import follows that engine
    importFromEngine(instance, connection, "postgresql");
    return executed;
  }

  /**
   * Executes arbitrary caller-supplied DDL verbatim (one
   * {@code Statement.execute} of the whole text — the same no-splitting rule
   * as templates, since the DDL may contain function bodies). The registry is
   * not touched; call the import endpoint afterwards to pick the changes up.
   */
  public DeployResult deployDdl(String instance, String ddl, Connection connection) {
    return execute(ddl, "custom-ddl", connection);
  }

  private DeployResult execute(String ddl, String label, Connection connection) {
    try (Statement statement = connection.createStatement()) {
      statement.execute(ddl);
      return new DeployResult(label, true, null);
    } catch (SQLException e) {
      return new DeployResult(label, false, sanitize(e.getMessage()));
    }
  }

  /** Reads the engine's signatures grouped by name; the instance must exist. */
  private Map<String, List<UdfIntrospector.UdfSignature>> engineSignatures(
      String instance, Connection connection, String engine) {
    // policies.udfs validates the instance exists before any engine round trip
    policies.udfs(instance);
    String normalized = engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
    String target;
    try {
      target = "mysql".equals(normalized) ? connection.getCatalog() : "public";
    } catch (SQLException e) {
      throw new SqlMaskException(SqlMaskException.Code.INTROSPECT_ERROR,
          "udf introspection failed: " + sanitize(e.getMessage()), e);
    }
    List<UdfIntrospector.UdfSignature> signatures;
    try {
      signatures = lookup.apply(normalized).introspect(connection, target);
    } catch (SQLException e) {
      throw new SqlMaskException(SqlMaskException.Code.INTROSPECT_ERROR,
          "udf introspection failed: " + sanitize(e.getMessage()), e);
    }
    Map<String, List<UdfIntrospector.UdfSignature>> byName = new TreeMap<>();
    for (UdfIntrospector.UdfSignature signature : signatures) {
      if (signature.paramTypes().isEmpty()) {
        continue; // a masking UDF must bind the column value; registry rejects zero-param shapes
      }
      byName.computeIfAbsent(signature.name(), k -> new ArrayList<>()).add(signature);
    }
    return byName;
  }

  private static UdfDefinition toDefinition(String name, List<UdfIntrospector.UdfSignature> signatures) {
    List<UdfDefinition.UdfSignature> shapes = new ArrayList<>();
    for (UdfIntrospector.UdfSignature signature : signatures) {
      shapes.add(new UdfDefinition.UdfSignature(signature.paramTypes(), signature.returnType()));
    }
    return new UdfDefinition(name, List.copyOf(shapes), SOURCE_IMPORTED, Instant.now());
  }

  /** Canonical overload shape "type,type->type" — order-insensitive comparison key. */
  private static Set<String> shapesOf(List<UdfIntrospector.UdfSignature> signatures) {
    Set<String> shapes = new LinkedHashSet<>();
    for (UdfIntrospector.UdfSignature signature : signatures) {
      shapes.add(String.join(",", signature.paramTypes()) + "->" + signature.returnType());
    }
    return shapes;
  }

  private static Set<String> shapesOf(UdfDefinition udf) {
    Set<String> shapes = new LinkedHashSet<>();
    for (UdfDefinition.UdfSignature signature : udf.signatures()) {
      shapes.add(String.join(",", signature.params()) + "->" + signature.returns());
    }
    return shapes;
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
