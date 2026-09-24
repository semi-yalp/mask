package io.sqlmask.policy.store;

import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyResource;
import io.sqlmask.policy.model.PolicyType;
import io.sqlmask.policy.model.RowFilterItem;
import io.sqlmask.policy.model.SubjectSelector;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializes policies to the same Ranger-style {@code policies.yaml} document
 * {@link PolicyYamlLoader} reads — a symmetric inverse of the loader.
 * Emission is deterministic: fixed key order, subject sets as lists, empty
 * users/groups/arguments keys omitted, and scalars quoted by SnakeYAML so no
 * value (a {@code *} user, a numeric-looking string argument, a {@code #} in
 * filterExpr) can change meaning on reload.
 */
public final class PolicyYamlWriter {

  private final Yaml yaml = new Yaml();

  public String write(List<Policy> policies) {
    Map<String, Object> root = new LinkedHashMap<>();
    List<Object> policyNodes = new ArrayList<>(policies.size());
    for (Policy policy : policies) {
      policyNodes.add(toNode(policy));
    }
    root.put("policies", policyNodes);
    return yaml.dump(root);
  }

  private static Map<String, Object> toNode(Policy policy) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("name", policy.name());
    node.put("enabled", policy.enabled());
    node.put("priority", policy.priority());
    node.put("resources", resources(policy));
    if (policy.type() == PolicyType.DATA_MASK) {
      List<Object> items = new ArrayList<>(policy.dataMaskItems().size());
      for (DataMaskItem item : policy.dataMaskItems()) {
        Map<String, Object> itemNode = selectorNode(item.selector());
        itemNode.put("udf", item.udf());
        if (!item.arguments().isEmpty()) {
          itemNode.put("arguments", new ArrayList<>(item.arguments()));
        }
        items.add(itemNode);
      }
      node.put("dataMaskItems", items);
    } else {
      List<Object> items = new ArrayList<>(policy.rowFilterItems().size());
      for (RowFilterItem item : policy.rowFilterItems()) {
        Map<String, Object> itemNode = selectorNode(item.selector());
        itemNode.put("filterExpr", item.filterExpr());
        items.add(itemNode);
      }
      node.put("rowFilterItems", items);
    }
    return node;
  }

  private static List<Object> resources(Policy policy) {
    List<Object> resources = new ArrayList<>(policy.resources().size());
    for (PolicyResource resource : policy.resources()) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("catalog", resource.catalog());
      node.put("schema", resource.schema());
      node.put("table", resource.table());
      if (resource.column() != null) {
        node.put("column", new ArrayList<>(List.of(resource.column())));
      }
      resources.add(node);
    }
    return resources;
  }

  private static Map<String, Object> selectorNode(SubjectSelector selector) {
    Map<String, Object> node = new LinkedHashMap<>();
    if (!selector.users().isEmpty()) {
      node.put("users", new ArrayList<>(selector.users()));
    }
    if (!selector.groups().isEmpty()) {
      node.put("groups", new ArrayList<>(selector.groups()));
    }
    return node;
  }
}