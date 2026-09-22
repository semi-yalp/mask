package io.masklite.config;

import io.masklite.dialect.PostgresqlTypeResolver;
import io.masklite.dialect.TypeResolver;
import io.masklite.error.SqlMaskException;
import io.masklite.metadata.ColumnKey;
import io.masklite.metadata.TableMetadata;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads and semantically validates the metadata YAML. All errors are reported
 * with the offending YAML path (for example {@code columns[0].policy})
 * instead of generic parser messages.
 */
public final class YamlConfigLoader {

  private final TypeResolver typeResolver;

  /** Interprets column type declarations in the default (PostgreSQL) vocabulary. */
  public YamlConfigLoader() {
    this(new PostgresqlTypeResolver());
  }

  /** Interprets column type declarations in the vocabulary of {@code typeResolver}. */
  public YamlConfigLoader(TypeResolver typeResolver) {
    this.typeResolver = typeResolver;
  }

  /** Loads and validates the YAML file at {@code path}. */
  public MaskingConfig load(Path path) {
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      return load(reader, path.toString());
    } catch (IOException e) {
      throw new SqlMaskException(SqlMaskException.Code.IO_ERROR,
          "cannot read metadata file '" + path + "': " + e.getMessage(), e);
    }
  }

  /** Parses YAML content from a string; {@code sourceName} is used in error messages. */
  public MaskingConfig loadContent(String yamlContent, String sourceName) {
    return load(new java.io.StringReader(yamlContent), sourceName);
  }

  private MaskingConfig load(Reader reader, String sourceName) {
    Object root = parse(reader, sourceName);
    requireMapping(root, sourceName + ": root must be a mapping");
    Map<?, ?> rootMap = (Map<?, ?>) root;

    List<TableMetadata> tables = loadTables(rootMap, sourceName, typeResolver);
    Map<String, MaskingPolicy> policies = loadPolicies(rootMap, sourceName);
    List<MaskingConfig.ColumnPolicyBinding> bindings = loadColumnBindings(rootMap, policies, sourceName);

    return new MaskingConfig(tables, bindings, policies);
  }

  private Object parse(Reader reader, String sourceName) {
    try {
      LoaderOptions loaderOptions = new LoaderOptions();
      loaderOptions.setAllowDuplicateKeys(false);
      Yaml yaml = new Yaml(new SafeConstructor(loaderOptions));
      return yaml.load(reader);
    } catch (YAMLException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          sourceName + ": invalid YAML: " + e.getMessage(), e);
    }
  }

  private List<TableMetadata> loadTables(Map<?, ?> root, String sourceName,
      TypeResolver typeResolver) {
    Object metadata = root.get("metadata");
    requireMapping(metadata, sourceName + ": 'metadata' must be a mapping");
    Map<?, ?> metadataMap = (Map<?, ?>) metadata;
    Object tablesNode = metadataMap.get("tables");
    requireList(tablesNode, sourceName + ": 'metadata.tables' must be a list");

    List<TableMetadata> tables = new ArrayList<>();
    Set<String> seenTables = new LinkedHashSet<>();
    List<?> tableList = (List<?>) tablesNode;
    for (int i = 0; i < tableList.size(); i++) {
      String tablePath = sourceName + ": metadata.tables[" + i + "]";
      requireMapping(tableList.get(i), tablePath + " must be a mapping");
      Map<?, ?> tableMap = (Map<?, ?>) tableList.get(i);
      String catalog = requiredString(tableMap, "catalog", tablePath);
      String schema = requiredString(tableMap, "schema", tablePath);
      String name = requiredString(tableMap, "name", tablePath);
      String tableKey = ColumnKey.normalize(catalog, "catalog") + "."
          + ColumnKey.normalize(schema, "schema") + "."
          + ColumnKey.normalize(name, "table");
      if (!seenTables.add(tableKey)) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            tablePath + ": duplicate table '" + catalog + "." + schema + "." + name + "'");
      }
      String rowFilter = optionalString(tableMap, "rowFilter", tablePath);
      Object columnsNode = tableMap.get("columns");
      requireList(columnsNode, tablePath + ".columns must be a list");
      List<?> columnList = (List<?>) columnsNode;
      if (columnList.isEmpty()) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            tablePath + ".columns must declare at least one column");
      }
      Set<String> seenColumns = new LinkedHashSet<>();
      List<TableMetadata.Column> columns = new ArrayList<>();
      for (int j = 0; j < columnList.size(); j++) {
        String columnPath = tablePath + ".columns[" + j + "]";
        requireMapping(columnList.get(j), columnPath + " must be a mapping");
        Map<?, ?> columnMap = (Map<?, ?>) columnList.get(j);
        String columnName = requiredString(columnMap, "name", columnPath);
        String columnType = requiredString(columnMap, "type", columnPath);
        String normalizedColumn = ColumnKey.normalize(columnName, "column");
        if (!seenColumns.add(normalizedColumn)) {
          throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
              columnPath + ": duplicate column name '" + columnName + "'");
        }
        try {
          columns.add(typeResolver.parseColumn(columnName, columnType));
        } catch (SqlMaskException e) {
          throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
              columnPath + ".type: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
          throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
              columnPath + ": " + e.getMessage(), e);
        }
      }
      tables.add(new TableMetadata(catalog, schema, name, columns, rowFilter));
    }
    return tables;
  }

  private Map<String, MaskingPolicy> loadPolicies(Map<?, ?> root, String sourceName) {
    Object policiesNode = root.get("policies");
    requireMapping(policiesNode, sourceName + ": 'policies' must be a mapping");
    Map<?, ?> policiesMap = (Map<?, ?>) policiesNode;
    Map<String, MaskingPolicy> policies = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : policiesMap.entrySet()) {
      if (!(entry.getKey() instanceof String policyName)) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            sourceName + ": policies: policy names must be strings");
      }
      String policyPath = sourceName + ": policies." + policyName;
      requireMapping(entry.getValue(), policyPath + " must be a mapping");
      Map<?, ?> policyMap = (Map<?, ?>) entry.getValue();
      String udf = requiredString(policyMap, "udf", policyPath);
      List<Object> arguments = new ArrayList<>();
      if (policyMap.containsKey("arguments")) {
        Object argumentsNode = policyMap.get("arguments");
        requireList(argumentsNode, policyPath + ".arguments must be a list");
        arguments.addAll((List<?>) argumentsNode);
      }
      MaskingPolicy.validateArguments(arguments, policyName, policyPath);
      policies.put(policyName, new MaskingPolicy(policyName, udf, arguments));
    }
    return policies;
  }

  private List<MaskingConfig.ColumnPolicyBinding> loadColumnBindings(
      Map<?, ?> root, Map<String, MaskingPolicy> policies, String sourceName) {
    Object columnsNode = root.get("columns");
    if (columnsNode == null) {
      return List.of();
    }
    requireList(columnsNode, sourceName + ": 'columns' must be a list");
    List<?> bindingList = (List<?>) columnsNode;
    List<MaskingConfig.ColumnPolicyBinding> bindings = new ArrayList<>();
    Set<String> seenKeys = new LinkedHashSet<>();
    for (int i = 0; i < bindingList.size(); i++) {
      String bindingPath = sourceName + ": columns[" + i + "]";
      requireMapping(bindingList.get(i), bindingPath + " must be a mapping");
      Map<?, ?> bindingMap = (Map<?, ?>) bindingList.get(i);
      String catalog = requiredString(bindingMap, "catalog", bindingPath);
      String schema = requiredString(bindingMap, "schema", bindingPath);
      String table = requiredString(bindingMap, "table", bindingPath);
      String column = requiredString(bindingMap, "column", bindingPath);
      String policyName = requiredString(bindingMap, "policy", bindingPath);
      if (!policies.containsKey(policyName)) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            bindingPath + ".policy: unknown policy '" + policyName + "' "
                + "(declared policies: " + policies.keySet() + ")");
      }
      ColumnKey key = ColumnKey.of(catalog, schema, table, column);
      if (!seenKeys.add(key.toString())) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            bindingPath + ": duplicate policy binding for column '" + key + "'");
      }
      bindings.add(new MaskingConfig.ColumnPolicyBinding(key, policyName));
    }
    return bindings;
  }

  private void requireMapping(Object node, String message) {
    if (!(node instanceof Map<?, ?>)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, message);
    }
  }

  private void requireList(Object node, String message) {
    if (!(node instanceof List<?>)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, message);
    }
  }

  private String requiredString(Map<?, ?> map, String key, String path) {
    Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          path + "." + key + ": required non-blank string is missing");
    }
    return s;
  }

  /** Reads an optional string key; present-but-blank is a config error, never a silent skip. */
  private String optionalString(Map<?, ?> map, String key, String path) {
    Object value = map.get(key);
    if (value == null) {
      return null;
    }
    if (!(value instanceof String s) || s.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          path + "." + key + ": must be a non-blank string when present");
    }
    return s;
  }
}
