package io.sqlmask.metaserver.service;

import io.sqlmask.dialect.DialectProfiles;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetadataServiceTest {

  private final InMemoryMetaStore store = new InMemoryMetaStore();
  private final MetadataService service = new MetadataService(store);

  private static ConnectionInfo conn() {
    return new ConnectionInfo("127.0.0.1", 5432, "db", "user", "REF", "disable", 10,
        List.of(), false);
  }

  @Test
  void createStoresRowWithVersionOne() {
    InstanceRow row = service.create("pg_prod", "PostgreSQL", null, conn());
    assertEquals("pg_prod", row.name());
    assertEquals("postgresql", row.dialect());
    assertEquals(1, row.metadataVersion());
  }

  @Test
  void duplicateCreateRejected() {
    service.create("pg_prod", "postgresql", null, conn());
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> service.create("pg_prod", "postgresql", null, conn()));
    assertEquals(SqlMaskException.Code.METADATA_INSTANCE_EXISTS, e.getCode());
  }

  @Test
  void unknownDialectRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> service.create("x", "oracle", null, conn()));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    // 支持清单与 DialectProfiles.names() 同源，不得再硬编码（批2终审修复）
    assertThat(e.getMessage())
        .contains("unsupported dialect 'oracle'")
        .contains(String.join(", ", DialectProfiles.names()));
  }

  @Test
  void normalizeDialectAcceptsHiveAndSparksql() {
    // spec §6 正向钉：两新方言可建实例，dialect 归一小写
    InstanceRow h = service.create("h1", "Hive", null, conn());
    assertThat(h.dialect()).isEqualTo("hive");
    InstanceRow s = service.create("s1", "SparkSQL", null, conn());
    assertThat(s.dialect()).isEqualTo("sparksql");
  }

  @Test
  void effectiveEngineDerivesHiveAndSparksqlFromDialect() {
    // 批2终审修复（C1）：引擎派生必须覆盖 hive/sparksql，否则查询侧会按 mysql 引擎连库
    assertThat(new InstanceRow("h", "hive", null, null, 1).effectiveEngine()).isEqualTo("hive");
    assertThat(new InstanceRow("s", "sparksql", null, null, 1).effectiveEngine())
        .isEqualTo("sparksql");
    InstanceRow h = service.create("h1", "hive", null, conn());
    assertThat(h.effectiveEngine()).isEqualTo("hive");
    InstanceRow s = service.create("s1", "sparksql", null, conn());
    assertThat(s.effectiveEngine()).isEqualTo("sparksql");
  }

  @Test
  void blankNameRejected() {
    assertThrows(SqlMaskException.class, () -> service.create("  ", "postgresql", null, conn()));
  }

  @Test
  void getUnknownReturnsNotFound() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> service.get("ghost"));
    assertEquals(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND, e.getCode());
  }

  @Test
  void updateConnectionBumpsVersion() {
    service.create("pg_prod", "postgresql", null, conn());
    InstanceRow updated = service.updateConnection("pg_prod", null);
    assertEquals(2, updated.metadataVersion());
    assertEquals(null, updated.connection());
  }

  @Test
  void deleteRemovesInstance() {
    service.create("pg_prod", "postgresql", null, conn());
    service.delete("pg_prod");
    assertEquals(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND,
        assertThrows(SqlMaskException.class, () -> service.get("pg_prod")).getCode());
  }

  @Test
  void nameIsTrimmedOnCreateAndGet() {
    service.create("  pg_prod  ", "postgresql", null, conn());
    assertEquals("pg_prod", service.get(" pg_prod ").name());
  }

  @Test
  void updateConnectionUnknownInstanceNotFound() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> service.updateConnection("ghost", conn()));
    assertEquals(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND, e.getCode());
  }

  @Test
  void engineBlankDerivesNullAndStarrocksRequiresMysqlDialect() {
    InstanceRow pg = service.create("pg1", "postgresql", "  ", null);
    assertThat(pg.engine()).isNull();
    assertThat(pg.effectiveEngine()).isEqualTo("postgresql");

    InstanceRow sr = service.create("sr1", "mysql", "STARROCKS", null);
    assertThat(sr.engine()).isEqualTo("starrocks");
    assertThat(sr.effectiveEngine()).isEqualTo("starrocks");

    assertThatThrownBy(() -> service.create("bad1", "mysql", "hive", null))
        .isInstanceOf(SqlMaskException.class)
        .hasMessageContaining("unsupported engine");
    assertThatThrownBy(() -> service.create("bad2", "postgresql", "starrocks", null))
        .isInstanceOf(SqlMaskException.class)
        .hasMessageContaining("requires dialect 'mysql'");
  }
}
