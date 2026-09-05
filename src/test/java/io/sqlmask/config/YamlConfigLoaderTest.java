package io.sqlmask.config;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.ColumnKey;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlConfigLoaderTest {

  private final YamlConfigLoader loader = new YamlConfigLoader();

  private static final Path VALID = Path.of("src/test/resources/metadata/valid.yaml");
  private static final Path INVALID = Path.of("src/test/resources/metadata/invalid.yaml");

  @Test
  void loadsCompleteTableAndPolicy() {
    LoadedConfig loaded = loader.load(VALID);

    var phoneKey = ColumnKey.of("crm", "public", "customer", "phone");
    var policy = loaded.policyRegistry().find(phoneKey);
    assertTrue(policy.isPresent(), "phone should be bound to a policy");
    assertEquals("phone_mask", policy.get().name());
    assertEquals("mask_phone", policy.get().udf());

    var table = loaded.findTable("crm", "public", "customer");
    assertTrue(table.isPresent());
    assertEquals(
        List.of("id", "phone", "email", "status", "created_at"),
        table.get().columns().stream().map(c -> c.name()).toList());
  }

  @Test
  void rejectsMissingQualifiedTablePart() {
    SqlMaskException e =
        assertThrows(SqlMaskException.class, () -> loader.load(INVALID));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("columns[0].table"),
        () -> "diagnostic should point at columns[0].table but was: " + e.getMessage());
  }

  @Test
  void rejectsColumnPolicyReferencingUnknownPolicy() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - name: phone
                  type: varchar
        columns:
          - catalog: crm
            schema: public
            table: customer
            column: phone
            policy: nope
        policies:
          phone_mask:
            udf: mask_phone
            arguments: [3, 4]
        """;
    SqlMaskException e =
        assertThrows(SqlMaskException.class, () -> loader.loadContent(yaml, "test.yaml"));
    assertTrue(e.getMessage().contains("columns[0].policy"),
        () -> "diagnostic should point at columns[0].policy but was: " + e.getMessage());
    assertTrue(e.getMessage().contains("nope"),
        () -> "diagnostic should name the unknown policy but was: " + e.getMessage());
  }

  @Test
  void preservesArgumentOrder() {
    LoadedConfig loaded = loader.load(VALID);
    var policy = loaded.policyRegistry()
        .find(ColumnKey.of("crm", "public", "customer", "phone")).orElseThrow();
    assertEquals(List.of(3, 4), policy.arguments());
  }

  @Test
  void rejectsDuplicateTable() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - name: id
                  type: bigint
            - catalog: CRM
              schema: public
              name: customer
              columns:
                - name: id
                  type: bigint
        policies: {}
        """;
    SqlMaskException e =
        assertThrows(SqlMaskException.class, () -> loader.loadContent(yaml, "test.yaml"));
    assertTrue(e.getMessage().contains("duplicate table"),
        () -> e.getMessage());
  }

  @Test
  void rejectsDuplicateColumn() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - name: phone
                  type: varchar
                - name: PHONE
                  type: varchar
        policies: {}
        """;
    SqlMaskException e =
        assertThrows(SqlMaskException.class, () -> loader.loadContent(yaml, "test.yaml"));
    assertTrue(e.getMessage().contains("duplicate column"), () -> e.getMessage());
  }

  @Test
  void rejectsUnknownColumnType() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - name: extra
                  type: hstore
        policies: {}
        """;
    SqlMaskException e =
        assertThrows(SqlMaskException.class, () -> loader.loadContent(yaml, "test.yaml"));
    assertTrue(e.getMessage().contains("metadata.tables[0].columns[0].type"),
        () -> "diagnostic should point at the column type but was: " + e.getMessage());
  }

  @Test
  void rejectsDuplicateColumnBinding() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - name: phone
                  type: varchar
        columns:
          - catalog: crm
            schema: public
            table: customer
            column: phone
            policy: mask
          - catalog: crm
            schema: public
            table: customer
            column: PHONE
            policy: mask
        policies:
          mask:
            udf: mask_generic
            arguments: []
        """;
    SqlMaskException e =
        assertThrows(SqlMaskException.class, () -> loader.loadContent(yaml, "test.yaml"));
    assertTrue(e.getMessage().contains("duplicate policy binding"), () -> e.getMessage());
  }

  @Test
  void rejectsNonScalarArgument() {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - name: phone
                  type: varchar
        policies:
          mask:
            udf: mask_generic
            arguments:
              - name: keep_last
        """;
    SqlMaskException e =
        assertThrows(SqlMaskException.class, () -> loader.loadContent(yaml, "test.yaml"));
    assertTrue(e.getMessage().contains("policies.mask"), () -> e.getMessage());
    assertTrue(e.getMessage().contains("scalar"), () -> e.getMessage());
  }
}
