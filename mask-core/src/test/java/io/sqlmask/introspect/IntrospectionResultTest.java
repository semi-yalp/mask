package io.sqlmask.introspect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntrospectionResultTest {

  @Test
  void fourArgConstructorDefaultsKindToTable() {
    IntrospectionResult.TableInfo table = new IntrospectionResult.TableInfo(
        "crm", "public", "customer", List.of());
    assertEquals("table", table.kind());
  }

  @Test
  void explicitKindIsCarried() {
    IntrospectionResult.TableInfo table = new IntrospectionResult.TableInfo(
        "crm", "public", "customer_v", "view", List.of());
    assertEquals("view", table.kind());
  }

  @Test
  void blankOrNullKindNormalizesToTable() {
    assertEquals("table", new IntrospectionResult.TableInfo(
        "crm", "public", "t", (String) null, List.of()).kind());
    assertEquals("table", new IntrospectionResult.TableInfo(
        "crm", "public", "t", "  ", List.of()).kind());
  }

  @Test
  void isKnownMatchesOnlyTheThreeLowercaseConstants() {
    assertTrue(TableKind.isKnown("table"));
    assertTrue(TableKind.isKnown("view"));
    assertTrue(TableKind.isKnown("materialized_view"));
    assertFalse(TableKind.isKnown("TABLE"));
    assertFalse(TableKind.isKnown("viwe"));
    assertFalse(TableKind.isKnown(null));
  }
}
