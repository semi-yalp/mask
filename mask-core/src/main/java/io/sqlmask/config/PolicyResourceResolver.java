package io.sqlmask.config;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyResource;

import java.util.List;

/**
 * Fail-closed resource resolution: every policy resource must match at least
 * one declared table (and a concrete column must exist on one of the matched
 * tables). A policy that silently matches nothing is a configuration error —
 * a typo would otherwise disable protection unnoticed.
 */
public final class PolicyResourceResolver {

  private final LoadedConfig loaded;

  public PolicyResourceResolver(LoadedConfig loaded) {
    this.loaded = loaded;
  }

  public void validate(List<Policy> policies) {
    for (Policy policy : policies) {
      for (PolicyResource resource : policy.resources()) {
        List<TableMetadata> tables = matchingTables(resource);
        if (tables.isEmpty()) {
          throw error("policy '" + policy.name() + "': resource '" + describe(resource)
              + "' matches no declared table");
        }
        if (resource.column() != null && !"*".equals(resource.column())) {
          boolean columnDeclared = tables.stream().anyMatch(t -> hasColumn(t, resource.column()));
          if (!columnDeclared) {
            throw error("policy '" + policy.name() + "': resource column '"
                + resource.column() + "' is not declared on any matched table of '"
                + describe(resource) + "'");
          }
        }
      }
    }
  }

  private List<TableMetadata> matchingTables(PolicyResource resource) {
    return loaded.tables().stream()
        .filter(t -> levelMatches(resource.catalog(), t.catalog(), "catalog")
            && levelMatches(resource.schema(), t.schema(), "schema")
            && levelMatches(resource.table(), t.name(), "table"))
        .toList();
  }

  private boolean hasColumn(TableMetadata table, String column) {
    return table.columns().stream()
        .anyMatch(c -> ColumnKey.normalize(c.name(), "column").equals(column));
  }

  private static boolean levelMatches(String pattern, String declared, String part) {
    return "*".equals(pattern)
        || pattern.equals(ColumnKey.normalize(declared, part));
  }

  private static String describe(PolicyResource resource) {
    return resource.catalog() + "." + resource.schema() + "." + resource.table();
  }

  private static SqlMaskException error(String message) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, message);
  }
}
