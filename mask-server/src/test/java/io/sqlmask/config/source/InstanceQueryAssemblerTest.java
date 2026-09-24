package io.sqlmask.config.source;

import io.sqlmask.config.LoadedConfig;
import io.sqlmask.common.metadata.MetadataClient;
import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstanceQueryAssemblerTest {

  private final InstanceQueryAssembler assembler = new InstanceQueryAssembler();

  private static ConfigSource.ResolvedConfig effective(String dialect, String rowFilter) {
    // 用 YAML 走一遍既有装载器构造生效配置（等价于 policy service 下发后的形态）
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              %s
              columns:
                - { name: id, type: bigint }
                - { name: phone, type: varchar }
        columns:
          - { catalog: crm, schema: public, table: customer, column: phone, policy: m }
        policies:
          m: { udf: mask_phone, arguments: [3, 4] }
        """.formatted(rowFilter == null ? "" : "rowFilter: \"" + rowFilter + "\"");
    LoadedConfig loaded = new io.sqlmask.config.YamlConfigLoader().loadContent(
        yaml, "metadata.yaml", dialect);
    return new ConfigSource.ResolvedConfig(loaded, dialect);
  }

  private static MetadataClient.MetadataSnapshot snapshot(String dialect) {
    return new MetadataClient.MetadataSnapshot("pg_prod", dialect, 7,
        List.of(new MetadataClient.TableSnapshot("crm", "public", "customer",
                List.of(new MetadataClient.ColumnSnapshot("id", "bigint"),
                    new MetadataClient.ColumnSnapshot("phone", "varchar"))),
            // 快照里多出一张新表（生效配置尚未导入）
            new MetadataClient.TableSnapshot("crm", "public", "fresh",
                List.of(new MetadataClient.ColumnSnapshot("id", "bigint")))));
  }

  @Test
  void mergesFreshTablesWithEffectiveRowFilterAndPolicies() {
    LoadedConfig merged = assembler.assemble(snapshot("postgresql"),
        effective("postgresql", "status = 'active'"));
    assertThat(merged.tables()).extracting(t -> t.name()).containsExactly("customer", "fresh");
    assertThat(merged.findTable("crm", "public", "customer").orElseThrow().rowFilter())
        .isEqualTo("status = 'active'");
    assertThat(merged.findTable("crm", "public", "fresh").orElseThrow().rowFilter()).isNull();
    // 策略与列绑定原样保留
    assertThat(merged.config().policies()).containsKey("m");
    assertThat(merged.config().columnPolicies()).hasSize(1);
  }

  @Test
  void rejectsDialectMismatch() {
    assertThatThrownBy(() -> assembler.assemble(snapshot("mysql"), effective("postgresql", null)))
        .isInstanceOf(SqlMaskException.class)
        .hasMessageContaining("dialect");
  }
}
