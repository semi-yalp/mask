package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.TableStructure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataYamlImporterTest {

  private final MetadataYamlImporter importer = new MetadataYamlImporter();

  @Test
  void parsesMetadataTables() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - { name: id, type: bigint }
                - { name: phone, type: varchar }
        """;
    List<TableStructure> tables = importer.parse(yaml, "test.yaml");
    assertEquals(1, tables.size());
    assertEquals("crm", tables.get(0).catalog());
    assertEquals("table", tables.get(0).kind());
    assertEquals(2, tables.get(0).columns().size());
    assertEquals("varchar", tables.get(0).columns().get(1).type());
  }

  @Test
  void kindDefaultsToTableWhenAbsent() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - { name: id, type: bigint }
        """;
    assertEquals("table", importer.parse(yaml, "test.yaml").get(0).kind());
  }

  @Test
  void viewAndMaterializedViewKindsAccepted() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer_v
              kind: view
              columns:
                - { name: id, type: bigint }
            - catalog: crm
              schema: public
              name: mv_stats
              kind: materialized_view
              columns:
                - { name: day, type: date }
        """;
    List<TableStructure> tables = importer.parse(yaml, "test.yaml");
    assertEquals("view", tables.get(0).kind());
    assertEquals("materialized_view", tables.get(1).kind());
  }

  @Test
  void unknownKindRejectedNotSilentlyDropped() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer_v
              kind: viwe
              columns:
                - { name: id, type: bigint }
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> importer.parse(yaml, "test.yaml"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("unknown kind 'viwe'"), () -> e.getMessage());
    assertTrue(e.getMessage().contains("table|view|materialized_view"), () -> e.getMessage());
  }

  @Test
  void nonStringKindRejected() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer_v
              kind: 123
              columns:
                - { name: id, type: bigint }
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> importer.parse(yaml, "test.yaml"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("unknown kind '123'"), () -> e.getMessage());
    assertTrue(e.getMessage().contains("table|view|materialized_view"), () -> e.getMessage());
  }

  @Test
  void rowFilterFieldRejectedWithGuidance() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              rowFilter: "status = 'active'"
              columns:
                - { name: id, type: bigint }
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> importer.parse(yaml, "test.yaml"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("rowFilter is not accepted here"));
    assertTrue(e.getMessage().contains("row_filter policy on the policy service"));
  }

  @Test
  void explicitNullRowFilterAlsoRejected() {
    // 显式 rowFilter: null 也算声明了 rowFilter：必须拒绝（containsKey 语义，
    // 与 "never silently dropped" 一致），而不是当 null 放行。
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              rowFilter: null
              columns:
                - { name: id, type: bigint }
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> importer.parse(yaml, "test.yaml"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("rowFilter is not accepted here"), () -> e.getMessage());
  }

  @Test
  void missingTablesRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> importer.parse("metadata: {}", "test.yaml"));
    assertTrue(e.getMessage().contains("must be a list"));
  }

  @Test
  void emptyColumnsRejected() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns: []
        """;
    assertThrows(SqlMaskException.class, () -> importer.parse(yaml, "test.yaml"));
  }

  @Test
  void nonMappingRootRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> importer.parse("- just\n- a\n- list\n", "test.yaml"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("YAML must be a mapping"));
  }

  @Test
  void missingRequiredFieldReportedWithPath() {
    String yaml = """
        metadata:
          tables:
            - schema: public
              name: customer
              columns:
                - { name: id, type: bigint }
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> importer.parse(yaml, "test.yaml"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("metadata.tables[0].catalog is required"),
        () -> e.getMessage());
  }

  @Test
  void emptyTablesListParsesLegally() {
    // 导入器层面空列表合法（采集可能出现零表）；「declares no tables」的拒绝在 admin 端点
    List<TableStructure> tables = importer.parse("metadata:\n  tables: []\n", "test.yaml");
    assertEquals(0, tables.size());
  }

  @Test
  void nonMappingColumnEntryRejected() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - id
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> importer.parse(yaml, "test.yaml"));
    assertTrue(e.getMessage().contains("metadata.tables[0].columns[0] must be a mapping"),
        () -> e.getMessage());
  }

  @Test
  void nonMappingTableEntryRejected() {
    String yaml = """
        metadata:
          tables:
            - just_a_string
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> importer.parse(yaml, "test.yaml"));
    assertTrue(e.getMessage().contains("metadata.tables[0] must be a mapping"),
        () -> e.getMessage());
  }
}
