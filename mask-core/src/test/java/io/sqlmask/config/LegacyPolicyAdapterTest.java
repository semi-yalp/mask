package io.sqlmask.config;

import io.sqlmask.policy.match.PolicyEngine;
import io.sqlmask.policy.match.PolicyIndex;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.PolicyType;
import io.sqlmask.policy.model.Subject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyPolicyAdapterTest {

  private static final String LEGACY_YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            rowFilter: "status = 'active'"
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
              - {name: status, type: varchar}
          - catalog: crm
            schema: public
            name: orders
            columns:
              - {name: id, type: bigint}

      columns:
        - {catalog: crm, schema: public, table: customer, column: phone, policy: phone_mask}

      policies:
        phone_mask:
          udf: mask_phone
          arguments: [3, 4]
      """;

  @Test
  void convertedPoliciesReproduceLegacyLookups() {
    LoadedConfig loaded = new YamlConfigLoader().loadContent(LEGACY_YAML, "metadata.yaml");
    List<Policy> policies = LegacyPolicyAdapter.convert(loaded.config());
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(policies));
    Subject anonymous = Subject.anonymous();

    // 掩码：精确列命中，udf/参数与旧定义一致
    var mask = engine.maskFor("crm", "public", "customer", "phone", anonymous).orElseThrow();
    assertEquals("mask_phone", mask.udf());
    assertEquals(List.of(3, 4), mask.arguments());
    assertTrue(engine.maskFor("crm", "public", "customer", "id", anonymous).isEmpty());

    // 行过滤：带 rowFilter 的表命中且表达式一致；无 rowFilter 的表不命中
    var hits = engine.rowFiltersFor("crm", "public", "customer", anonymous);
    assertEquals(1, hits.size());
    assertEquals("status = 'active'", hits.get(0).expr());
    assertTrue(engine.rowFiltersFor("crm", "public", "orders", anonymous).isEmpty());

    // 策略名约定（错误消息前缀用）
    assertEquals("phone_mask", mask.policyName());
  }

  @Test
  void convertedRowFilterPolicyIsNamedAfterTable() {
    LoadedConfig loaded = new YamlConfigLoader().loadContent(LEGACY_YAML, "metadata.yaml");
    List<Policy> policies = LegacyPolicyAdapter.convert(loaded.config());
    assertEquals("rowFilter:crm.public.customer", policies.stream()
        .filter(p -> p.type() == PolicyType.ROW_FILTER).findFirst().orElseThrow().name());
  }
}
