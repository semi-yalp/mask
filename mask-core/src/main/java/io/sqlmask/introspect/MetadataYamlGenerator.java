package io.sqlmask.introspect;

import java.util.Comparator;
import java.util.List;

/**
 * Deterministically renders an {@link IntrospectionResult} as metadata YAML:
 * hand-assembled (same shape as the web editor's generateYaml) so the same
 * database always yields byte-identical output. Skeleton only — policies and
 * row filters are authored by hand afterwards.
 */
public final class MetadataYamlGenerator {

  public String generate(IntrospectionResult result) {
    StringBuilder out = new StringBuilder();
    out.append("metadata:\n");
    List<IntrospectionResult.TableInfo> tables = result.tables().stream()
        .sorted(Comparator.comparing(IntrospectionResult.TableInfo::catalog)
            .thenComparing(IntrospectionResult.TableInfo::schema)
            .thenComparing(IntrospectionResult.TableInfo::name))
        .toList();
    if (tables.isEmpty()) {
      out.append("  tables: []\n");
    } else {
      out.append("  tables:\n");
      for (IntrospectionResult.TableInfo table : tables) {
        out.append("    - catalog: ").append(scalar(table.catalog())).append('\n');
        out.append("      schema: ").append(scalar(table.schema())).append('\n');
        out.append("      name: ").append(scalar(table.name())).append('\n');
        out.append("      columns:\n");
        for (IntrospectionResult.ColumnInfo column : table.columns()) {
          out.append("        - name: ").append(scalar(column.name())).append('\n');
          out.append("          type: ").append(scalar(column.yamlType())).append('\n');
        }
      }
    }
    out.append("policies: {}");
    return out.toString();
  }

  /** Plain scalar for safe tokens, double-quoted JSON escape otherwise. */
  private String scalar(String value) {
    if (value != null && value.matches("[A-Za-z0-9_.$(),-]+")) {
      return value;
    }
    StringBuilder escaped = new StringBuilder("\"");
    for (int i = 0; i < (value == null ? 0 : value.length()); i++) {
      char c = value.charAt(i);
      escaped.append(switch (c) {
        case '"' -> "\\\"";
        case '\\' -> "\\\\";
        case '\n' -> "\\n";
        case '\t' -> "\\t";
        case '\r' -> "\\r";
        default -> c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c);
      });
    }
    return escaped.append('"').toString();
  }
}
