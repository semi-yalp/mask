package io.sqlmask.server;

import io.micrometer.core.instrument.MeterRegistry;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureObservability
@AutoConfigureMockMvc
class EffectiveConfigMetricsTest {

  private static final String INSTANCE = "metrics_eff_pg";

  @Autowired
  private MockMvc mvc;

  @Autowired
  private MeterRegistry registry;

  @Autowired
  private PolicyService service;

  @BeforeEach
  void setUp() {
    try {
      service.createInstance(INSTANCE, "postgresql", List.of(
          new TableDef("crm", "public", "customer",
              List.of(new ColumnDef("phone", "varchar")))));
    } catch (SqlMaskException alreadyExists) {
      // 上下文复用
    }
  }

  @Test
  void pullUpdatesGauges() throws Exception {
    mvc.perform(get("/api/effective/" + INSTANCE))
        .andExpect(status().isOk());

    assertThat(registry.get("sqlmask.effective.pull")
        .tag("instance", INSTANCE).tag("dialect", "postgresql").tag("outcome", "SUCCESS")
        .counter().count()).isGreaterThanOrEqualTo(1.0);
    assertThat(registry.get("sqlmask.effective.config_version")
        .tag("instance", INSTANCE).gauge().value()).isGreaterThanOrEqualTo(1.0);
  }

  @Test
  void unknownInstanceCollapsesIntoNotFoundSentinel() throws Exception {
    double before = registry.counter("sqlmask.effective.pull",
        "instance", "(not_found)", "dialect", "(not_found)", "outcome", "FAILURE").count();
    mvc.perform(get("/api/effective/no_such_instance_metrics"))
        .andExpect(status().isBadRequest());

    assertThat(registry.get("sqlmask.effective.pull")
        .tag("instance", "(not_found)").tag("dialect", "(not_found)").tag("outcome", "FAILURE")
        .counter().count()).isEqualTo(before + 1);
    assertThat(registry.getMeters()).noneMatch(m ->
        m.getId().getName().equals("sqlmask.effective.pull")
            && m.getId().getTag("instance").equals("no_such_instance_metrics"));
  }
}
