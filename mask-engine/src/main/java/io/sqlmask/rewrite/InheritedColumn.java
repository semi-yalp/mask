package io.sqlmask.rewrite;

import io.sqlmask.lineage.ColumnOrigin;
import io.sqlmask.policy.model.DataMaskItem;

import java.util.List;

/**
 * 一条"复制表语句"的列继承记录:目标表三元组 + 目标列名被声明为复制继承
 * 的来源列策略接管,目标列的数据在写入时保持干净(不套脱敏 UDF),由注册方
 * 为目标表列登记继承策略,使后续读取命中脱敏。
 *
 * @param targetCatalog     目标表 catalog(1~2 段目标标识符时继承来源列同段值)
 * @param targetSchema      目标表 schema(1 段目标标识符时继承来源列同段值)
 * @param targetTable       目标表名
 * @param targetColumn      目标列名(INSERT/CREATE TABLE 列清单对应项或输出列名)
 * @param targetColumnType  来源列 {@code TableMetadata.Column.typeDeclaration()}
 *                          (被复制列类型与源列一致),供结构登记使用;来源不可
 *                          得时为 null(注册方跳过该列类型)
 * @param source            来源列血缘;注册方只消费目标三元组/列/列类型/items,
 *                          该字段仅作诊断追溯
 * @param items             来源列可继承的脱敏指令(决策顺序:优先级高者在前)
 */
public record InheritedColumn(String targetCatalog, String targetSchema, String targetTable,
    String targetColumn, String targetColumnType, ColumnOrigin source, List<DataMaskItem> items) {

  public InheritedColumn {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
