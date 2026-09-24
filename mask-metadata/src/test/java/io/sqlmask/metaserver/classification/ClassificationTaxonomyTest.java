package io.sqlmask.metaserver.classification;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassificationTaxonomyTest {

  @Test
  void heuristicsMatchByLoweredSubstring() {
    assertGuess("phone_number", "CONTACT", "HIGH");
    assertGuess("user_email", "CONTACT", "MEDIUM");
    assertGuess("mobile", "CONTACT", "HIGH");
    assertGuess("id_card_no", "IDENTITY", "HIGH");
    assertGuess("passport_no", "IDENTITY", "HIGH");
    assertGuess("full_name", "PII", "MEDIUM");
    assertGuess("birth_date", "PII", "MEDIUM");
    assertGuess("home_address", "LOCATION", "LOW");
    assertGuess("postal_code", "LOCATION", "LOW");
    assertGuess("account_balance", "FINANCE", "HIGH");
    assertGuess("card_no", "FINANCE", "HIGH");
    assertGuess("diagnosis_text", "MEDICAL", "HIGH");
  }

  @Test
  void heuristicsAreCaseInsensitive() {
    assertGuess("PHONE_NUMBER", "CONTACT", "HIGH");
    assertGuess("ContactEmail", "CONTACT", "MEDIUM");
  }

  @Test
  void neutralColumnsDoNotMatch() {
    assertTrue(ClassificationTaxonomy.guess("pg", "a.b.c.id", "id").isEmpty());
    assertTrue(ClassificationTaxonomy.guess("pg", "a.b.c.qty", "quantity").isEmpty());
    assertTrue(ClassificationTaxonomy.guess("pg", "a.b.c.created", "created_at").isEmpty());
    assertTrue(ClassificationTaxonomy.guess("pg", "a.b.c.blank", null).isEmpty());
    assertTrue(ClassificationTaxonomy.guess("pg", "a.b.c.blank", "").isEmpty());
  }

  @Test
  void guessCarriesInstanceColumnKeyAndAutoSource() {
    Classification guess = ClassificationTaxonomy
        .guess("pg_prod", "crm.public.customer.phone_number", "phone_number").orElseThrow();
    assertEquals("pg_prod", guess.instance());
    assertEquals("crm.public.customer.phone_number", guess.columnKey());
    assertEquals(Classification.SOURCE_AUTO, guess.source());
    assertNull(guess.note());
    assertNull(guess.updatedAt());
  }

  @Test
  void earlierRuleWinsOnOverlap() {
    // "telephone" hits phone (CONTACT/HIGH) before any later rule
    assertGuess("telephone", "CONTACT", "HIGH");
    // "card_no" is finance, not identity: no identity fragment matches it
    assertGuess("card_no", "FINANCE", "HIGH");
  }

  @Test
  void categoryValidationNormalizesAndRejects() {
    assertEquals("FINANCE", ClassificationTaxonomy.requireCategory("finance"));
    assertEquals("PII", ClassificationTaxonomy.requireCategory("  PII  "));
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> ClassificationTaxonomy.requireCategory("SECRET"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("IDENTITY, PII, CONTACT, FINANCE, LOCATION, MEDICAL, OTHER"),
        e.getMessage());
  }

  @Test
  void levelValidationNormalizesAndRejects() {
    assertEquals("HIGH", ClassificationTaxonomy.requireLevel("high"));
    assertEquals("LOW", ClassificationTaxonomy.requireLevel("Low"));
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> ClassificationTaxonomy.requireLevel("EXTREME"));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, e.getCode());
    assertTrue(e.getMessage().contains("HIGH, MEDIUM, LOW"), e.getMessage());
  }

  @Test
  void blankCategoryOrLevelRejected() {
    SqlMaskException category = assertThrows(SqlMaskException.class,
        () -> ClassificationTaxonomy.requireCategory(" "));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, category.getCode());
    SqlMaskException level = assertThrows(SqlMaskException.class,
        () -> ClassificationTaxonomy.requireLevel(null));
    assertEquals(SqlMaskException.Code.CONFIG_ERROR, level.getCode());
  }

  private static void assertGuess(String columnName, String category, String level) {
    Optional<Classification> guess = ClassificationTaxonomy.guess("pg", "a.b.c." + columnName,
        columnName);
    assertTrue(guess.isPresent(), columnName + " should match a rule");
    assertEquals(category, guess.get().category(), columnName);
    assertEquals(level, guess.get().level(), columnName);
  }
}
