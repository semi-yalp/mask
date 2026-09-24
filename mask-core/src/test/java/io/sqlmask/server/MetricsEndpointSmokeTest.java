package io.sqlmask.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 审计管线指标只有 audit.enabled=true 时注册(默认关闭,spec §7 新契约)。 */
@SpringBootTest(properties = "audit.enabled=true")
@AutoConfigureObservability
@AutoConfigureMockMvc
class MetricsEndpointSmokeTest {

  @Autowired
  private MockMvc mvc;

  @Test
  void exposesPrometheusScrapeEndpointWithCommonTag() throws Exception {
    mvc.perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("jvm_memory_used_bytes")))
        .andExpect(content().string(containsString("application=\"sql-mask\"")))
        // 审计管道指标（spec §3.5）
        .andExpect(content().string(containsString("sqlmask_audit_queue_depth")))
        .andExpect(content().string(containsString("sqlmask_audit_queue_capacity")));
  }
}
