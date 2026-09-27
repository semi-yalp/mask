package io.sqlmask.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The metadata pull dials out with operator credentials: it is an admin
 * action behind the same fail-closed gate as /admin/cache/refresh. No
 * SQLMASK_ADMIN_API_KEY ⇒ reject instead of letting anyone probe intranet
 * hosts through the service.
 */
@SpringBootTest(properties = "SQLMASK_ADMIN_API_KEY=metadata-secret")
@AutoConfigureMockMvc
class MetadataPullGateTest {

  @Autowired
  MockMvc mockMvc;

  private static final String BODY =
      "{\"engine\":\"bogus\",\"database\":\"d\",\"user\":\"u\",\"password\":\"p\"}";

  @Test
  void pullWithoutKeyIsRejected() throws Exception {
    mockMvc.perform(post("/api/metadata/pull").servletPath("/api/metadata/pull")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void pullWithWrongKeyIsRejected() throws Exception {
    mockMvc.perform(post("/api/metadata/pull").servletPath("/api/metadata/pull")
            .header("X-Api-Key", "nope")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void pullWithAdminKeyPassesTheGate() throws Exception {
    // 网关放行后停在 CONFIG_ERROR(未知引擎)而非 401——证明鉴权已过、且不会外连
    mockMvc.perform(post("/api/metadata/pull").servletPath("/api/metadata/pull")
            .header("X-Api-Key", "metadata-secret")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isBadRequest());
  }
}
