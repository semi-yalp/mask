package io.sqlmask.policy.server;

import io.sqlmask.policy.PolicyException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyControllerTest {

  private final PolicyController controller = new PolicyController();

  @Test
  void parseReturnsStructuredSummary() {
    var response = controller.parse(new PolicyController.PolicyParseRequest("""
        policies:
          - name: mask-phone
            priority: 2
            resources:
              - {catalog: crm, schema: public, table: customer, column: phone}
            dataMaskItems:
              - {groups: ["*"], udf: mask_phone}
        """));
    assertEquals(1, response.policies().size());
    var dto = response.policies().get(0);
    assertEquals("mask-phone", dto.name());
    assertEquals(2, dto.priority());
    assertEquals("data_mask", dto.type());
    assertEquals(1, dto.itemCount());
    assertEquals("phone", dto.resources().get(0).column());
  }

  @Test
  void blankPayloadIsRejected() {
    PolicyException e = assertThrows(PolicyException.class,
        () -> controller.parse(new PolicyController.PolicyParseRequest(" ")));
    assertTrue(e.getMessage().contains("policyYaml is required"));
  }

  @Test
  void invalidPolicyYamlBubblesAsPolicyException() {
    assertThrows(PolicyException.class,
        () -> controller.parse(new PolicyController.PolicyParseRequest("policies: [ {")));
  }
}
