package io.sqlmask.dialect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Trino 类型声明守卫(对照 HiveDialectProfileTest.decimalRequiresBothPrecisionAndScale 的 I2 口径)。 */
class TrinoTypeResolverTest {

  private final TrinoTypeResolver resolver = new TrinoTypeResolver();

  @Test
  void decimalWithoutScaleFailsClosed() {
    // decimal(10)(precision=10, scale=null)曾漏过守卫、在下游 schema 构建
    // (YamlCalciteSchemaFactory#createSqlType 拆箱 scale)时 NPE——对齐 Hive I2:
    // fail-closed 报支持清单。bare decimal(双 null)走无参 createSqlType 分支
    // 无 NPE,且是 TrinoTypeMapper 采集映射的既有合法行为,继续放行(见下个测试)。
    assertThat(resolver.parseColumn("a", "decimal(10, 2)").sqlTypeName()).isNotNull();
    assertThatThrownBy(() -> resolver.parseColumn("a", "decimal(10)"))
        .isInstanceOf(io.sqlmask.error.SqlMaskException.class)
        .hasMessageContaining("decimal(10)")
        .hasMessageContaining("decimal(p,s)");
    assertThatThrownBy(() -> resolver.parseColumn("a", "decimal(,2)"))
        .isInstanceOf(io.sqlmask.error.SqlMaskException.class);
  }

  @Test
  void acceptsUnparameterizedScalarsAndTimezoneVariants() {
    assertThat(resolver.parseColumn("a", "bigint").sqlTypeName()).isNotNull();
    assertThat(resolver.parseColumn("a", "varchar").sqlTypeName()).isNotNull();
    // bare decimal 无 NPE 风险且是 TrinoTypeMapper 采集映射的既有合法输出,
    // 锁定该 introspection round-trip(对照 TrinoTypeMapperTest 硬防线)
    assertThat(resolver.parseColumn("a", "decimal").sqlTypeName()).isNotNull();
    assertThat(resolver.parseColumn("a", "timestamp(3) with time zone").sqlTypeName()).isNotNull();
  }
}
