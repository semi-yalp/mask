package io.sqlmask.config;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.ColumnKey;
import org.apache.calcite.sql.type.SqlTypeName;
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
    var policy = policyFor(loaded, phoneKey);
    assertTrue(policy.isPresent(), "phone should be bound to a policy");
    assertTrue(policy.get().policyName().startsWith("phone_mask"), policy.get().policyName());
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
    var policy = policyFor(loaded,
        ColumnKey.of("crm", "public", "customer", "phone")).orElseThrow();
    assertEquals(List.of(3, 4), policy.arguments());
  }

  /** Legacy-config policy lookups now go through the PDP over converted policies. */
  private static java.util.Optional<io.sqlmask.policy.model.MaskInstruction> policyFor(
      LoadedConfig loaded, ColumnKey key) {
    io.sqlmask.policy.match.PolicyEngine engine = new io.sqlmask.policy.match.PolicyEngine(
        io.sqlmask.policy.match.PolicyIndex.of(LegacyPolicyAdapter.convert(loaded.config())));
    return engine.maskFor(key.catalog(), key.schema(), key.table(), key.column(),
        io.sqlmask.policy.model.Subject.anonymous());
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

  @Test
  void loadsRowFilter() {
    LoadedConfig loaded = loader.loadContent("""
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              rowFilter: "status = 'active'"
              columns:
                - name: id
                  type: bigint
        policies: {}
        """, "test.yaml");
    var table = loaded.findTable("crm", "public", "customer").orElseThrow();
    assertEquals("status = 'active'", table.rowFilter());
  }

  @Test
  void blankRowFilterMeansUnconfigured() {
    for (String rowFilter : new String[] {"", "   "}) {
      LoadedConfig loaded = loader.loadContent("""
          metadata:
            tables:
              - catalog: crm
                schema: public
                name: customer
                rowFilter: "%s"
                columns:
                  - name: id
                    type: bigint
          policies: {}
          """.formatted(rowFilter), "test.yaml");
      var table = loaded.findTable("crm", "public", "customer").orElseThrow();
      assertEquals(null, table.rowFilter(),
          () -> "blank rowFilter '" + rowFilter + "' must mean unconfigured");
    }
  }

  @Test
  void absentRowFilterMeansUnconfigured() {
    LoadedConfig loaded = loader.load(VALID);
    var table = loaded.findTable("crm", "public", "customer").orElseThrow();
    assertEquals(null, table.rowFilter());
  }

  @Test
  void rejectsNonStringRowFilter() {
    for (String rowFilter : new String[] {"[1, 2]", "123", "true"}) {
      String yaml = """
          metadata:
            tables:
              - catalog: crm
                schema: public
                name: customer
                rowFilter: %s
                columns:
                  - name: id
                    type: bigint
          policies: {}
          """.formatted(rowFilter);
      SqlMaskException e =
          assertThrows(SqlMaskException.class, () -> loader.loadContent(yaml, "test.yaml"),
              () -> "rowFilter " + rowFilter + " must be rejected");
      assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
      assertTrue(e.getMessage().contains("metadata.tables[0].rowFilter"),
          () -> "diagnostic should point at tables[0].rowFilter but was: " + e.getMessage());
    }
  }

  @Test
  void loadsIndependentRowFiltersPerTable() {
    LoadedConfig loaded = loader.loadContent("""
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              rowFilter: "status = 'active'"
              columns:
                - name: id
                  type: bigint
            - catalog: crm
              schema: public
              name: orders
              rowFilter: "region = 'north'"
              columns:
                - name: id
                  type: bigint
        policies: {}
        """, "test.yaml");
    assertEquals("status = 'active'",
        loaded.findTable("crm", "public", "customer").orElseThrow().rowFilter());
    assertEquals("region = 'north'",
        loaded.findTable("crm", "public", "orders").orElseThrow().rowFilter());
  }

  @Test
  void policiesRemainRequiredEvenWithoutRowFilters() {
    // current-contract lock: a row-filter-only YAML must still declare the
    // (possibly empty) policies mapping; revisiting this is a deliberate
    // loader change, not an accident
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> loader.loadContent("""
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              rowFilter: "status = 'active'"
              columns:
                - name: id
                  type: bigint
        """, "test.yaml"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("policies"), () -> e.getMessage());
  }

  @Test
  void mysqlTypeNamesParseUnderMysqlDialect() {
    String yaml = """
        metadata:
          tables:
            - catalog: shop
              schema: app
              name: orders
              columns:
                - name: id
                  type: bigint
                - name: taken_at
                  type: datetime
                - name: memo
                  type: text
        policies: {}
        """;
    LoadedConfig loaded = new YamlConfigLoader().loadContent(yaml, "m.yaml", "mysql");
    assertEquals(SqlTypeName.TIMESTAMP,
        loaded.tables().get(0).columns().get(1).sqlTypeName());
    assertEquals("datetime", loaded.tables().get(0).columns().get(1).typeDeclaration());
  }

  @Test
  void mysqlTypeNameUnderPostgresDialectFails() {
    String yaml = """
        metadata:
          tables:
            - catalog: shop
              schema: app
              name: orders
              columns:
                - name: taken_at
                  type: datetime
        policies: {}
        """;
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new YamlConfigLoader().loadContent(yaml, "m.yaml", "postgresql"));
    assertTrue(e.getMessage().contains("datetime"), () -> e.getMessage());
  }

  @Test
  void unknownDialectInLoaderFailsWithList() {
    // an empty tables list would trip the unrelated 'policies' validation
    // first, so use the minimal single-table document and assert on the
    // dialect name alone
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> new YamlConfigLoader().loadContent("""
            metadata:
              tables:
                - catalog: shop
                  schema: app
                  name: orders
                  columns:
                    - name: id
                      type: bigint
            policies: {}
            """, "m.yaml", "oracle"));
    assertTrue(e.getMessage().contains("unsupported dialect"), () -> e.getMessage());
  }
}
