package io.sqlmask.server;

import io.sqlmask.config.source.PolicyServiceConfigSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Manual cache drop and the scheduled refresh entry (no live service needed). */
class CacheRefreshControllerTest {

  @Test
  void cachesPerInstanceAndClearsSelectivelyOrWholly() {
    InstanceConfigSources sources = new InstanceConfigSources("http://127.0.0.1:1", "k");
    PolicyServiceConfigSource a = sources.get("a");
    assertThat(sources.get("a")).isSameAs(a);
    assertThat(sources.get("b")).isNotSameAs(a);

    assertThat(sources.clear("a")).isEqualTo(1);
    assertThat(sources.get("a")).isNotSameAs(a);
    assertThat(sources.clear(null)).isEqualTo(2); // a (rebuilt above) + b
  }

  @Test
  void scheduledRefreshSurvivesUnreachableService() {
    InstanceConfigSources sources = new InstanceConfigSources("http://127.0.0.1:1", "k");
    sources.get("a");
    assertThatCode(sources::refreshAll).doesNotThrowAnyException(); // stale semantics
  }

  @Test
  void refreshEndpointReportsClearedCountAndAcceptsEmptyBody() throws Exception {
    InstanceConfigSources sources = new InstanceConfigSources("http://127.0.0.1:1", "k");
    sources.get("a");
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new CacheRefreshController(sources)).build();

    mvc.perform(post("/admin/cache/refresh")
            .contentType("application/json")
            .content("{\"instance\": \"a\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cleared").value(1));
    mvc.perform(post("/admin/cache/refresh"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cleared").value(0));
  }
}
