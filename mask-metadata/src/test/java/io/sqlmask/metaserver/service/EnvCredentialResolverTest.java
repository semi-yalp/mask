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
}
