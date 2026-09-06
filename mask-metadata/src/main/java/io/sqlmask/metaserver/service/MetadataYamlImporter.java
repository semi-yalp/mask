package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.TableStructure;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the tables section of the legacy YAML into structures. Row filters
 * are a policy-domain concern: a rowFilter field is rejected outright (never
 * silently dropped) with guidance to the policy service.
 */
@Service
public class MetadataYamlImporter {

  private final Yaml yaml = new Yaml();

  public List<TableStructure> parse(String source, String sourceName) {
    Object root = yaml.load(source);
    if (!(root instanceof Map<?, ?> rootMap)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          sourceName + ": YAML must be a mapping");
    }
    Object metadataNode = rootMap.get("metadata");
    Object tablesNode = metadataNode instanceof Map<?, ?> metadataMap
        ? metadataMap.get("tables")
        : null;
    if (!(tablesNode instanceof List<?> tableList)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          sourceName + ": 'metadata.tables' must be a list");
    }
    List<TableStructure> tables = new ArrayList<>();
    for (int i = 0; i < tableList.size(); i++) {
      tables.add(parseTable(tableList.get(i), sourceName + ": metadata.tables[" + i + "]"));
    }
    return tables;
  }

  private static TableStructure parseTable(Object node, String path) {
    if (!(node instanceof Map<?, ?> tableMap)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, path + " must be a mapping");
    }
    if (tableMap.get("rowFilter") != null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          path + ".rowFilter is not accepted here: row filters are policies now; "
              + "configure a row_filter policy on the policy service");
    }
    String catalog = requiredString(tableMap, "catalog", path);
    String schema = requiredString(tableMap, "schema", path);
    String name = requiredString(tableMap, "name", path);
    if (!(tableMap.get("columns") instanceof List<?> columnList) || columnList.isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          path + ".columns must be a non-empty list");
    }
    List<TableStructure.ColumnStructure> columns = new ArrayList<>();
    for (int j = 0; j < columnList.size(); j++) {
      Object columnNode = columnList.get(j);
      String columnPath = path + ".columns[" + j + "]";
      if (!(columnNode instanceof Map<?, ?> columnMap)) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            columnPath + " must be a mapping");
      }
      columns.add(new TableStructure.ColumnStructure(
          requiredString(columnMap, "name", columnPath),
          requiredString(columnMap, "type", columnPath)));
    }
    return new TableStructure(catalog, schema, name, columns);
  }

  private static String requiredString(Map<?, ?> map, String key, String path) {
    Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          path + "." + key + " is required");
    }
    return s;
  }
}
