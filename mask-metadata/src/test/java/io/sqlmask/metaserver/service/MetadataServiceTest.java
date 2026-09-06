package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.metaserver.model.ConnectionInfo;
import io.sqlmask.metaserver.model.InstanceRow;
import io.sqlmask.metaserver.store.InMemoryMetaStore;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    InstanceRow row = service.create("pg_prod", "PostgreSQL", conn());
    assertEquals("pg_prod", row.name());
    assertEquals("postgresql", row.dialect());
    assertEquals(1, row.metadataVersion());
  }

  @Test
  void duplicateCreateRejected() {
    service.create("pg_prod", "postgresql", conn());
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> service.create("pg_prod", "postgresql", conn()));
    assertEquals(SqlMaskException.Code.METADATA_INSTANCE_EXISTS, e.getCode());
  }

  @Test
  void unknownDialectRejected() {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> service.create("x", "oracle", conn()));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  @Test
  void blankNameRejected() {
    assertThrows(SqlMaskException.class, () -> service.create("  ", "postgresql", conn()));
  }

  @Test
  void getUnknownReturnsNotFound() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> service.get("ghost"));
    assertEquals(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND, e.getCode());
  }

  @Test
  void updateConnectionBumpsVersion() {
    service.create("pg_prod", "postgresql", conn());
    InstanceRow updated = service.updateConnection("pg_prod", null);
    assertEquals(2, updated.metadataVersion());
    assertEquals(null, updated.connection());
  }

  @Test
  void deleteRemovesInstance() {
    service.create("pg_prod", "postgresql", conn());
    service.delete("pg_prod");
    assertEquals(SqlMaskException.Code.METADATA_INSTANCE_NOT_FOUND,
        assertThrows(SqlMaskException.class, () -> service.get("pg_prod")).getCode());
  }
}
