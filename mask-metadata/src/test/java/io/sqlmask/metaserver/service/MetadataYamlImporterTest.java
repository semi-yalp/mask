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
    assertEquals(2, tables.get(0).columns().size());
    assertEquals("varchar", tables.get(0).columns().get(1).type());
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
}
