package io.sqlmask.server;

import io.sqlmask.audit.AuditSearchResult;
import io.sqlmask.audit.AuditSearchClient;
import io.sqlmask.audit.AuditSearchUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuditQueryEndpointTest {

  @Autowired
  private MockMvc mvc;

  @MockBean
  private AuditSearchClient searchClient;

  @Test
  void returnsMappedPage() throws Exception {
    when(searchClient.search(any(io.sqlmask.audit.AuditQuery.class))).thenReturn(
        new AuditSearchResult(2,
            List.of(Map.of("eventType", "REWRITE"), Map.of("eventType", "ADMIN_CHANGE"))));
    mvc.perform(get("/api/audit/events").param("eventType", "REWRITE")
            .param("from", "2026-09-16T00:00:00Z").param("to", "2026-09-17T00:00:00Z")
            .param("page", "0").param("size", "50"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(50))
        .andExpect(jsonPath("$.events[0].eventType").value("REWRITE"));
  }

  @Test
  void rejectsOversizedPageAndOversizedRange() throws Exception {
    when(searchClient.search(any(io.sqlmask.audit.AuditQuery.class))).thenReturn(
        new AuditSearchResult(0, List.of()));
    mvc.perform(get("/api/audit/events").param("size", "201")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/audit/events")
            .param("from", "2026-09-01T00:00:00Z").param("to", "2026-09-16T00:00:00Z"))
        .andExpect(status().isBadRequest()); // 15 days > 7d cap
    mvc.perform(get("/api/audit/events").param("from", "not-a-time"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void esOutageIs502WithCode() throws Exception {
    when(searchClient.search(any(io.sqlmask.audit.AuditQuery.class)))
        .thenThrow(new AuditSearchUnavailableException("down",
            new java.io.IOException("refused")));
    mvc.perform(get("/api/audit/events"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("AUDIT_SEARCH_UNAVAILABLE"));
  }
}
