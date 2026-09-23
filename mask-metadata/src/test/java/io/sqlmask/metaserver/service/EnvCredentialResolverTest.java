package io.sqlmask.metaserver.service;

import io.sqlmask.error.SqlMaskException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvCredentialResolverTest {

  private final EnvCredentialResolver resolver = new EnvCredentialResolver();

  @Test
  void missingEnvVarFailsClosedNamingTheReference() {
    // 变量名特意选择测试进程不会定义的名字；成功路径需要真实环境变量，
    // 与真实数据库联调一样归入手工验收
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> resolver.resolve("SQLMASK_TEST_UNSET_CREDENTIAL_2026"));
    assertEquals(SqlMaskException.Code.METADATA_CREDENTIAL_UNAVAILABLE, e.getCode());
    assertTrue(e.getMessage().contains("SQLMASK_TEST_UNSET_CREDENTIAL_2026"), () -> e.getMessage());
  }

  @Test
  void nonPrefixedReferenceIsRejectedBeforeTouchingTheEnvironment() {
    // passwordRef 由采集载荷决定：无 SQLMASK_ 前缀的引用（可指向任意环境变量，
    // 包括云凭证）一律拒绝——迁移说明见 README
    for (String bad : new String[] {"PGPASSWORD", "AWS_SECRET_ACCESS_KEY", "sqlmask_pw", "", null}) {
      SqlMaskException e = assertThrows(SqlMaskException.class, () -> resolver.resolve(bad));
      assertEquals(SqlMaskException.Code.METADATA_CREDENTIAL_UNAVAILABLE, e.getCode());
      assertTrue(e.getMessage().contains("SQLMASK_"), () -> e.getMessage());
    }
  }
}
