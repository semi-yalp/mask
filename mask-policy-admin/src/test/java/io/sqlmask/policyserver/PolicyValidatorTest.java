package io.sqlmask.policyserver;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policy.model.SubjectSelector;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyValidatorTest {

  // `status`/`id` are declared because the row-filter cases below use them:
  // "status = 'active'" is an ACCEPTED baseline filter and "id > 0" is a second
  // accepted filter — row-filter overlap is allowed and the filters compose
  // with AND at compile time. Both columns must stay declared: the
  // RowFilterRegistry whitelist rejects filters referencing undeclared columns.
  private static final EngineInstance INSTANCE = new EngineInstance("pg_prod", "postgresql",
      List.of(new TableDef("crm", "public", "customer", List.of(
          new ColumnDef("phone", "varchar"), new ColumnDef("email", "varchar"),
          new ColumnDef("status", "varchar"), new ColumnDef("id", "varchar")))));

  private static final List<UdfDefinition> UDFS = List.of(
      new UdfDefinition("mask_phone", List.of(
          new UdfDefinition.UdfSignature(List.of("varchar", "integer", "integer"), "varchar"),
          new UdfDefinition.UdfSignature(List.of("bigint", "integer", "integer"), "varchar"))),
      new UdfDefinition("mask_ssn", List.of(
          new UdfDefinition.UdfSignature(List.of("varchar"), "varchar"))));

  private final PolicyValidator validator = new PolicyValidator();

  private PolicyEntity datamask(String name, String table, List<String> columns) {
    return new PolicyEntity(name, PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", table, columns), "mask_phone", List.of(3, 4), null);
  }

  @Test
  void acceptsValidDatamaskPolicy() {
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, UDFS, datamask("phone_mask", "customer", List.of("phone")), List.of()));
  }

  @Test
  void rejectsUnknownTableAndColumn() {
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validatePolicy(INSTANCE, UDFS, datamask("p", "no_such", List.of("phone")), List.of()))
        .getMessage().contains("no_such"));
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validatePolicy(INSTANCE, UDFS, datamask("p", "customer", List.of("fax")), List.of()))
        .getMessage().contains("fax"));
  }

  @Test
  void rejectsOverlapWithEnabledPolicy() {
    PolicyEntity existing = datamask("a_mask", "customer", List.of("phone", "email"));
    PolicyEntity overlapping = datamask("b_mask", "customer", List.of("email"));
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, overlapping, List.of(existing)));
    assertTrue(e.getMessage().contains("a_mask") && e.getMessage().contains("b_mask"));
  }

  @Test
  void rejectsSamePriorityOverlapWithPrioritiesInMessage() {
    PolicyEntity existing = new PolicyEntity("a_mask", PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4), null);
    PolicyEntity overlapping = new PolicyEntity("b_mask", PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4), null);
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, overlapping, List.of(existing)));
    assertTrue(e.getMessage().contains("a_mask") && e.getMessage().contains("b_mask"));
    assertTrue(e.getMessage().contains("priority 0"));
  }

  @Test
  void rejectsSamePriorityOverlapAboveIntegerCache() {
    PolicyEntity existing = new PolicyEntity("a_mask", PolicyType.DATAMASK, true, 200,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4), null);
    PolicyEntity overlapping = new PolicyEntity("b_mask", PolicyType.DATAMASK, true, 200,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4), null);
    assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, overlapping, List.of(existing)));
  }

  @Test
  void allowsOverlapWithDifferentPriority() {
    PolicyEntity existing = new PolicyEntity("a_mask", PolicyType.DATAMASK, true, 10,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4), null);
    PolicyEntity overlapping = new PolicyEntity("b_mask", PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("*"), Set.of()), "mask_phone", List.of(3, 4), null);
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, UDFS, overlapping, List.of(existing)));
  }

  @Test
  void allowsSamePriorityWhenSubjectsDisjoint() {
    PolicyEntity aliceOnly = new PolicyEntity("a_mask", PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("alice"), Set.of()), "mask_phone", List.of(3, 4), null);
    PolicyEntity bobOnly = new PolicyEntity("b_mask", PolicyType.DATAMASK, true, 0,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        new SubjectSelector(Set.of("bob"), Set.of()), "mask_phone", List.of(3, 4), null);
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, UDFS, bobOnly, List.of(aliceOnly)));
  }

  @Test
  void disabledPoliciesDoNotBlock() {
    PolicyEntity disabled = new PolicyEntity("a_mask", PolicyType.DATAMASK, false,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(), null);
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, UDFS, datamask("b_mask", "customer", List.of("phone")), List.of(disabled)));
  }

  private PolicyEntity rowFilter(String name, String expr) {
    return new PolicyEntity(name, PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()), null, List.of(), expr);
  }

  @Test
  void allowsRowFilterOverlap() {
    PolicyEntity rf = rowFilter("rf", "status = 'active'");
    assertDoesNotThrow(() -> validator.validatePolicy(INSTANCE, UDFS, rf, List.of()));
    PolicyEntity rf2 = rowFilter("rf2", "id > 0");
    assertDoesNotThrow(() -> validator.validatePolicy(INSTANCE, UDFS, rf2, List.of(rf)));
  }

  @Test
  void rejectsMalformedFilterExpressionViaWhitelist() {
    PolicyEntity rf = new PolicyEntity("rf", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()), null, List.of(),
        "status = (SELECT status FROM t)");
    assertThrows(SqlMaskException.class, () -> validator.validatePolicy(INSTANCE, UDFS, rf, List.of()));
  }

  @Test
  void rejectsGlobPatternInTableLevel() {
    PolicyEntity p = datamask("p", "cust*", List.of("phone"));
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, p, List.of()));
    assertTrue(e.getMessage().contains("glob"));
    assertTrue(e.getCode() == SqlMaskException.Code.CONFIG_ERROR);
  }

  @Test
  void rejectsGlobPatternInColumnLevel() {
    PolicyEntity p = datamask("p", "customer", List.of("phon*"));
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, p, List.of()));
    assertTrue(e.getMessage().contains("glob"));
  }

  @Test
  void rejectsGlobPatternInRowFilterTableLevel() {
    PolicyEntity rf = new PolicyEntity("rf", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "tmp_*", List.of()), null, List.of(),
        "status = 'active'");
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, rf, List.of()));
    assertTrue(e.getMessage().contains("glob"));
  }

  @Test
  void rejectsBadInstanceNameDialectAndTypes() {
    assertThrows(SqlMaskException.class, () -> validator.validateInstance(
        new EngineInstance("bad name!", "postgresql", INSTANCE.tables())));
    assertThrows(SqlMaskException.class, () -> validator.validateInstance(
        new EngineInstance("x", "oracle", INSTANCE.tables())));
    assertThrows(SqlMaskException.class, () -> validator.validateInstance(
        new EngineInstance("x", "trino", List.of(
            new TableDef("c", "s", "t", List.of(new ColumnDef("a", "datetime")))))));
  }

  @Test
  void rejectsZeroColumnTable() {
    assertTrue(assertThrows(SqlMaskException.class, () -> validator.validateInstance(
            new EngineInstance("x", "postgresql", List.of(
                new TableDef("c", "s", "t", List.of())))))
        .getMessage().contains("must declare at least one column"));
  }

  // ---- DATAMASK 策略 udf 四步解析 ----

  @Test
  void rejectsUnknownUdfName() {
    PolicyEntity p = new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_nonexistent", List.of(3, 4), null);
    assertTrue(assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, p, List.of()))
        .getMessage().contains("unknown udf 'mask_nonexistent'"));
  }

  @Test
  void rejectsArityMismatch() {
    PolicyEntity p = new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_ssn", List.of(1), null);
    assertTrue(assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, p, List.of()))
        .getMessage().contains("mask_ssn"));
  }

  @Test
  void rejectsCrossFamilyScalarArgument() {
    PolicyEntity p = new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of("abc", 4), null);
    assertTrue(assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, p, List.of()))
        .getMessage().contains("argument"));
  }

  @Test
  void rejectsColumnWithoutMatchingOverload() {
    // status 是 varchar 但 id 在 fixture 里也是 varchar；借 bigint 列构造失配：
    // phone(varchar) 有重载，改为选 id 列并把策略指到 mask_ssn（1 参签名）不行——
    // 用一个 bigint 列的实例直接验证。
    EngineInstance bigintInstance = new EngineInstance("pg_b", "postgresql",
        List.of(new TableDef("crm", "public", "ledger", List.of(
            new ColumnDef("acct", "bigint")))));
    PolicyEntity p = new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "ledger", List.of("acct")),
        "mask_ssn", List.of(), null);
    assertTrue(assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(bigintInstance, UDFS, p, List.of()))
        .getMessage().contains("column 'acct'"));
  }

  @Test
  void resolvesOverloadPerColumn() {
    // 一条策略同时选 varchar 列（走第一签名）与 bigint 列（走第二签名）。
    EngineInstance mixed = new EngineInstance("pg_m", "postgresql",
        List.of(new TableDef("crm", "public", "t", List.of(
            new ColumnDef("phone", "varchar"), new ColumnDef("uid", "bigint")))));
    PolicyEntity p = new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "t", List.of("phone", "uid")),
        "mask_phone", List.of(3, 4), null);
    assertDoesNotThrow(() -> validator.validatePolicy(mixed, UDFS, p, List.of()));
  }

  @Test
  void rejectsPrecisionMismatchAsNoOverload() {
    // varchar(10) ≠ varchar（precision 参与精确匹配）。
    EngineInstance sized = new EngineInstance("pg_s", "postgresql",
        List.of(new TableDef("crm", "public", "t", List.of(
            new ColumnDef("phone", "varchar(10)")))));
    PolicyEntity p = new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "t", List.of("phone")),
        "mask_ssn", List.of(), null);
    assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(sized, UDFS, p, List.of()));
  }

  @Test
  void policiesFailingUdfResolutionNamesEnabledDatamaskOnly() {
    PolicyEntity enabledMask = datamask("phone_mask", "customer", List.of("phone"));
    PolicyEntity disabledMask = new PolicyEntity("off", PolicyType.DATAMASK, false,
        new ResourceSelector("crm", "public", "customer", List.of("phone")),
        "mask_phone", List.of(3, 4), null);
    PolicyEntity rowFilter = new PolicyEntity("rf", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()),
        null, List.of(), "status = 'active'");
    // 空注册表下只有 enabled 的 datamask 失效。
    assertEquals(List.of("phone_mask"),
        validator.policiesFailingUdfResolution(INSTANCE, List.of(),
            List.of(enabledMask, disabledMask, rowFilter)));
    // 注册表齐备时无人失效。
    assertTrue(validator.policiesFailingUdfResolution(INSTANCE, UDFS,
        List.of(enabledMask, disabledMask, rowFilter)).isEmpty());
  }

  // ---- UDF 写入校验 ----

  private static UdfDefinition udf(String name, UdfDefinition.UdfSignature... signatures) {
    return new UdfDefinition(name, List.of(signatures));
  }

  @Test
  void acceptsValidUdfDefinition() {
    assertDoesNotThrow(() -> validator.validateUdf(INSTANCE, udf("mask_phone",
        new UdfDefinition.UdfSignature(List.of("varchar", "integer", "integer"), "varchar"),
        new UdfDefinition.UdfSignature(List.of("bigint", "integer", "integer"), "varchar"))));
  }

  @Test
  void rejectsUdfWithoutSignaturesOrParams() {
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validateUdf(INSTANCE, udf("empty"))).getMessage().contains("at least one signature"));
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validateUdf(INSTANCE, udf("no_params",
            new UdfDefinition.UdfSignature(List.of(), "varchar"))))
        .getMessage().contains("column-value parameter"));
  }

  @Test
  void rejectsUdfBadNameAndBadTypeDeclarations() {
    assertThrows(SqlMaskException.class, () ->
        validator.validateUdf(INSTANCE, udf("bad name!",
            new UdfDefinition.UdfSignature(List.of("varchar"), "varchar"))));
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validateUdf(INSTANCE, udf("mask_phone",
            new UdfDefinition.UdfSignature(List.of("strng", "integer"), "varchar"))))
        .getMessage().contains("strng"));
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validateUdf(INSTANCE, udf("mask_phone",
            new UdfDefinition.UdfSignature(List.of("varchar"), "blob"))))
        .getMessage().contains("blob"));
  }

  @Test
  void rejectsDuplicateUdfSignature() {
    assertTrue(assertThrows(SqlMaskException.class, () ->
        validator.validateUdf(INSTANCE, udf("mask_phone",
            new UdfDefinition.UdfSignature(List.of("varchar", "integer"), "varchar"),
            new UdfDefinition.UdfSignature(List.of("varchar", "integer"), "text"))))
        .getMessage().contains("duplicate signature"));
  }

  // ---- 主体相交重叠校验 ----

  private static PolicyEntity datamaskWithSubjects(String name, Set<String> users,
      Set<String> groups, List<String> columns) {
    return new PolicyEntity(name, PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", columns),
        new SubjectSelector(users, groups), "mask_phone", List.of(3, 4), null);
  }

  @Test
  void sameTypeSameTableDisjointSubjectsDoNotOverlap() {
    // users-only 互不相交、groups-only 互不相交：确实选不中同一主体，放行
    PolicyEntity alice = datamaskWithSubjects("a_mask", Set.of("alice"), Set.of(),
        List.of("phone"));
    PolicyEntity bob = datamaskWithSubjects("b_mask", Set.of("bob"), Set.of(),
        List.of("phone"));
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, UDFS, alice, List.of(bob)));
    PolicyEntity analytics = datamaskWithSubjects("c_mask", Set.of(), Set.of("analytics"),
        List.of("phone"));
    PolicyEntity bi = datamaskWithSubjects("d_mask", Set.of(), Set.of("bi"),
        List.of("phone"));
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, UDFS, analytics, List.of(bi)));
  }

  @Test
  void crossSetSubjectsOverlapForCompositeSubject() {
    // 复合主体 (bob, [analysts]) 经 matchLevel 独立命中 users 与 groups，
    // users-only × groups-only 同表同列也必须拒绝（否则编译期/按主体静默双策略）
    PolicyEntity usersOnly = datamaskWithSubjects("a_mask", Set.of("alice"), Set.of(),
        List.of("phone"));
    PolicyEntity groupsOnly = datamaskWithSubjects("b_mask", Set.of(), Set.of("analysts"),
        List.of("phone"));
    assertTrue(assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, groupsOnly, List.of(usersOnly)))
        .getMessage().contains("overlaps"));
  }

  @Test
  void wildcardSubjectOverlapsEverything() {
    PolicyEntity analysts = datamaskWithSubjects("a_mask", Set.of("alice"), Set.of(),
        List.of("phone"));
    PolicyEntity everyone = datamaskWithSubjects("b_mask", Set.of(), Set.of("*"),
        List.of("phone"));
    assertTrue(assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, everyone, List.of(analysts)))
        .getMessage().contains("overlaps"));
  }

  @Test
  void userIntersectionAndGroupIntersectionOverlap() {
    PolicyEntity a = datamaskWithSubjects("a_mask", Set.of("alice", "bob"), Set.of(),
        List.of("phone"));
    PolicyEntity b = datamaskWithSubjects("b_mask", Set.of("bob"), Set.of(),
        List.of("phone"));
    assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, b, List.of(a)));
    PolicyEntity c = datamaskWithSubjects("c_mask", Set.of(), Set.of("analytics"),
        List.of("phone"));
    PolicyEntity d = datamaskWithSubjects("d_mask", Set.of(), Set.of("analytics", "bi"),
        List.of("phone"));
    assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, d, List.of(c)));
  }

  @Test
  void rowFilterSameTableDisjointSubjectsAllowed() {
    // users 互斥即可；users × groups 的组合会命中复合主体，不再作为反例
    PolicyEntity rfA = new PolicyEntity("rf_a", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()),
        new SubjectSelector(Set.of("alice"), Set.of()), null, List.of(), "status = 'active'");
    PolicyEntity rfB = new PolicyEntity("rf_b", PolicyType.ROW_FILTER, true,
        new ResourceSelector("crm", "public", "customer", List.of()),
        new SubjectSelector(Set.of("bob"), Set.of()), null, List.of(), "id > 0");
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, UDFS, rfB, List.of(rfA)));
  }

  @Test
  void defaultConstructorMeansEveryone() {
    PolicyEntity legacy = datamask("legacy", "customer", List.of("phone")); // 7 参便捷构造
    assertEquals(Set.of("*"), legacy.subjects().users());
  }

  // ---- 继承列(inheritColumns)校验: inheritColumns ⊆ columns ----

  @Test
  void acceptsInheritColumnsSubsetOfColumns() {
    PolicyEntity p = new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone", "email"),
            List.of("phone")),
        "mask_phone", List.of(3, 4), null);
    assertDoesNotThrow(() -> validator.validatePolicy(INSTANCE, UDFS, p, List.of()));
  }

  @Test
  void acceptsEmptyInheritColumns() {
    // 兼容旧策略:未声明继承列即空集合,不额外校验
    assertDoesNotThrow(() ->
        validator.validatePolicy(INSTANCE, UDFS, datamask("p", "customer", List.of("phone")), List.of()));
  }

  @Test
  void rejectsInheritColumnNotAmongResourceColumns() {
    // email 在表中存在,但不在策略 columns 列表里 —— 继承列必须 ⊆ columns
    PolicyEntity p = new PolicyEntity("p", PolicyType.DATAMASK, true,
        new ResourceSelector("crm", "public", "customer", List.of("phone"),
            List.of("email")),
        "mask_phone", List.of(3, 4), null);
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> validator.validatePolicy(INSTANCE, UDFS, p, List.of()));
    assertTrue(e.getMessage().contains("inheritColumn 'email' is not among resource columns"),
        () -> e.getMessage());
  }
}
