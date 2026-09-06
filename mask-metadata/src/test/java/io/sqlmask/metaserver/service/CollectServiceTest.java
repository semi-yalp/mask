package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.introspect.ConnectionSpec;
import io.sqlmask.introspect.IntrospectionResult;
import io.sqlmask.introspect.MetadataIntrospector;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.model.TableStructure;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import io.sqlmask.metaserver.web.MetadataDtos.CollectResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CollectServiceTest {

  private final InMemoryMetaStore store = new InMemoryMetaStore();
  private final MetadataService instances = new MetadataService(store);
  private final StructureService structures = new StructureService(store);

  private static IntrospectionResult result = new IntrospectionResult("db",
      List.of(new IntrospectionResult.TableInfo("db", "public", "customer",
          List.of(new IntrospectionResult.ColumnInfo("id", "bigint", "int8", false),
              new IntrospectionResult.ColumnInfo("phone", "varchar", "text", false)))),
      List.of("column db.public.customer.phone: PG type text is not representable, degraded to varchar"));

  private final CollectService service = new CollectService(instances, structures,
      ref -> "resolved-password", engine -> spec -> result);

  CollectServiceTest() {
    store.createInstance(new InstanceRow("pg_prod", "postgresql",
        new ConnectionInfo("127.0.0.1", 5432, "db", "user", "SQLMASK_PG_PASSWORD", "disable",
            10, List.of("public"), false), 1));
    store.createInstance(new InstanceRow("no_conn", "postgresql", null, 1));
  }

  @Test
  void collectReplacesStructureAndReportsCounts() {
    CollectResponse response = service.collect("pg_prod");
    assertEquals(1, response.tableCount());
    assertEquals(2, response.columnCount());
    assertEquals(1, response.warnings().size());
    assertEquals(2, response.metadataVersion());
    List<TableStructure> stored = store.loadStructure("pg_prod");
    assertEquals("bigint", stored.get(0).columns().get(0).type());
  }

  @Test
  void collectFailureLeavesStoredStructureUntouched() {
    service.collect("pg_prod");
    long versionBefore = store.findInstance("pg_prod").orElseThrow().metadataVersion();
    CollectService failing = new CollectService(instances, structures, ref -> "pw",
        engine -> spec -> {
          throw new SqlMaskException(SqlMaskException.Code.INTROSPECT_ERROR, "db down");
        });
    assertThrows(SqlMaskException.class, () -> failing.collect("pg_prod"));
    assertEquals(versionBefore, store.findInstance("pg_prod").orElseThrow().metadataVersion());
    assertEquals(1, store.loadStructure("pg_prod").size());
  }

  @Test
  void instanceWithoutConnectionRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> service.collect("no_conn"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  @Test
  void connectionSpecBuiltWithStoredScope() {
    List<ConnectionSpec> captured = new java.util.ArrayList<>();
    CollectService capturing = new CollectService(instances, structures, ref -> "resolved-password",
        engine -> spec -> {
          captured.add(spec);
          return result;
        });
    capturing.collect("pg_prod");
    ConnectionSpec spec = captured.get(0);
    assertEquals("postgresql", spec.engine());
    assertEquals("resolved-password", spec.password());
    assertEquals(false, spec.strict());
    assertEquals(List.of("public"), spec.schemas());
  }
}
