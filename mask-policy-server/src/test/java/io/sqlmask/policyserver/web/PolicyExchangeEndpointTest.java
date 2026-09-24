package io.sqlmask.policyserver.web;

import io.sqlmask.policyserver.PolicyService;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The policies.yaml exchange surface: export download and import upsert. */
@SpringBootTest
@AutoConfigureMockMvc
class PolicyExchangeEndpointTest {

  private static final String EXPORT_URL = "/api/instances/pg_prod/policies/export";
  private static final String IMPORT_URL = "/api/instances/pg_prod/policies/import";

  @Autowired
  private MockMvc mvc;

  @Autowired
  private PolicyService service;

  @BeforeEach
  void cleanUp() {
    for (String policy : List.of("mask-phone", "filter-archived")) {
      try {
        service.deletePolicy("pg_prod", policy);
      } catch (io.sqlmask.error.SqlMaskException absent) {
        // 无遗留
      }
    }
    try {
      service.deleteUdf("pg_prod", "mask_phone");
    } catch (io.sqlmask.error.SqlMaskException absent) {
      // 无遗留
    }
    try {
      service.deleteInstance("pg_prod");
    } catch (io.sqlmask.error.SqlMaskException absent) {
      // 无遗留
    }
  }

  private void createInstanceWithUdf() {
    service.createInstance("pg_prod", "postgresql", List.of(
        new TableDef("crm", "public", "customer",
            List.of(new ColumnDef("phone", "varchar"), new ColumnDef("email", "varchar"))),
        new TableDef("crm", "public", "orders",
            List.of(new ColumnDef("id", "bigint"), new ColumnDef("status", "varchar")))));
    service.createUdf("pg_prod", new UdfDefinition("mask_phone", List.of(
        new UdfDefinition.UdfSignature(List.of("varchar", "integer", "integer"), "varchar"))));
  }

  @Test
  void exportDownloadsAllPoliciesAsYamlAttachment() throws Exception {
    createInstanceWithUdf();
    service.createPolicy("pg_prod", new PolicyEntity("mask-phone", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(3, 4), null));
    service.createPolicy("pg_prod", new PolicyEntity("filter-archived",
        PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "orders", List.of()),
        null, null, "status <> 'archived'"));

    mvc.perform(get(EXPORT_URL))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/yaml"))
        .andExpect(header().string("Content-Disposition",
            containsString("attachment; filename=\"policies-pg_prod.yaml\"")))
        .andExpect(content().string(containsString("policies:")))
        .andExpect(content().string(containsString("name: mask-phone")))
        .andExpect(content().string(containsString("name: filter-archived")));
  }

  @Test
  void importAppliesTheFileAndReportsCounts() throws Exception {
    createInstanceWithUdf();

    mvc.perform(post(IMPORT_URL).contentType(MediaType.TEXT_PLAIN).content("""
        policies:
          - name: mask-phone
            resources:
              - catalog: crm
                schema: public
                table: customer
                column: [phone, email]
            dataMaskItems:
              - users: ["*"]
                udf: mask_phone
                arguments: [3, 4]
          - name: filter-archived
            resources:
              - catalog: crm
                schema: public
                table: orders
            rowFilterItems:
              - users: [alice]
                filterExpr: "status <> 'archived'"
        """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(2))
        .andExpect(jsonPath("$.updated").value(0));

    assertEquals(2, service.policies("pg_prod").size());
    service.policies("pg_prod").stream()
        .filter(p -> p.name().equals("mask-phone")).findFirst().orElseThrow();
  }

  @Test
  void importedFileRoundTripsThroughExport() throws Exception {
    createInstanceWithUdf();
    service.createPolicy("pg_prod", new PolicyEntity("mask-phone", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(3, 4), null));
    service.createPolicy("pg_prod", new PolicyEntity("filter-archived",
        PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "orders", List.of()),
        null, null, "status <> 'archived'"));

    String exported = mvc.perform(get(EXPORT_URL)).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    service.deletePolicy("pg_prod", "mask-phone");
    service.deletePolicy("pg_prod", "filter-archived");

    mvc.perform(post(IMPORT_URL).contentType(MediaType.TEXT_PLAIN).content(exported))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.created").value(2))
        .andExpect(jsonPath("$.updated").value(0));

    assertEquals(List.of("mask-phone", "filter-archived"),
        service.policies("pg_prod").stream().map(PolicyEntity::name).toList());
  }

  @Test
  void importingAnInvalidFileIsAConfigErrorWithYamlPath() throws Exception {
    createInstanceWithUdf();

    mvc.perform(post(IMPORT_URL).contentType(MediaType.TEXT_PLAIN)
            .content("policies: [ {"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFIG_ERROR"))
        .andExpect(jsonPath("$.message").value(containsString("policies.yaml: invalid YAML")));
  }

  @Test
  void unknownInstanceIsNotFoundForBothEndpoints() throws Exception {
    mvc.perform(get("/api/instances/nope/policies/export"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POLICY_INSTANCE_NOT_FOUND"));
    mvc.perform(post("/api/instances/nope/policies/import")
            .contentType(MediaType.TEXT_PLAIN).content("policies: []"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POLICY_INSTANCE_NOT_FOUND"));
  }
}