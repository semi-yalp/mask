package io.sqlmask.policyserver.compile;

import io.sqlmask.common.effective.EffectiveConfigResponse;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InheritColumnsCompileTest {
  @Test
  void compileMarksInheritedColumnsInBinding() {
    EngineInstance instance = new EngineInstance("demo", "postgresql",
        List.of(new TableDef("db", "public", "customer",
            List.of(new ColumnDef("phone", "varchar"), new ColumnDef("name", "varchar")))));
    PolicyEntity policy = new PolicyEntity("crm.phone", PolicyType.DATAMASK, true, 3,
        new ResourceSelector("db", "public", "customer", List.of("phone", "name"),
            List.of("phone")),
        new SubjectSelector(Set.of(), Set.of("*")),
        "mask_phone", List.of(), null);
    EffectiveConfigResponse response =
        EffectiveConfigCompiler.compile(instance, List.of(policy), Subject.anonymous());
    EffectiveConfigResponse.ColumnBinding phone = response.config().columns().stream()
        .filter(b -> b.column().equals("phone")).findFirst().orElseThrow();
    EffectiveConfigResponse.ColumnBinding name = response.config().columns().stream()
        .filter(b -> b.column().equals("name")).findFirst().orElseThrow();
    assertTrue(phone.inheritOnCopy());
    assertFalse(name.inheritOnCopy());
  }
}
