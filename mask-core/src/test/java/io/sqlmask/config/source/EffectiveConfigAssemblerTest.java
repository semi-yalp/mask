package io.sqlmask.config.source;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.rewrite.RewriteEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectiveConfigAssemblerTest {

  private EffectiveConfigResponse sample() {
    return new EffectiveConfigResponse("pg_prod", "postgresql", 42,
        new EffectiveConfigResponse.PolicySummary(1, 0),
        new EffectiveConfigResponse.ConfigPayload(
            new EffectiveConfigResponse.MetadataPayload(List.of(
                new EffectiveConfigResponse.TablePayload("crm", "public", "customer", null,
                    List.of(new EffectiveConfigResponse.ColumnPayload("phone", "varchar"),
                        new EffectiveConfigResponse.ColumnPayload("amount", "decimal(10,2)"))))),
            List.of(new EffectiveConfigResponse.ColumnBinding(
                "crm", "public", "customer", "phone", "phone_mask")),
            Map.of("phone_mask", new EffectiveConfigResponse.UdfDefinition("mask_phone", List.of(3, 4)))));
  }

  @Test
  void assemblesTypesBindingsAndRegistry() {
    LoadedConfig loaded = new EffectiveConfigAssembler().assemble(sample());
    assertEquals(1, loaded.tables().size());
    assertEquals("decimal(10,2)",
        loaded.tables().get(0).columns().get(1).typeDeclaration());
    assertTrue(loaded.policyRegistry().find(
        io.sqlmask.metadata.ColumnKey.of("CRM", "PUBLIC", "CUSTOMER", "PHONE")).isPresent());
  }

  @Test
  void rejectsUnknownPolicyBinding() {
    // ColumnBinding 引用了不存在的策略（其余与 sample 完全一致）
    EffectiveConfigResponse.ConfigPayload badPayload = new EffectiveConfigResponse.ConfigPayload(
        sample().config().metadata(),
        List.of(new EffectiveConfigResponse.ColumnBinding(
            "crm", "public", "customer", "phone", "no_such_policy")),
        sample().config().policies());
    EffectiveConfigResponse bad = new EffectiveConfigResponse("pg_prod", "postgresql", 42,
        new EffectiveConfigResponse.PolicySummary(0, 0), badPayload);
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new EffectiveConfigAssembler().assemble(bad));
    // ColumnBinding 引用了不存在的策略
    assertEquals(io.sqlmask.error.SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  @Test
  void rejectsUnknownTypeWithDialectContext() {
    EffectiveConfigResponse bad = new EffectiveConfigResponse("trino_prod", "trino", 1,
        new EffectiveConfigResponse.PolicySummary(0, 0),
        new EffectiveConfigResponse.ConfigPayload(
            new EffectiveConfigResponse.MetadataPayload(List.of(
                new EffectiveConfigResponse.TablePayload("crm", "public", "t", null,
                    List.of(new EffectiveConfigResponse.ColumnPayload("a", "datetime"))))),
            List.of(), Map.of()));
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new EffectiveConfigAssembler().assemble(bad));
    assertTrue(e.getMessage().contains("trino"));
  }

  @Test
  void engineOverloadMatchesYamlPath() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - { name: phone, type: varchar }
        columns:
          - { catalog: crm, schema: public, table: customer, column: phone, policy: phone_mask }
        policies:
          phone_mask: { udf: mask_phone, arguments: [3, 4] }
        """;
    String sql = "SELECT phone FROM customer;";
    var fromYaml = new RewriteEngine().rewrite(yaml, sql, "postgresql");
    LoadedConfig loaded = new EffectiveConfigAssembler().assemble(sample());
    var fromLoaded = new RewriteEngine().rewrite(loaded, sql, "postgresql");
    assertEquals(fromYaml, fromLoaded);
  }
}
