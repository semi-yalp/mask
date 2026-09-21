package io.sqlmask.policyserver;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.policyserver.connection.EngineAccess;
import io.sqlmask.policyserver.model.AccessType;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.ConnectionConfig;
import io.sqlmask.policyserver.model.ConnectionStatus;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.PolicyVersion;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.store.PolicyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyServiceTest {

  private final PolicyStore store = mock(PolicyStore.class);
  private final PolicyValidator validator = mock(PolicyValidator.class);
  private final EngineAccess access = mock(EngineAccess.class);
  private PolicyService service;

  private static final TableDef CUSTOMER = new TableDef("crm", "public", "customer",
      List.of(new ColumnDef("phone", "varchar")));
  private static final ConnectionConfig CFG = new ConnectionConfig("postgresql", "localhost", 5432,
      "db", "u", "PW_REF", List.of(), false, "disable", 15);

  @BeforeEach
  void setUp() {
    service = new PolicyService(store, validator);
    when(store.findInstance("pg")).thenReturn(Optional.of(
        new EngineInstance("pg", "postgresql", CFG, ConnectionStatus.CONNECTED, List.of(CUSTOMER))));
    when(access.test(any())).thenReturn(
        new io.sqlmask.policyserver.connection.ConnectionTestResult(true, "postgresql", 4,
            List.of()));
  }

  private static PolicyEntity maskPolicy(String name, int version) {
    return new PolicyEntity(name, AccessType.SELECT, PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(), null, version);
  }

  @Test
  void createInstanceWithConnectionTestsAndStoresConnected() {
    when(store.createInstance(any())).thenAnswer(inv -> inv.getArgument(0));
    EngineInstance result = service.createInstance("pg", "postgresql", CFG, false, access);
    verify(access).test(CFG);
    assertEquals(ConnectionStatus.CONNECTED, result.status());
    assertEquals(CFG, result.connection());
    verify(store).createInstance(any());
  }

  @Test
  void createInstanceWithFetchMetadataCapturesTables() {
    when(store.createInstance(any())).thenAnswer(inv -> inv.getArgument(0));
    when(access.fetch(CFG)).thenReturn(List.of(CUSTOMER));
    EngineInstance result = service.createInstance("pg", "postgresql", CFG, true, access);
    verify(access).fetch(CFG);
    assertEquals(1, result.tables().size());
  }

  @Test
  void createInstanceWithoutConnectionIsUnconnected() {
    when(store.createInstance(any())).thenAnswer(inv -> inv.getArgument(0));
    EngineInstance result = service.createInstance("pg", "postgresql", null, false, access);
    assertEquals(ConnectionStatus.UNCONNECTED, result.status());
    verify(access, never()).test(any());
  }

  @Test
  void createInstanceConnectionFailurePersistsNothing() {
    when(access.test(any())).thenThrow(
        new SqlMaskException(SqlMaskException.Code.CONNECTION_FAILED, "refused"));
    ArgumentCaptor<EngineInstance> captor = ArgumentCaptor.forClass(EngineInstance.class);
    assertThrows(SqlMaskException.class, () ->
        service.createInstance("pg", "postgresql", CFG, false, access));
    verify(store, never()).createInstance(any());
  }

  @Test
  void updateConnectionTestsAndPersists() {
    service.updateConnection("pg", CFG, access);
    verify(access).test(CFG);
    verify(store).updateInstanceConnection("pg", CFG, ConnectionStatus.CONNECTED);
  }

  @Test
  void retestConnectionWithoutConnectionIsConfigError() {
    when(store.findInstance("pg")).thenReturn(Optional.of(
        new EngineInstance("pg", "postgresql", null, ConnectionStatus.UNCONNECTED,
            List.of(CUSTOMER))));
    SqlMaskException e = assertThrows(SqlMaskException.class, () ->
        service.retestConnection("pg", access));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
  }

  @Test
  void metadataFetchReplacesTables() {
    when(access.fetch(CFG)).thenReturn(List.of(CUSTOMER));
    when(store.updateInstanceTables("pg", List.of(CUSTOMER))).thenReturn(
        new EngineInstance("pg", "postgresql", CFG, ConnectionStatus.CONNECTED,
            List.of(CUSTOMER)));
    service.metadataFetch("pg", access);
    verify(store).updateInstanceTables("pg", List.of(CUSTOMER));
  }

  @Test
  void createPolicyValidatesAndStores() {
    when(store.listUdfs("pg")).thenReturn(List.of());
    when(store.listPolicies("pg")).thenReturn(List.of());
    service.createPolicy("pg", maskPolicy("p1", 0));
    verify(validator).validatePolicy(any(), any(), any(), any());
    verify(store).createPolicy(eq("pg"), any());
  }

  @Test
  void rollbackAndVersionsDelegateToStore() {
    when(store.rollbackPolicy("pg", "p1", 1))
        .thenReturn(maskPolicy("p1", 2));
    when(store.policyVersions("pg", "p1")).thenReturn(List.of(
        new PolicyVersion(1, io.sqlmask.policyserver.model.ChangeType.CREATE,
            maskPolicy("p1", 1), null, Instant.now())));
    assertEquals(2, service.rollbackPolicy("pg", "p1", 1).currentVersion());
    assertEquals(1, service.policyVersions("pg", "p1").size());
  }

  @Test
  void effectiveStampsConfigVersionAndCompiles() {
    when(store.currentVersion("pg")).thenReturn(7L);
    when(store.listPolicies("pg")).thenReturn(List.of(maskPolicy("p1", 1)));
    assertEquals(7L, service.effective("pg", Subject.anonymous()).configVersion());
  }

  @Test
  void deleteUdfGuardBlocksWhenPoliciesFailResolution() {
    when(store.listUdfs("pg")).thenReturn(List.of(
        new io.sqlmask.policyserver.model.UdfDefinition("mask_phone", List.of(
            new io.sqlmask.policyserver.model.UdfDefinition.UdfSignature(List.of("varchar"),
                "varchar")))));
    when(store.listPolicies("pg")).thenReturn(List.of(maskPolicy("p1", 1)));
    when(validator.policiesFailingUdfResolution(any(), any(), any())).thenReturn(List.of("p1"));
    SqlMaskException e = assertThrows(SqlMaskException.class, () ->
        service.deleteUdf("pg", "mask_phone"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    verify(store, never()).deleteUdf(any(), any());
  }
}