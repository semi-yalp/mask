package io.sqlmask.policyserver.app;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.app.SuggestService.SuggestResult;
import io.sqlmask.policyserver.connection.EngineAccess;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.ConnectionConfig;
import io.sqlmask.policyserver.model.ConnectionStatus;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import io.sqlmask.policyserver.store.InMemoryPolicyStore;
import io.sqlmask.policyserver.store.PolicyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SuggestServiceTest {

  private PolicyStore store;
  private EngineAccess access;
  private SuggestService service;

  private static final ConnectionConfig CFG = new ConnectionConfig("postgresql", "localhost", 5432,
      "db", "u", "PW_REF", List.of(), false, "disable", 15);

  private static final TableDef CUSTOMER = new TableDef("crm", "public", "customer",
      List.of(new ColumnDef("phone", "varchar"), new ColumnDef("phone_alt", "varchar")));
  private static final TableDef ORDERS = new TableDef("crm", "public", "orders",
      List.of(new ColumnDef("amount", "numeric")));

  @BeforeEach
  void setUp() {
    store = new InMemoryPolicyStore();
    access = mock(EngineAccess.class);
    service = new SuggestService(store, access);
  }

  private EngineInstance snapshotInstance() {
    EngineInstance instance = new EngineInstance("pg", "postgresql", CFG, ConnectionStatus.CONNECTED,
        List.of(CUSTOMER, ORDERS));
    store.createInstance(instance);
    return instance;
  }

  private EngineInstance emptySnapshotWithConnection() {
    EngineInstance instance = new EngineInstance("pg", "postgresql", CFG,
        ConnectionStatus.CONNECTED, List.of());
    store.createInstance(instance);
    return instance;
  }

  @Test
  void tableSuggestionsFromSnapshot() {
    snapshotInstance();
    SuggestResult result = service.suggest("pg", "table", "cust", null, null, 20);
    assertEquals("snapshot", result.source());
    assertEquals(1, result.items().size());
    assertEquals("customer", result.items().get(0).name());
  }

  @Test
  void tableSuggestionsFilterBySchema() {
    snapshotInstance();
    SuggestResult result = service.suggest("pg", "table", "", "sales", null, 20);
    assertEquals(0, result.items().size());
  }

  @Test
  void emptySnapshotFallsBackToLive() {
    emptySnapshotWithConnection();
    when(access.fetch(any())).thenReturn(List.of(CUSTOMER, ORDERS));
    SuggestResult result = service.suggest("pg", "table", "cust", null, null, 20);
    assertEquals("live", result.source());
    assertEquals(1, result.items().size());
  }

  @Test
  void liveFailureSurfacesConnectionFailedNot500() {
    emptySnapshotWithConnection();
    when(access.fetch(any())).thenThrow(new SqlMaskException(
        SqlMaskException.Code.CONNECTION_FAILED, "down"));
    SqlMaskException e = assertThrows(SqlMaskException.class, () ->
        service.suggest("pg", "table", "cust", null, null, 20));
    assertEquals(SqlMaskException.Code.CONNECTION_FAILED, e.getCode());
  }

  @Test
  void columnSuggestionsWithinTable() {
    snapshotInstance();
    SuggestResult result = service.suggest("pg", "column", "phone", "public", "customer", 20);
    assertEquals(2, result.items().size());
    assertTrue(result.items().stream().anyMatch(i -> i.name().equals("phone")));
    assertTrue(result.items().stream().anyMatch(i -> i.name().equals("phone_alt")));
    assertEquals("customer", result.items().get(0).table());
  }

  @Test
  void columnUnknownTableReturnsEmpty() {
    snapshotInstance();
    SuggestResult result = service.suggest("pg", "column", "", "public", "nope", 20);
    assertEquals(0, result.items().size());
  }

  @Test
  void udfSuggestionsFromRegistry() {
    snapshotInstance();
    store.createUdf("pg", new UdfDefinition("mask_phone",
        List.of(new UdfDefinition.UdfSignature(List.of("varchar"), "varchar"))));
    SuggestResult result = service.suggest("pg", "udf", "mask", null, null, 20);
    assertEquals(1, result.items().size());
    assertEquals("mask_phone", result.items().get(0).name());
  }

  @Test
  void emptyQueryReturnsNoSuggestions() {
    snapshotInstance();
    SuggestResult result = service.suggest("pg", "table", "", null, null, 20);
    assertEquals(0, result.items().size());
  }

  @Test
  void limitCapsResults() {
    snapshotInstance();
    // Only "orders" matches the "order" prefix; cap 1 returns one.
    SuggestResult result = service.suggest("pg", "table", "order", null, null, 1);
    assertEquals(1, result.items().size());
  }

  @Test
  void unknownKindIsConfigError() {
    snapshotInstance();
    SqlMaskException e = assertThrows(SqlMaskException.class, () ->
        service.suggest("pg", "bogus", "", null, null, 20));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  @Test
  void unknownInstanceIsNotFound() {
    SqlMaskException e = assertThrows(SqlMaskException.class, () ->
        service.suggest("nope", "table", "", null, null, 20));
    assertEquals(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND, e.getCode());
  }
}