package io.sqlmask.audit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditAdminHelperTest {

  static class CapturingRecorder implements AuditRecorder {
    final List<AuditEvent> events = new ArrayList<>();

    @Override
    public void record(AuditEvent event) {
      events.add(event);
    }
  }

  private MockHttpServletRequest request(String ip, String authKind) {
    MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/instances");
    r.setRemoteAddr(ip);
    if (authKind != null) {
      r.setAttribute(AuditEvents.AUTH_KIND_ATTRIBUTE, authKind);
    }
    return r;
  }

  @Test
  void successRecordsAdminChangeWithDurationAndContext() {
    CapturingRecorder recorder = new CapturingRecorder();
    AuditAdminHelper helper = new AuditAdminHelper(recorder, "sql-mask",
        t -> t.getClass().getSimpleName());
    String result = helper.adminChange(request("10.1.1.1", "API_KEY"), "CREATE", "INSTANCE",
        null, "crm", () -> Map.of("dialect", "postgresql"), () -> "ok");
    assertEquals("ok", result);
    assertEquals(1, recorder.events.size());
    AuditEvent e = recorder.events.get(0);
    assertEquals(AuditEvent.ADMIN_CHANGE, e.eventType());
    assertEquals(AuditEvent.SUCCESS, e.outcome());
    assertEquals("CREATE", e.action());
    assertEquals("INSTANCE", e.resourceType());
    assertEquals("crm", e.resourceName());
    assertEquals("10.1.1.1", e.sourceIp());
    assertEquals("API_KEY", e.authKind());
    assertEquals(Map.of("dialect", "postgresql"), e.detail());
    assertEquals("sql-mask", e.service());
  }

  @Test
  void failureRecordsThenRethrowsOriginalException() {
    CapturingRecorder recorder = new CapturingRecorder();
    AuditAdminHelper helper = new AuditAdminHelper(recorder, "sql-mask",
        t -> t instanceof IllegalArgumentException ? "CONFIG_ERROR" : "INTERNAL_ERROR");
    RuntimeException boom = new IllegalArgumentException("bad name");
    RuntimeException thrown = assertThrows(IllegalArgumentException.class,
        () -> helper.adminChange(request("127.0.0.1", null), "DELETE", "POLICY", "crm",
            "mask-phone", () -> Map.of(), () -> {
              throw boom;
            }));
    assertSame(boom, thrown);
    assertEquals(1, recorder.events.size());
    AuditEvent e = recorder.events.get(0);
    assertEquals(AuditEvent.FAILURE, e.outcome());
    assertEquals("CONFIG_ERROR", e.errorCode());
    assertEquals("bad name", e.errorMessage());
    assertEquals("ANONYMOUS", e.authKind());
  }

  @Test
  void detailSupplierFailureOnFailurePathDoesNotMaskOriginalError() {
    CapturingRecorder recorder = new CapturingRecorder();
    AuditAdminHelper helper = new AuditAdminHelper(recorder, "sql-mask",
        t -> "X");
    assertThrows(IllegalStateException.class,
        () -> helper.adminChange(request(null, null), "UPDATE", "TABLES", "crm", null,
            () -> {
              throw new RuntimeException("detail blew up");
            }, () -> {
              throw new IllegalStateException("original");
            }));
    assertEquals(AuditEvent.FAILURE, recorder.events.get(0).outcome());
    assertNull(recorder.events.get(0).detail());
  }

  @Test
  void authKindDefaultsToAnonymousAndNullIpPassesThrough() {
    CapturingRecorder recorder = new CapturingRecorder();
    AuditAdminHelper helper = new AuditAdminHelper(recorder, "mask-metadata", t -> "X");
    helper.adminChange(request(null, null), "CREATE", "INSTANCE", null, "pg",
        () -> Map.of(), () -> 1);
    assertEquals("ANONYMOUS", recorder.events.get(0).authKind());
    assertNull(recorder.events.get(0).sourceIp());
  }

  @Test
  void throwingRecorderDoesNotMaskOriginalErrorOrLoseResult() {
    // AuditRecorder implementations are contractually never-throw; this guards
    // the best-effort fallback so a misbehaving recorder can neither replace
    // the rethrown business exception nor lose a completed result.
    AuditRecorder throwing = event -> {
      throw new RuntimeException("recorder blew up");
    };
    AuditAdminHelper helper = new AuditAdminHelper(throwing, "sql-mask", t -> "X");
    RuntimeException boom = new IllegalStateException("original");
    RuntimeException thrown = assertThrows(IllegalStateException.class,
        () -> helper.adminChange(request("127.0.0.1", null), "DELETE", "POLICY", "crm",
            "mask-phone", () -> Map.of(), () -> {
              throw boom;
            }));
    assertSame(boom, thrown);
    String result = helper.adminChange(request("127.0.0.1", null), "CREATE", "INSTANCE",
        null, "pg", () -> Map.of(), () -> "ok");
    assertEquals("ok", result);
  }
}
