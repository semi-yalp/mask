package io.sqlmask.introspect;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataYamlGeneratorTest {

  private static final Path GOLDEN_DIR = Path.of("src/test/resources/golden");
  private static final boolean WRITE =
      Boolean.getBoolean("golden.write");

  private IntrospectionResult multiTable() {
    return new IntrospectionResult("crm", List.of(
        new IntrospectionResult.TableInfo("crm", "public", "customer", List.of(
            new IntrospectionResult.ColumnInfo("id", "bigint", "bigint", false),
            new IntrospectionResult.ColumnInfo("phone", "varchar(20)", "character varying(20)", false),
            new IntrospectionResult.ColumnInfo("created_at", "timestamp", "timestamp without time zone", false))),
        new IntrospectionResult.TableInfo("crm", "public", "orders", List.of(
            new IntrospectionResult.ColumnInfo("id", "bigint", "bigint", false),
            new IntrospectionResult.ColumnInfo("amount", "numeric(10,2)", "numeric(10,2)", false))),
        new IntrospectionResult.TableInfo("crm", "sales", "region", List.of(
            new IntrospectionResult.ColumnInfo("id", "integer", "integer", false)))),
        List.of());
  }

  private IntrospectionResult degraded() {
    return new IntrospectionResult("crm", List.of(
        new IntrospectionResult.TableInfo("crm", "public", "customer", List.of(
            new IntrospectionResult.ColumnInfo("id", "bigint", "bigint", false),
            new IntrospectionResult.ColumnInfo("extra", "varchar", "jsonb", true)))),
        List.of("column crm.public.customer.extra: PG type jsonb is not representable, degraded to varchar"));
  }

  private IntrospectionResult empty() {
    return new IntrospectionResult("crm", List.of(),
        List.of("未找到任何表，请检查 schema 过滤条件"));
  }

  @Test
  void multiTableGolden() throws Exception {
    assertGolden("introspect-multi-table.yaml", multiTable());
  }

  @Test
  void degradedColumnGolden() throws Exception {
    assertGolden("introspect-degraded.yaml", degraded());
  }

  @Test
  void emptySkeletonGolden() throws Exception {
    assertGolden("introspect-empty.yaml", empty());
  }

  @Test
  void tableOrderIsCatalogSchemaNameRegardlessOfInputOrder() {
    IntrospectionResult reversed = new IntrospectionResult("crm", List.of(
        new IntrospectionResult.TableInfo("crm", "public", "orders",
            List.of(new IntrospectionResult.ColumnInfo("id", "bigint", "bigint", false))),
        new IntrospectionResult.TableInfo("crm", "public", "customer",
            List.of(new IntrospectionResult.ColumnInfo("id", "bigint", "bigint", false)))),
        List.of());
    String out = new MetadataYamlGenerator().generate(reversed);
    assertTrue(out.indexOf("name: customer") < out.indexOf("name: orders"));
  }

  private void assertGolden(String fileName, IntrospectionResult result) throws Exception {
    String text = new MetadataYamlGenerator().generate(result);
    Path golden = GOLDEN_DIR.resolve(fileName);
    if (WRITE) {
      Files.writeString(golden, text + "\n", StandardCharsets.UTF_8);
    }
    assertEquals(Files.readString(golden, StandardCharsets.UTF_8), text + "\n");
  }
}
