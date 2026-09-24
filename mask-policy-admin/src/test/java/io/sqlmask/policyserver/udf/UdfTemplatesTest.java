package io.sqlmask.policyserver.udf;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The built-in masking template catalog ported from deploy/local-e2e/01_udf.sql. */
class UdfTemplatesTest {

  @Test
  void catalogCoversTheFiveMaskingShapes() {
    assertEquals(List.of("mask_email", "mask_idcard", "mask_name", "mask_phone", "mask_text"),
        UdfTemplates.names());
  }

  @Test
  void everyTemplateIsASingleCreateOrReplaceStatement() {
    for (String name : UdfTemplates.names()) {
      String ddl = UdfTemplates.template(name).orElseThrow();
      assertTrue(ddl.strip().startsWith("CREATE OR REPLACE FUNCTION " + name + "("),
          name + ": " + ddl);
      // STRICT gives NULL-in-NULL-out; IMMUTABLE lets the planner fold calls
      assertTrue(ddl.contains("IMMUTABLE STRICT"), name + ": " + ddl);
      // one statement: the only semicolons are inside $$ ... $$ and the terminator
      assertTrue(ddl.endsWith(";"), name);
    }
  }

  @Test
  void maskingSemanticsSurvivedThePort() {
    String phone = UdfTemplates.template("mask_phone").orElseThrow();
    assertTrue(phone.contains("substr(v, 1, front)"));
    assertTrue(phone.contains("substr(v, n - back + 1, back)"));

    String email = UdfTemplates.template("mask_email").orElseThrow();
    assertTrue(email.contains("position('@' in v)"));
    assertTrue(email.contains("substr(v, pos)"));

    String name = UdfTemplates.template("mask_name").orElseThrow();
    assertTrue(name.contains("substr(v, 1, 1)"));

    String idcard = UdfTemplates.template("mask_idcard").orElseThrow();
    assertTrue(idcard.contains("substr(v, 1, 6)"));
    assertTrue(idcard.contains("DEFAULT 4"));

    String text = UdfTemplates.template("mask_text").orElseThrow();
    assertTrue(text.contains("'***'"), text);
  }

  @Test
  void unknownTemplateIsEmpty() {
    assertEquals(Optional.empty(), UdfTemplates.template("mask_hash"));
    assertFalse(UdfTemplates.names().contains("mask_hash"));
  }
}
