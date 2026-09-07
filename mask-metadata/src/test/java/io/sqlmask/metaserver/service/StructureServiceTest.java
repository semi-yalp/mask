package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StructureServiceTest {

  private final InMemoryMetaStore store = new InMemoryMetaStore();
  private final StructureService service = new StructureService(store);

  StructureServiceTest() {
    store.createInstance(new InstanceRow("pg_prod", "postgresql",
        new ConnectionInfo("h", 5432, "d", "u", "R", "disable", 10, List.of(), false), 1));
    store.createInstance(new InstanceRow("my_prod", "mysql", null, 1));
  }

  private static TableStructure table(String name, String[][] columns) {
    return new TableStructure("crm", "public", name,
        java.util.Arrays.stream(columns)
            .map(c -> new TableStructure.ColumnStructure(c[0], c[1])).toList());
  }

  @Test
  void validTablesReplaceAndBumpVersion() {
    long version = service.replace("pg_prod", List.of(
        table("customer", new String[][]{{"id", "bigint"}, {"phone", "varchar"}})));
    assertEquals(2, version);
    assertEquals(1, store.loadStructure("pg_prod").size());
  }

  @Test
  void emptyListAllowedForCollectionScenario() {
    assertEquals(2, service.replace("pg_prod", List.of()));
    assertEquals(0, store.loadStructure("pg_prod").size());
  }

  @Test
  void unresolvableTypeRejectedWithPath() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> service.replace("pg_prod",
        List.of(table("customer", new String[][]{{"id", "not-a-type"}}))));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertEquals(true, e.getMessage().contains("crm.public.customer.id"));
  }

  @Test
  void duplicateTableRejected() {
    assertThrows(SqlMaskException.class, () -> service.replace("pg_prod",
        List.of(table("customer", new String[][]{{"id", "bigint"}}),
            table("CUSTOMER", new String[][]{{"id", "bigint"}}))));
  }

  @Test
  void mysqlTypesValidatedAgainstMysqlResolver() {
    long version = service.replace("my_prod", List.of(
        table("orders", new String[][]{{"id", "bigint"}, {"amount", "decimal(10,2)"}})));
    assertEquals(2, version);
  }

  @Test
  void unknownInstanceRejected() {
    assertThrows(SqlMaskException.class, () -> service.replace("ghost", List.of()));
  }

  @Test
  void zeroColumnTableRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> service.replace("pg_prod", List.of(table("customer", new String[][]{}))));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertEquals(true, e.getMessage().contains("must declare at least one column"));
  }

  @Test
  void failedReplaceKeepsPreviousStructureAndVersion() {
    // 校验发生在原子覆盖之前：批次里第二张表非法时，第一张表与存量结构都不落库
    service.replace("pg_prod", List.of(table("customer", new String[][]{{"id", "bigint"}})));
    long versionBefore = store.findInstance("pg_prod").orElseThrow().metadataVersion();
    assertThrows(SqlMaskException.class, () -> service.replace("pg_prod", List.of(
        table("customer", new String[][]{{"id", "bigint"}}),
        table("orders", new String[][]{{"id", "definitely-not-a-type"}}))));
    assertEquals(versionBefore, store.findInstance("pg_prod").orElseThrow().metadataVersion());
    assertEquals(1, store.loadStructure("pg_prod").size());
  }

  @Test
  void dialectExclusiveTypeRejectedOnWrongDialect() {
    // timestamptz 只在 PostgreSQL 类型清单内：写入 mysql 实例时必须被该方言的 TypeResolver 拒绝
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> service.replace("my_prod",
        List.of(table("orders", new String[][]{{"created_at", "timestamptz"}}))));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertEquals(true, e.getMessage().contains("crm.public.orders.created_at"));
  }
}
