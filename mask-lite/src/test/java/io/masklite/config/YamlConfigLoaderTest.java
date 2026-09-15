package io.masklite.config;

import io.masklite.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Error-path coverage for the metadata YAML loader: every guard reports its YAML path. */
class YamlConfigLoaderTest {

  private static final String VALID = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - name: phone
                type: varchar
      policies:
        mask_phone:
          udf: mask_phone
          arguments: [3, 4]
      columns:
        - catalog: crm
          schema: public
          table: customer
          column: phone
          policy: mask_phone
      """;

  /** {@code VALID} without the top-level {@code columns:} binding section. */
  private static final String VALID_WITHOUT_BINDINGS = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - name: phone
                type: varchar
      policies:
        mask_phone:
          udf: mask_phone
          arguments: [3, 4]
      """;

  private final YamlConfigLoader loader = new YamlConfigLoader();

  private static SqlMaskException loadFails(String yaml) {
    return assertThrows(SqlMaskException.class, () -> new YamlConfigLoader().loadContent(yaml, "m.yaml"),
        () -> yaml);
  }

  @Test
  void validYamlLoadsTablesPoliciesAndBindings() {
    MaskingConfig config = loader.loadContent(VALID, "m.yaml");
    assertEquals(1, config.tables().size());
    assertEquals(1, config.columnPolicies().size());
    assertEquals("mask_phone", config.policies().get("mask_phone").udf());
  }

  @Test
  void columnsSectionIsOptional() {
    MaskingConfig config = loader.loadContent(VALID_WITHOUT_BINDINGS, "m.yaml");
    assertEquals(0, config.columnPolicies().size());
    assertEquals(1, config.tables().size());
  }

  @Test
  void malformedYamlIsConfigError() {
    SqlMaskException e = loadFails("metadata:\n  tables: [unclosed");
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("invalid YAML"), () -> e.getMessage());
  }

  @Test
  void duplicateYamlKeysAreRejected() {
    SqlMaskException e = loadFails("""
        metadata:
          tables: []
        metadata:
          tables: []
        policies: {}
        """);
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  @Test
  void rootMustBeAMapping() {
    SqlMaskException e = loadFails("- a\n- b\n");
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("root must be a mapping"), () -> e.getMessage());
  }

  @Test
  void metadataSectionIsRequired() {
    SqlMaskException e = loadFails("policies: {}\n");
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("'metadata' must be a mapping"), () -> e.getMessage());
  }

  @Test
  void tablesMustBeAList() {
    SqlMaskException e = loadFails("""
        metadata:
          tables:
            key: value
        policies: {}
        """);
    assertTrue(e.getMessage().contains("'metadata.tables' must be a list"), () -> e.getMessage());
  }

  @Test
  void tableEntryMustBeAMapping() {
    SqlMaskException e = loadFails("""
        metadata:
          tables:
            - just_a_string
        policies: {}
        """);
    assertTrue(e.getMessage().contains("metadata.tables[0] must be a mapping"), () -> e.getMessage());
  }

  @Test
  void requiredTableFieldMayNotBeBlank() {
    SqlMaskException e = loadFails("""
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: " "
              columns:
                - name: phone
                  type: varchar
        policies: {}
        """);
    assertTrue(e.getMessage().contains("metadata.tables[0].name: required non-blank string is missing"),
        () -> e.getMessage());
  }

  @Test
  void duplicateTableIsRejected() {
    SqlMaskException e = loadFails("""
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - name: phone
                  type: varchar
            - catalog: crm
              schema: public
              name: customer
              columns:
                - name: id
                  type: bigint
        policies: {}
        """);
    assertTrue(e.getMessage().contains("duplicate table 'crm.public.customer'"), () -> e.getMessage());
  }

  @Test
  void duplicateColumnIsRejected() {
    SqlMaskException e = loadFails("""
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - name: phone
                  type: varchar
                - name: phone
                  type: varchar
        policies: {}
        """);
    assertTrue(e.getMessage().contains("columns[1]: duplicate column name 'phone'"),
        () -> e.getMessage());
  }

  @Test
  void emptyColumnListIsRejected() {
    SqlMaskException e = loadFails("""
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns: []
        policies: {}
        """);
    assertTrue(e.getMessage().contains("must declare at least one column"), () -> e.getMessage());
  }

  @Test
  void unsupportedColumnTypeReportsItsPath() {
    SqlMaskException e = loadFails("""
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - name: payload
                  type: struct<a int>
        policies: {}
        """);
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("metadata.tables[0].columns[0].type"), () -> e.getMessage());
    assertTrue(e.getMessage().contains("unsupported or malformed type declaration"),
        () -> e.getMessage());
  }

  @Test
  void policiesSectionIsRequired() {
    SqlMaskException e = loadFails("""
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              columns:
                - name: phone
                  type: varchar
        """);
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("'policies' must be a mapping"), () -> e.getMessage());
  }

  @Test
  void policyUdfIsRequired() {
    SqlMaskException e = loadFails("""
        metadata:
          tables: []
        policies:
          mask_phone: {}
        """);
    assertTrue(e.getMessage().contains("policies.mask_phone.udf: required non-blank string is missing"),
        () -> e.getMessage());
  }

  @Test
  void policyArgumentsMustBeAList() {
    SqlMaskException e = loadFails("""
        metadata:
          tables: []
        policies:
          mask_phone:
            udf: mask_phone
            arguments: 3
        """);
    assertTrue(e.getMessage().contains("arguments must be a list"), () -> e.getMessage());
  }

  @Test
  void policyArgumentsMustBeScalars() {
    SqlMaskException e = loadFails("""
        metadata:
          tables: []
        policies:
          mask_phone:
            udf: mask_phone
            arguments:
              - [3, 4]
        """);
    assertTrue(e.getMessage().contains("must be a scalar"), () -> e.getMessage());
  }

  @Test
  void columnsBindingSectionMustBeAList() {
    SqlMaskException e = loadFails(VALID_WITHOUT_BINDINGS + "columns:\n  key: value\n");
    assertTrue(e.getMessage().contains("'columns' must be a list"), () -> e.getMessage());
  }

  @Test
  void duplicateColumnBindingIsRejected() {
    SqlMaskException e = loadFails(VALID_WITHOUT_BINDINGS + """
        columns:
          - catalog: crm
            schema: public
            table: customer
            column: phone
            policy: mask_phone
          - catalog: crm
            schema: public
            table: customer
            column: phone
            policy: mask_phone
        """);
    assertTrue(e.getMessage().contains("duplicate policy binding"), () -> e.getMessage());
  }

  @Test
  void unreadableFileIsIoError() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> loader.load(Path.of("no-such-metadata-file.yaml")));
    assertEquals(SqlMaskException.Code.IO_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("cannot read metadata file"), () -> e.getMessage());
  }
}
