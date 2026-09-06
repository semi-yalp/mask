package io.sqlmask.policy.store;

import io.sqlmask.policy.PolicyException;
import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyResource;
import io.sqlmask.policy.model.PolicyType;
import io.sqlmask.policy.model.RowFilterItem;
import io.sqlmask.policy.model.SubjectSelector;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses the policies.yaml document into validated policies. Every failure is
 * a {@link PolicyException} whose message starts with the YAML path of the
 * offending node (for example "policies.yaml: policies[0].dataMaskItems[1]").
 */
public final class PolicyYamlLoader {

  public List<Policy> parse(String yaml, String sourceName) {
    Object root;
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      root = new Yaml(new SafeConstructor(options)).load(yaml);
    } catch (YAMLException e) {
      throw new PolicyException(sourceName + ": invalid YAML: " + e.getMessage(), e);
    }
    if (!(root instanceof Map<?, ?> rootMap)) {
      throw new PolicyException(sourceName + ": root must be a mapping with a 'policies' key");
    }
    Object policiesNode = rootMap.get("policies");
    if (!(policiesNode instanceof List<?> policyList)) {
      throw new PolicyException(
          sourceName + ": 'policies' must be a list (use policies: [] for none)");
    }
    List<Policy> policies = new ArrayList<>();
    Set<String> seenNames = new LinkedHashSet<>();
    for (int i = 0; i < policyList.size(); i++) {
      policies.add(loadPolicy(policyList.get(i), sourceName + ": policies[" + i + "]", seenNames));
    }
    return policies;
  }

  private Policy loadPolicy(Object node, String path, Set<String> seenNames) {
    if (!(node instanceof Map<?, ?> map)) {
      throw new PolicyException(path + " must be a mapping");
    }
    String name = requiredString(map, "name", path);
    if (!seenNames.add(name)) {
      throw new PolicyException(path + ": duplicate policy name '" + name + "'");
    }
    boolean enabled = map.get("enabled") == null
        ? true
        : requireType(map.get("enabled"), Boolean.class, path + ".enabled", "boolean");
    int priority = map.get("priority") == null
        ? 0
        : requireType(map.get("priority"), Integer.class, path + ".priority", "integer");

    Object resourcesNode = map.get("resources");
    if (!(resourcesNode instanceof List<?> resourceList) || resourceList.isEmpty()) {
      throw new PolicyException(path + ".resources must be a non-empty list");
    }
    List<PolicyResource> resources = new ArrayList<>();
    for (int i = 0; i < resourceList.size(); i++) {
      resources.addAll(loadResource(resourceList.get(i), path + ".resources[" + i + "]"));
    }

    boolean hasMask = map.containsKey("dataMaskItems");
    boolean hasFilter = map.containsKey("rowFilterItems");
    if (hasMask == hasFilter) {
      throw new PolicyException(
          path + ": exactly one of dataMaskItems / rowFilterItems is required");
    }
    if (hasMask) {
      for (PolicyResource resource : resources) {
        if (resource.column() == null) {
          throw new PolicyException(path
              + ".resources: dataMask resources must declare a column level (use \"*\" for all columns)");
        }
      }
      return new Policy(name, enabled, priority, PolicyType.DATA_MASK, resources,
          loadDataMaskItems(map.get("dataMaskItems"), path + ".dataMaskItems"), List.of());
    }
    for (PolicyResource resource : resources) {
      if (resource.column() != null) {
        throw new PolicyException(
            path + ".resources: rowFilter resources are table-level; remove the column level");
      }
    }
    return new Policy(name, enabled, priority, PolicyType.ROW_FILTER, resources, List.of(),
        loadRowFilterItems(map.get("rowFilterItems"), path + ".rowFilterItems"));
  }

  private List<PolicyResource> loadResource(Object node, String path) {
    if (!(node instanceof Map<?, ?> map)) {
      throw new PolicyException(path + " must be a mapping");
    }
    String catalog = requiredString(map, "catalog", path);
    String schema = requiredString(map, "schema", path);
    String table = requiredString(map, "table", path);
    Object columnNode = map.get("column");
    if (columnNode == null) {
      return List.of(new PolicyResource(catalog, schema, table, null));
    }
    if (columnNode instanceof String column) {
      return List.of(new PolicyResource(catalog, schema, table, column));
    }
    if (columnNode instanceof List<?> columns) {
      if (columns.isEmpty()) {
        throw new PolicyException(path + ".column must not be an empty list");
      }
      List<PolicyResource> expanded = new ArrayList<>();
      for (int i = 0; i < columns.size(); i++) {
        if (!(columns.get(i) instanceof String column) || column.isBlank()) {
          throw new PolicyException(
              path + ".column[" + i + "] must be a non-blank string or \"*\"");
        }
        expanded.add(new PolicyResource(catalog, schema, table, column));
      }
      return expanded;
    }
    throw new PolicyException(path + ".column must be a string, a list of strings or \"*\"");
  }

  private List<DataMaskItem> loadDataMaskItems(Object node, String path) {
    if (!(node instanceof List<?> list) || list.isEmpty()) {
      throw new PolicyException(path + " must be a non-empty list");
    }
    List<DataMaskItem> items = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      String itemPath = path + "[" + i + "]";
      if (!(list.get(i) instanceof Map<?, ?> map)) {
        throw new PolicyException(itemPath + " must be a mapping");
      }
      SubjectSelector selector = loadSelector(map, itemPath);
      String udf = requiredString(map, "udf", itemPath);
      List<Object> arguments = List.of();
      if (map.containsKey("arguments")) {
        Object argumentsNode = map.get("arguments");
        if (!(argumentsNode instanceof List<?> argumentList)) {
          throw new PolicyException(itemPath + ".arguments must be a list");
        }
        for (int j = 0; j < argumentList.size(); j++) {
          Object argument = argumentList.get(j);
          if (argument == null || argument instanceof Map<?, ?> || argument instanceof List<?>) {
            throw new PolicyException(itemPath + ".arguments[" + j
                + "] must be a scalar (string, number or boolean)");
          }
        }
        arguments = List.copyOf(argumentList);
      }
      items.add(new DataMaskItem(selector, udf, arguments));
    }
    return items;
  }

  private List<RowFilterItem> loadRowFilterItems(Object node, String path) {
    if (!(node instanceof List<?> list) || list.isEmpty()) {
      throw new PolicyException(path + " must be a non-empty list");
    }
    List<RowFilterItem> items = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      String itemPath = path + "[" + i + "]";
      if (!(list.get(i) instanceof Map<?, ?> map)) {
        throw new PolicyException(itemPath + " must be a mapping");
      }
      SubjectSelector selector = loadSelector(map, itemPath);
      items.add(new RowFilterItem(selector, requiredString(map, "filterExpr", itemPath)));
    }
    return items;
  }

  private SubjectSelector loadSelector(Map<?, ?> map, String path) {
    Set<String> users = stringSet(map.get("users"), path + ".users");
    Set<String> groups = stringSet(map.get("groups"), path + ".groups");
    if (users.isEmpty() && groups.isEmpty()) {
      throw new PolicyException(
          path + ": users/groups: at least one subject is required (use [\"*\"] for everyone)");
    }
    return new SubjectSelector(users, groups);
  }

  private Set<String> stringSet(Object node, String path) {
    if (node == null) {
      return Set.of();
    }
    if (!(node instanceof List<?> list)) {
      throw new PolicyException(path + " must be a list of strings");
    }
    Set<String> values = new LinkedHashSet<>();
    for (int i = 0; i < list.size(); i++) {
      if (!(list.get(i) instanceof String s) || s.isBlank()) {
        throw new PolicyException(path + "[" + i + "] must be a non-blank string");
      }
      values.add(s);
    }
    return values;
  }

  private String requiredString(Map<?, ?> map, String key, String path) {
    Object value = map.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new PolicyException(path + "." + key + ": required non-blank string is missing");
    }
    return s;
  }

  private <T> T requireType(Object value, Class<T> type, String path, String label) {
    if (!type.isInstance(value)) {
      throw new PolicyException(path + " must be a " + label);
    }
    return type.cast(value);
  }
}
