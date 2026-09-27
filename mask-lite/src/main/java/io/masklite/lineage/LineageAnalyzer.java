package io.masklite.lineage;

import io.masklite.rewrite.MaskSelector;
import io.masklite.sql.ValidatedSql;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.SetOp;
import org.apache.calcite.rel.metadata.RelColumnOrigin;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexSubQuery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Analyzes the root output fields of a validated query: for every output
 * ordinal it collects the set of base-column origins via Calcite's
 * {@code RelMetadataQuery.getColumnOrigins} and classifies the result.
 *
 * <p>{@code null} from the metadata layer means the origin cannot be
 * determined ({@link LineageStatus#UNKNOWN}, a failure), an empty set means
 * the expression has no base-column origin such as a constant
 * ({@link LineageStatus#NO_ORIGIN}, passthrough). CTE and derived-table
 * structures never surface as origins because the analysis tree has CTEs
 * expanded into derived tables.
 *
 * <p>标量子查询（TPC-DS q09 的 CASE-over-subquery 输出形态）：元数据层不进
 * {@link RexSubQuery}，含子查询的表达式整体拿不到 origins。处理规则：
 *
 * <ul>
 *   <li>先对树上每个投影里的标量子查询递归做安全校验——其自身输出列若命中
 *       脱敏策略（或无法证明不命中），子查询会把受保护数据走私到输出，
 *       整条语句 fail-closed（UNKNOWN）；全部安全才继续。</li>
 *   <li>随后对含子查询的输出列，把子查询位替换为 NULL 常量后重新取 origins：
 *       纯子查询列（无策略命中）归为 NO_ORIGIN 透传；混合列保留非子查询部分
 *       的真实来源，策略命中即正常包装。</li>
 * </ul>
 *
 * 子查询在其他位置（WHERE / HAVING / JOIN 条件）从不向输出列贡献值，保持放行。
 */
public final class LineageAnalyzer {

  private final MaskSelector selector;

  public LineageAnalyzer(MaskSelector selector) {
    this.selector = selector;
  }

  public List<OutputLineage> analyze(ValidatedSql validated) {
    return analyze(validated.rel(), validated.rowType());
  }

  public List<OutputLineage> analyze(RelNode root, RelDataType outputRowType) {
    RelMetadataQuery metadataQuery = root.getCluster().getMetadataQuery();
    List<RexSubQuery> subqueries = collectProjectionSubQueries(root);
    for (RexSubQuery subquery : subqueries) {
      if (!subquerySafe(subquery.rel, metadataQuery)) {
        return allUnknown(outputRowType);
      }
    }
    List<OutputLineage> result = new ArrayList<>();
    for (RelDataTypeField field : outputRowType.getFieldList()) {
      result.add(analyzeField(root, field, metadataQuery));
    }
    return result;
  }

  private OutputLineage analyzeField(RelNode root, RelDataTypeField field,
      RelMetadataQuery metadataQuery) {
    Set<RelColumnOrigin> rawOrigins = metadataQuery.getColumnOrigins(root, field.getIndex());
    if (rawOrigins == null) {
      // 元数据层不进子查询：整体 null 时把子查询位换成 NULL 常量重取，
      // 非子查询部分的来源因此可见（子查询本身已验证安全）
      rawOrigins = originsWithSubqueriesNulled(root, field.getIndex(), metadataQuery);
      if (rawOrigins == null) {
        return OutputLineage.of(field, Set.of(), LineageStatus.UNKNOWN);
      }
    }
    if (rawOrigins.isEmpty()) {
      return OutputLineage.of(field, Set.of(), LineageStatus.NO_ORIGIN);
    }
    Set<ColumnOrigin> origins = new LinkedHashSet<>();
    for (RelColumnOrigin rawOrigin : rawOrigins) {
      origins.add(ColumnOrigin.from(rawOrigin));
    }
    return OutputLineage.of(field, origins, LineageStatus.RESOLVED);
  }

  /**
   * 单列标量子查询的安全校验：产出恰一列；列内更深的子查询逐层先过同一规则；
   * 该列的 origins 为空（常量/聚合无来源）或不命中任何脱敏策略才算安全——
   * 命中策略意味着子查询会绕过外层包装泄漏受保护数据，必须 fail-closed。
   */
  private boolean subquerySafe(RelNode subRel, RelMetadataQuery metadataQuery) {
    if (subRel.getRowType().getFieldCount() != 1) {
      return false;
    }
    for (RexSubQuery inner : collectProjectionSubQueries(subRel)) {
      if (!subquerySafe(inner.rel, metadataQuery)) {
        return false;
      }
    }
    Set<RelColumnOrigin> origins = metadataQuery.getColumnOrigins(subRel, 0);
    if (origins == null) {
      return false;
    }
    if (origins.isEmpty()) {
      return true;
    }
    Set<ColumnOrigin> mapped = new LinkedHashSet<>();
    for (RelColumnOrigin rawOrigin : origins) {
      mapped.add(ColumnOrigin.from(rawOrigin));
    }
    return selector.select(mapped).isEmpty();
  }

  /**
   * 含子查询输出列的替代 origins：定位产出该列的 Project（集合运算按分支
   * 递归、取并集），把表达式里的子查询换成 NULL 常量后重新取 origins；
   * 非此形态（Aggregate 等）或任一分支无法确定时返回 null（fail-closed）。
   */
  private Set<RelColumnOrigin> originsWithSubqueriesNulled(RelNode node, int index,
      RelMetadataQuery metadataQuery) {
    if (node instanceof Project project) {
      RexNode expression = project.getProjects().get(index);
      if (!containsSubQuery(expression)) {
        return null;
      }
      RexBuilder rexBuilder = node.getCluster().getRexBuilder();
      List<RexNode> substituted = project.getProjects().stream()
          .map(expression0 -> substituteNullForSubqueries(expression0, rexBuilder))
          .toList();
      Project copy = project.copy(project.getTraitSet(), project.getInput(),
          substituted, project.getRowType());
      return metadataQuery.getColumnOrigins(copy, index);
    }
    if (node instanceof SetOp setOp) {
      Set<RelColumnOrigin> union = new LinkedHashSet<>();
      for (RelNode input : setOp.getInputs()) {
        Set<RelColumnOrigin> branch = originsWithSubqueriesNulled(input, index, metadataQuery);
        if (branch == null) {
          return null;
        }
        union.addAll(branch);
      }
      return union;
    }
    return null;
  }

  private RexNode substituteNullForSubqueries(RexNode expression, RexBuilder rexBuilder) {
    return expression.accept(new RexShuttle() {
      @Override
      public RexNode visitCall(RexCall call) {
        if (call instanceof RexSubQuery subQuery) {
          return rexBuilder.makeNullLiteral(subQuery.getType());
        }
        return super.visitCall(call);
      }
    });
  }

  /**
   * Collects every scalar subquery sitting in a projection anywhere in the
   * tree. The walk deliberately does not descend into
   * {@link RexSubQuery#getRel()} — nested subqueries are covered by the
   * recursive safety check instead.
   */
  private static List<RexSubQuery> collectProjectionSubQueries(RelNode node) {
    List<RexSubQuery> subqueries = new ArrayList<>();
    if (node instanceof Project project) {
      for (RexNode expression : project.getProjects()) {
        collectSubQueries(expression, subqueries);
      }
    }
    for (RelNode input : node.getInputs()) {
      subqueries.addAll(collectProjectionSubQueries(input));
    }
    return subqueries;
  }

  private static void collectSubQueries(RexNode expression, List<RexSubQuery> into) {
    if (expression instanceof RexSubQuery subQuery) {
      into.add(subQuery);
      return;
    }
    if (expression instanceof RexCall call) {
      for (RexNode operand : call.getOperands()) {
        collectSubQueries(operand, into);
      }
    }
  }

  private static boolean containsSubQuery(RexNode expression) {
    return expression instanceof RexSubQuery
        || expression instanceof RexCall call
        && call.getOperands().stream().anyMatch(LineageAnalyzer::containsSubQuery);
  }

  private static List<OutputLineage> allUnknown(RelDataType outputRowType) {
    List<OutputLineage> result = new ArrayList<>();
    for (RelDataTypeField field : outputRowType.getFieldList()) {
      result.add(OutputLineage.of(field, Set.of(), LineageStatus.UNKNOWN));
    }
    return result;
  }
}
