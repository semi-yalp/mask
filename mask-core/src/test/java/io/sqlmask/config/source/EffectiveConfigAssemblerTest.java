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
    // policy lookups go through the PDP over the converted legacy policies
    var instruction = new io.sqlmask.policy.match.PolicyEngine(io.sqlmask.policy.match.PolicyIndex.of(
        io.sqlmask.config.LegacyPolicyAdapter.convert(loaded.config())))
        .maskFor("CRM", "PUBLIC", "CUSTOMER", "PHONE", io.sqlmask.policy.model.Subject.anonymous());
    assertTrue(instruction.isPresent());
    assertEquals("mask_phone", instruction.orElseThrow().udf());
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

  // ---------- fix round: boundary validation aligned with the YAML loader ----------

  private EffectiveConfigResponse response(EffectiveConfigResponse.ConfigPayload config) {
    return new EffectiveConfigResponse("pg_prod", "postgresql", 42,
        new EffectiveConfigResponse.PolicySummary(1, 0), config);
  }

  private EffectiveConfigResponse.ConfigPayload configWithTables(
      List<EffectiveConfigResponse.TablePayload> tables) {
    return new EffectiveConfigResponse.ConfigPayload(
        new EffectiveConfigResponse.MetadataPayload(tables), List.of(), Map.of());
  }

  private SqlMaskException assertConfigError(EffectiveConfigResponse bad) {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new EffectiveConfigAssembler().assemble(bad));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().startsWith("policy-instance 'pg_prod'"),
        () -> "message should carry the instance prefix: " + e.getMessage());
    return e;
  }

  @Test
  void rejectsZeroColumnTable() {
    EffectiveConfigResponse bad = response(configWithTables(List.of(
        new EffectiveConfigResponse.TablePayload("crm", "public", "customer", null, List.of()))));
    SqlMaskException e = assertConfigError(bad);
    assertTrue(e.getMessage().contains("at least one column"), () -> e.getMessage());
  }

  @Test
  void rejectsBlankAndEmptyTableIdentifiersWithConfigError() {
    for (String blankName : new String[] {"   ", ""}) {
      EffectiveConfigResponse bad = response(configWithTables(List.of(
          new EffectiveConfigResponse.TablePayload("crm", "public", blankName, null,
              List.of(new EffectiveConfigResponse.ColumnPayload("phone", "varchar"))))));
      SqlMaskException e = assertConfigError(bad);
      assertTrue(e.getMessage().contains(".name"), () -> e.getMessage());
    }
  }

  @Test
  void rejectsNullTableIdentifiersWithConfigError() {
    // catalog / schema / name 缺失
    for (String missing : new String[] {"catalog", "schema", "name"}) {
      EffectiveConfigResponse bad = response(configWithTables(List.of(
          new EffectiveConfigResponse.TablePayload(
              "catalog".equals(missing) ? null : "crm",
              "schema".equals(missing) ? null : "public",
              "name".equals(missing) ? null : "customer",
              null,
              List.of(new EffectiveConfigResponse.ColumnPayload("phone", "varchar"))))));
      SqlMaskException e = assertConfigError(bad);
      assertTrue(e.getMessage().contains("." + missing), () -> e.getMessage());
    }
  }

  @Test
  void rejectsNullBindingFieldsWithConfigError() {
    // binding 的 catalog / schema / table / column 缺失（policy 缺失见 rejectsNullRequiredFields）
    for (String missing : new String[] {"catalog", "schema", "table", "column"}) {
      EffectiveConfigResponse.ConfigPayload config = new EffectiveConfigResponse.ConfigPayload(
          sample().config().metadata(),
          List.of(new EffectiveConfigResponse.ColumnBinding(
              "catalog".equals(missing) ? null : "crm",
              "schema".equals(missing) ? null : "public",
              "table".equals(missing) ? null : "customer",
              "column".equals(missing) ? null : "phone",
              "phone_mask")),
          sample().config().policies());
      SqlMaskException e = assertConfigError(response(config));
      assertTrue(e.getMessage().contains("." + missing), () -> e.getMessage());
    }
  }

  @Test
  void rejectsBlankColumnNameWithConfigError() {
    EffectiveConfigResponse bad = response(configWithTables(List.of(
        new EffectiveConfigResponse.TablePayload("crm", "public", "customer", null,
            List.of(new EffectiveConfigResponse.ColumnPayload("  ", "varchar"))))));
    SqlMaskException e = assertConfigError(bad);
    assertTrue(e.getMessage().contains(".name"), () -> e.getMessage());
  }

  @Test
  void rejectsBlankBindingFieldWithConfigError() {
    EffectiveConfigResponse.ConfigPayload config = new EffectiveConfigResponse.ConfigPayload(
        sample().config().metadata(),
        List.of(new EffectiveConfigResponse.ColumnBinding(
            "crm", "public", "customer", " ", "phone_mask")),
        sample().config().policies());
    SqlMaskException e = assertConfigError(response(config));
    assertTrue(e.getMessage().contains(".column"), () -> e.getMessage());
  }

  @Test
  void rejectsNullTopLevelStructuresWithConfigError() {
    EffectiveConfigResponse.PolicySummary summary = new EffectiveConfigResponse.PolicySummary(1, 0);
    // config 缺失
    assertConfigError(new EffectiveConfigResponse("pg_prod", "postgresql", 42, summary, null));
    // config.metadata 缺失
    assertConfigError(new EffectiveConfigResponse("pg_prod", "postgresql", 42, summary,
        new EffectiveConfigResponse.ConfigPayload(null, List.of(), Map.of())));
    // config.metadata.tables 缺失
    assertConfigError(new EffectiveConfigResponse("pg_prod", "postgresql", 42, summary,
        new EffectiveConfigResponse.ConfigPayload(
            new EffectiveConfigResponse.MetadataPayload(null), List.of(), Map.of())));
    // config.columns 缺失
    assertConfigError(new EffectiveConfigResponse("pg_prod", "postgresql", 42, summary,
        new EffectiveConfigResponse.ConfigPayload(sample().config().metadata(), null,
            sample().config().policies())));
    // config.policies 缺失
    assertConfigError(new EffectiveConfigResponse("pg_prod", "postgresql", 42, summary,
        new EffectiveConfigResponse.ConfigPayload(sample().config().metadata(),
            sample().config().columns(), null)));
  }

  @Test
  void rejectsNullRequiredFieldsWithConfigError() {
    // table.columns 缺失
    assertConfigError(response(configWithTables(List.of(
        new EffectiveConfigResponse.TablePayload("crm", "public", "customer", null, null)))));
    // column.name 缺失
    assertConfigError(response(configWithTables(List.of(
        new EffectiveConfigResponse.TablePayload("crm", "public", "customer", null,
            List.of(new EffectiveConfigResponse.ColumnPayload(null, "varchar")))))));
    // column.type 缺失
    assertConfigError(response(configWithTables(List.of(
        new EffectiveConfigResponse.TablePayload("crm", "public", "customer", null,
            List.of(new EffectiveConfigResponse.ColumnPayload("phone", null)))))));
    // binding.policy 缺失
    assertConfigError(response(new EffectiveConfigResponse.ConfigPayload(
        sample().config().metadata(),
        List.of(new EffectiveConfigResponse.ColumnBinding(
            "crm", "public", "customer", "phone", null)),
        sample().config().policies())));
    // policy.udf 缺失
    assertConfigError(response(new EffectiveConfigResponse.ConfigPayload(
        sample().config().metadata(),
        sample().config().columns(),
        java.util.Collections.singletonMap("phone_mask",
            new EffectiveConfigResponse.UdfDefinition(null, List.of(3, 4))))));
    // policy 定义本身缺失
    assertConfigError(response(new EffectiveConfigResponse.ConfigPayload(
        sample().config().metadata(),
        sample().config().columns(),
        java.util.Collections.singletonMap("phone_mask", null))));
  }

  @Test
  void treatsMissingArgumentsAsEmptyList() {
    EffectiveConfigResponse.ConfigPayload config = new EffectiveConfigResponse.ConfigPayload(
        sample().config().metadata(),
        sample().config().columns(),
        java.util.Collections.singletonMap("phone_mask",
            new EffectiveConfigResponse.UdfDefinition("mask_phone", null)));
    LoadedConfig loaded = new EffectiveConfigAssembler().assemble(response(config));
    assertEquals(List.of(), loaded.policyRegistry()
        .find(io.sqlmask.metadata.ColumnKey.of("crm", "public", "customer", "phone"))
        .orElseThrow()
        .arguments());
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
