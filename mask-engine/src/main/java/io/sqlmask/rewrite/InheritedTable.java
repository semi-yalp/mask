package io.sqlmask.rewrite;

import java.util.List;

/**
 * 一条"复制表语句"的目标表完整结构:目标表三元组 + 语句验证后的**全部输出列**
 * (列名 + 方言类型声明)。混合复制(继承列 + 脱敏列 + 无策略列)时目标表落库的
 * 列不止继承列,注册方需要完整结构才能把目标表如实体登记进策略服务与元数据
 * 服务——否则后续对非继承列的查询因结构缺失而 fail-closed。
 *
 * @param catalog 目标表 catalog
 * @param schema  目标表 schema
 * @param table   目标表名
 * @param columns 目标表全部输出列(按输出顺序)
 */
public record InheritedTable(String catalog, String schema, String table,
    List<ColumnInfo> columns) {

  public InheritedTable {
    columns = columns == null ? List.of() : List.copyOf(columns);
  }

  /** 目标表的一个输出列:输出列名 + 方言类型声明(如 {@code varchar} / {@code decimal(10,2)})。 */
  public record ColumnInfo(String name, String type) {
  }
}
