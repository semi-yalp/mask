package io.sqlmask.policyserver.udf;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.introspect.udf.UdfIntrospector;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Full UDF-center chain against the in-memory store: the introspector lookup
 * is the injected fake (fixed signature list, no live engine) and the
 * {@link Connection}/{@link Statement} are Mockito mocks — deploy only needs
 * {@code execute} to succeed, import/resync only read through the fake.
 */
@SpringBootTest
class UdfCenterServiceTest {

  @Autowired
  private PolicyService policyService;

  private final Connection connection = mock(Connection.class);
  private final Statement statement = mock(Statement.class);

  /** What the fake engine currently exposes; tests mutate it between calls. */
  private final List<UdfIntrospector.UdfSignature> engineView = new ArrayList<>();

  @BeforeEach
  void stubStatementExecution() throws SQLException {
    when(connection.createStatement()).thenReturn(statement);
    when(statement.execute(anyString())).thenReturn(true);
  }

  private UdfCenterService service() {
    return new UdfCenterService(policyService, engine -> (c, schema) -> List.copyOf(engineView));
  }

  private String newInstance() {
    String instance = "udf_center_" + UUID.randomUUID().toString().substring(0, 8);
    policyService.createInstance(instance, "postgresql", List.of(
        new TableDef("crm", "public", "customer", List.of(new ColumnDef("phone", "varchar")))));
    return instance;
  }

  private static UdfIntrospector.UdfSignature sig(String name, String returns, String... params) {
    return new UdfIntrospector.UdfSignature(name, List.of(params), returns);
  }

  @Test
  void importCreatesMissingDefinitionsAsImported() throws SQLException {
    String instance = newInstance();
    engineView.add(sig("mask_phone", "text", "text", "integer", "integer"));
    engineView.add(sig("mask_idcard", "text", "text", "integer"));
    engineView.add(sig("uuid_generate_v4", "uuid")); // zero-param: not a masking udf

    UdfCenterService.ImportResult result = service().importFromEngine(instance, connection, "postgresql");

    assertEquals(2, result.created());
    assertEquals(0, result.updated());
    assertEquals(List.of("mask_idcard", "mask_phone"), result.names());
    List<UdfDefinition> registry = policyService.udfs(instance);
    assertEquals(2, registry.size());
    for (UdfDefinition udf : registry) {
      assertEquals("IMPORTED", udf.source());
      assertNotNull(udf.lastSyncedAt());
    }
    UdfDefinition phone = policyService.udf(instance, "mask_phone").orElseThrow();
    assertEquals(List.of("text", "integer", "integer"), phone.signatures().get(0).params());
    assertEquals("text", phone.signatures().get(0).returns());
  }

  @Test
  void importRefreshesChangedAndUnchangedDefinitions() throws SQLException {
    String instance = newInstance();
    engineView.add(sig("mask_phone", "text", "text", "integer", "integer"));
    engineView.add(sig("mask_idcard", "text", "text", "integer"));
    service().importFromEngine(instance, connection, "postgresql");

    // the engine changed mask_phone; mask_idcard is untouched
    engineView.clear();
    engineView.add(sig("mask_phone", "text", "text"));
    engineView.add(sig("mask_idcard", "text", "text", "integer"));
    UdfCenterService.ImportResult second =
        service().importFromEngine(instance, connection, "postgresql");

    assertEquals(0, second.created());
    assertEquals(2, second.updated());
    assertEquals(1, policyService.udf(instance, "mask_phone").orElseThrow().signatures().size());
    assertEquals(2,
        policyService.udf(instance, "mask_idcard").orElseThrow().signatures().get(0).params().size());
    assertNotNull(policyService.udf(instance, "mask_phone").orElseThrow().lastSyncedAt());
  }

  @Test
  void resyncClassifiesAddedMissingAndChangedWithoutWriting() throws SQLException {
    String instance = newInstance();
    engineView.add(sig("mask_phone", "text", "text", "integer", "integer"));
    engineView.add(sig("mask_idcard", "text", "text", "integer"));
    service().importFromEngine(instance, connection, "postgresql");
    long versionBefore = policyService.configVersion(instance);

    engineView.clear();
    engineView.add(sig("mask_phone", "text", "text")); // signature changed
    engineView.add(sig("mask_extra", "text", "text")); // only in the engine

    UdfCenterService.ResyncResult diff = service().resync(instance, connection, "postgresql");

    assertEquals(List.of("mask_extra"), diff.addedInEngine());
    assertEquals(List.of("mask_idcard"), diff.missingInEngine());
    assertEquals(List.of("mask_phone"), diff.changedSignatures());
    assertEquals(2, diff.registry().size());
    // resync is read-only: neither registry nor config version moved
    assertEquals(versionBefore, policyService.configVersion(instance));
    assertEquals(3, policyService.udf(instance, "mask_phone").orElseThrow().signatures().get(0)
        .params().size());
  }

  @Test
  void deployTemplateExecutesDdlAndImportsTheResult() throws SQLException {
    String instance = newInstance();
    engineView.add(sig("mask_text", "text", "text"));

    UdfCenterService.DeployResult result =
        service().deployTemplate(instance, "mask_text", connection);

    assertTrue(result.ok());
    assertEquals("mask_text", result.template());
    ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
    verify(statement).execute(ddl.capture());
    assertTrue(ddl.getValue().startsWith("CREATE OR REPLACE FUNCTION mask_text"),
        ddl.getValue());
    // the post-deploy import registered the fresh signature
    UdfDefinition deployed = policyService.udf(instance, "mask_text").orElseThrow();
    assertEquals("IMPORTED", deployed.source());
    assertEquals(List.of("text"), deployed.signatures().get(0).params());
  }

  @Test
  void deployTemplateRejectsUnknownNameAsConfigError() throws SQLException {
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> service().deployTemplate(newInstance(), "mask_hash", connection));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("unknown udf template"), e.getMessage());
  }

  @Test
  void deployDdlExecutesVerbatimWithoutTouchingTheRegistry() throws SQLException {
    String instance = newInstance();
    UdfCenterService.DeployResult result =
        service().deployDdl(instance, "DROP FUNCTION IF EXISTS nope", connection);
    assertTrue(result.ok());
    verify(statement).execute("DROP FUNCTION IF EXISTS nope");
    assertEquals(List.of(), policyService.udfs(instance));
  }

  @Test
  void deployDdlFailureIsReportedNotThrown() throws SQLException {
    when(statement.execute(anyString())).thenThrow(new SQLException("syntax error at or near"));
    UdfCenterService.DeployResult result =
        service().deployDdl(newInstance(), "CREATE BOGUS", connection);
    assertEquals(false, result.ok());
    assertEquals("custom-ddl", result.template());
    assertTrue(result.error().contains("syntax error"), result.error());
  }

  @Test
  void unknownInstanceFollowsErrorContract() throws SQLException {
    SqlMaskException e = assertThrows(SqlMaskException.class, () ->
        service().importFromEngine("no_such_instance", connection, "postgresql"));
    assertEquals(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND, e.getCode());
  }

  @Test
  void twoArgConstructorKeepsRegisteredDefaults() {
    UdfDefinition legacy = new UdfDefinition("mask_phone", List.of(
        new UdfDefinition.UdfSignature(List.of("text"), "text")));
    assertEquals("REGISTERED", legacy.source());
    assertNull(legacy.lastSyncedAt());
  }
}
