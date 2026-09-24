package io.sqlmask.server;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureObservability
@AutoConfigureMockMvc
class RewriteMetricsEndpointTest {

  private static final String YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            columns:
              - name: id
                type: bigint
              - name: phone
                type: varchar
      columns:
        - catalog: crm
          schema: public
          table: customer
          column: phone
          policy: mask_phone
      policies:
        mask_phone:
          udf: mask_phone
          arguments: [3, 4]
      """;

  @Autowired
  private MockMvc mvc;

  @Autowired
  private MeterRegistry registry;

  @Test
  void successfulRewriteIncrementsSuccessCounter() throws Exception {
    double before = counter("SUCCESS");
    mvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON)
            .content(body("postgresql")))
        .andExpect(status().isOk());
    assertThat(counter("SUCCESS")).isEqualTo(before + 1);
  }

  @Test
  void failedRewriteIncrementsFailureCounterWithCode() throws Exception {
    double before = failures("PARSE_ERROR");
    mvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON)
            .content(new ObjectMapper().writeValueAsString(Map.of(
                "metadataYaml", YAML, "sql", "SELEKT nonsense", "dialect", "postgresql"))))
        .andExpect(status().isBadRequest());
    assertThat(failures("PARSE_ERROR")).isEqualTo(before + 1);
  }

  @Test
  void unknownDialectCountsUnderInvalidSentinel() throws Exception {
    double before = registry.counter("sqlmask.rewrite.requests", "dialect", "invalid",
        "outcome", "FAILURE", "masked", "false", "row_filtered", "false").count();
    mvc.perform(post("/api/rewrite").contentType(MediaType.APPLICATION_JSON)
            .content(new ObjectMapper().writeValueAsString(Map.of(
                "metadataYaml", YAML, "sql", "SELECT 1", "dialect", "JUNK"))))
        .andExpect(status().is4xxClientError());
    assertThat(registry.counter("sqlmask.rewrite.requests", "dialect", "invalid",
        "outcome", "FAILURE", "masked", "false", "row_filtered", "false").count())
        .isEqualTo(before + 1);
  }

  private double counter(String outcome) {
    // registry.counter 对不存在的序列返回零值计数器——用于"动作前"读数不会抛异常
    return registry.counter("sqlmask.rewrite.requests", "dialect", "postgresql",
        "outcome", outcome, "masked", "true", "row_filtered", "false").count();
  }

  private double failures(String code) {
    return registry.counter("sqlmask.rewrite.failures",
        "dialect", "postgresql", "code", code).count();
  }

  private static String body(String dialect) throws Exception {
    return new ObjectMapper().writeValueAsString(Map.of(
        "metadataYaml", YAML, "sql", "SELECT phone FROM customer;", "dialect", dialect));
  }
}
