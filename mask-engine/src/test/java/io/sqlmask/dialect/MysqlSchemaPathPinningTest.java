package io.sqlmask.dialect;

import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins CalciteCatalogReader's multi-path resolution for the MySQL profile
 * ({@code CATALOG_SCHEMA_AND_SCHEMA}), per spec §10.1 item 1. If the observed
 * behavior is unacceptable, the fallback is a SCHEMA_ONLY profile (catalog
 * becomes a pure YAML grouping field).
 *
 * <p>Real model (Calcite 1.42, verified by bytecode decompilation in Task 5
 * and pinned by these tests): Calcite resolves a table name by CONCATENATING
 * each search-path entry with the name's leading segments
 * ({@code getSchema(rootSchema, concat(schemaPath, skipLast(names)))}). The
 * adapter generates {@code [catalog, schema]} pair paths plus bare
 * {@code [catalog]} paths (in that order, per declared schema), so:
 * a two-part name {@code db.table} resolves under the declared catalog
 * ({@code catalog.db} schema); an unqualified name resolves against every
 * pair path with first match winning (schemas iterate in Calcite's sorted
 * name order, not YAML declaration order — see the pinned third test);
 * the reader's trailing empty path lets three-part names resolve directly.
 */
class MysqlSchemaPathPinningTest {

  /** Two schemas both declaring {@code customer}; column names differ so the winner is observable. */
  private static final String TWO_SCHEMAS = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - name: phone
                type: varchar
          - catalog: crm
            schema: sales
            name: customer
            columns:
              - name: mobile
                type: varchar
      policies: {}
      """;

  private static final String ONE_SCHEMA = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - name: phone
                type: varchar
      policies: {}
      """;

  private final MysqlDialectAdapter adapter = new MysqlDialectAdapter();

  private SchemaPlus schemaOf(String yaml) {
    return YamlCalciteSchemaFactory.create(
        new YamlConfigLoader().loadContent(yaml, "pin.yaml", "mysql"));
  }

  @Test
  void twoPartNameResolvesUnderDeclaredCatalog() {
    // public.customer 是两段名：[crm, public] + [public] 落空后，[crm] + [public]
    // 命中声明 catalog 下的 crm.public.customer（concat 语义）
    var validated = adapter.validate(
        adapter.parse("SELECT phone FROM public.customer", 0), schemaOf(ONE_SCHEMA));
    assertEquals(java.util.List.of("phone"), validated.rowType().getFieldNames());
  }

  @Test
  void unqualifiedNameResolvesWhenUnique() {
    // 非限定名走 [crm, public] 对路径直接命中唯一表
    var validated = adapter.validate(
        adapter.parse("SELECT phone FROM customer", 0), schemaOf(ONE_SCHEMA));
    assertEquals(java.util.List.of("phone"), validated.rowType().getFieldNames());
  }

  @Test
  void unqualifiedNameAcrossDuplicateSchemasHasAPinnedOutcome() {
    // 观察钉定（2026-09-06）：非限定名 customer 在 crm.public 与 crm.sales 都声明
    // 同名表时，Calcite【按搜索路径顺序静默首匹配，不报二义】。搜索路径按 schema
    // 名有序生成（Calcite 子 schema 名为有序存储；探针验证：把声明顺序反转为
    // sales 在前，命中仍是 public），因此 customer 钉死解析到
    // crm.public.customer——SELECT phone（public 的列）成功。
    var validated = adapter.validate(
        adapter.parse("SELECT phone FROM customer", 0), schemaOf(TWO_SCHEMAS));
    assertEquals(java.util.List.of("phone"), validated.rowType().getFieldNames());
    // 反向证明是「首匹配」而非「任一匹配」：仅 sales 声明的列 mobile 经非限定名
    // 不可达（customer 已钉死为 public.customer，报 VALIDATION_ERROR）
    assertThrows(SqlMaskException.class,
        () -> adapter.validate(
            adapter.parse("SELECT mobile FROM customer", 0), schemaOf(TWO_SCHEMAS)),
        () -> "unqualified customer must resolve to crm.public (first search path), "
            + "not crm.sales");
  }
}
