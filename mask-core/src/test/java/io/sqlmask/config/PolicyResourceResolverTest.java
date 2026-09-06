package io.sqlmask.config;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyResource;
import io.sqlmask.policy.model.PolicyType;
import io.sqlmask.policy.model.RowFilterItem;
import io.sqlmask.policy.model.SubjectSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyResourceResolverTest {

  private static final String METADATA_YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
      policies: {}
      """;

  private LoadedConfig loaded() {
    return new YamlConfigLoader().loadContent(METADATA_YAML, "metadata.yaml");
  }

  private static Policy rowFilterPolicy(String catalog, String schema, String table) {
    return new Policy("f", true, 0, PolicyType.ROW_FILTER,
        List.of(PolicyResource.table(catalog, schema, table)), List.of(),
        List.of(new RowFilterItem(new SubjectSelector(Set.of(), Set.of("*")), "id > 0")));
  }

  @Test
  void unknownTableIsRejectedWithPolicyPrefix() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new PolicyResourceResolver(loaded()).validate(
            List.of(rowFilterPolicy("crm", "public", "nowhere"))));
    assertTrue(e.getMessage().contains("policy 'f':"));
    assertTrue(e.getMessage().contains("matches no declared table"));
    assertTrue(e.getCode() == SqlMaskException.Code.CONFIG_ERROR);
  }

  @Test
  void wildcardCatalogResolvesWhenAnyTableIsDeclared() {
    new PolicyResourceResolver(loaded()).validate(
        List.of(rowFilterPolicy("*", "public", "customer")));
  }

  @Test
  void unknownColumnIsRejected() {
    Policy mask = new Policy("m", true, 0, PolicyType.DATA_MASK,
        List.of(PolicyResource.column("crm", "public", "customer", "email")),
        List.of(new DataMaskItem(
            new SubjectSelector(Set.of(), Set.of("*")), "mask_email", List.of())),
        List.of());
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new PolicyResourceResolver(loaded()).validate(List.of(mask)));
    assertTrue(e.getMessage().contains("column 'email'"));
  }

  @Test
  void starColumnExpandsToAllDeclaredColumns() {
    Policy mask = new Policy("m", true, 0, PolicyType.DATA_MASK,
        List.of(PolicyResource.column("crm", "public", "customer", "*")),
        List.of(new DataMaskItem(
            new SubjectSelector(Set.of(), Set.of("*")), "mask_all", List.of())),
        List.of());
    new PolicyResourceResolver(loaded()).validate(List.of(mask));
  }
}
