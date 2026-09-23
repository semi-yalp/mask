package io.masklite.lineage;

import io.masklite.sql.ValidatedSql;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexDynamicParam;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.sql.SqlKind;

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
 * <p>Scalar sub-queries embedded in output expressions are traced
 * recursively: the value of {@code (SELECT avg(x) FROM t)} originates from
 * every base column that feeds the sub-query's single projection, so the
 * output is masked when any of those columns is masked. Any other sub-query
 * shape (EXISTS, IN, ANY/SOME/ALL, ARRAY), a multi-column or zero-column
 * scalar, or anything the metadata layer cannot resolve is still
 * {@link LineageStatus#UNKNOWN} — the metadata layer ignores the relational
 * subtree inside {@code RexSubQuery}, so a "no origin" verdict could hide a
 * masking leak.
 */
public final class LineageAnalyzer {

  public List<OutputLineage> analyze(ValidatedSql validated) {
    return analyze(validated.rel(), validated.rowType());
  }

  public List<OutputLineage> analyze(RelNode root, RelDataType outputRowType) {
    RelMetadataQuery metadataQuery = root.getCluster().getMetadataQuery();
    Project rootProject = findRootProject(root);
    List<OutputLineage> result = new ArrayList<>();
    for (RelDataTypeField field : outputRowType.getFieldList()) {
      result.add(analyzeField(root, rootProject, field, metadataQuery));
    }
    return result;
  }

  private OutputLineage analyzeField(RelNode root, Project rootProject, RelDataTypeField field,
      RelMetadataQuery metadataQuery) {
    if (rootProject != null && containsSubQuery(rootProject.getProjects().get(field.getIndex()))) {
      Set<ColumnOrigin> walked =
          walkExpression(rootProject.getProjects().get(field.getIndex()),
              rootProject.getInput(), metadataQuery);
      if (walked == null) {
        return OutputLineage.of(field, Set.of(), LineageStatus.UNKNOWN);
      }
      return walked.isEmpty()
          ? OutputLineage.of(field, Set.of(), LineageStatus.NO_ORIGIN)
          : OutputLineage.of(field, walked, LineageStatus.RESOLVED);
    }
    Set<org.apache.calcite.rel.metadata.RelColumnOrigin> rawOrigins =
        metadataQuery.getColumnOrigins(root, field.getIndex());
    if (rawOrigins == null) {
      return OutputLineage.of(field, Set.of(), LineageStatus.UNKNOWN);
    }
    if (rawOrigins.isEmpty()) {
      return OutputLineage.of(field, Set.of(), LineageStatus.NO_ORIGIN);
    }
    Set<ColumnOrigin> origins = new LinkedHashSet<>();
    for (org.apache.calcite.rel.metadata.RelColumnOrigin rawOrigin : rawOrigins) {
      origins.add(ColumnOrigin.from(rawOrigin));
    }
    return OutputLineage.of(field, origins, LineageStatus.RESOLVED);
  }

  /**
   * Collects the origins of an output expression that contains sub-queries,
   * walking the expression tree by hand because the metadata layer cannot
   * see into {@code RexSubQuery}. Returns null when any part cannot be
   * traced safely.
   */
  private Set<ColumnOrigin> walkExpression(RexNode expression, RelNode input,
      RelMetadataQuery metadataQuery) {
    if (expression instanceof RexSubQuery subQuery) {
      return scalarSubQueryOrigins(subQuery, metadataQuery);
    }
    if (expression instanceof RexInputRef inputRef) {
      return originSet(metadataQuery.getColumnOrigins(input, inputRef.getIndex()));
    }
    if (expression instanceof RexLiteral || expression instanceof RexDynamicParam) {
      return Set.of();
    }
    if (expression instanceof RexCall call) {
      Set<ColumnOrigin> origins = new LinkedHashSet<>();
      for (RexNode operand : call.getOperands()) {
        Set<ColumnOrigin> operandOrigins = walkExpression(operand, input, metadataQuery);
        if (operandOrigins == null) {
          return null;
        }
        origins.addAll(operandOrigins);
      }
      return origins;
    }
    // unexpected node shape: fail closed
    return null;
  }

  /** Origins of a scalar sub-query's single output value, or null if unsafe. */
  private Set<ColumnOrigin> scalarSubQueryOrigins(RexSubQuery subQuery,
      RelMetadataQuery metadataQuery) {
    if (subQuery.getKind() != SqlKind.SCALAR_QUERY) {
      return null;
    }
    RelNode rel = subQuery.rel;
    List<RelDataTypeField> fields = rel.getRowType().getFieldList();
    if (fields.size() != 1) {
      return null;
    }
    Project project = findRootProject(rel);
    if (project != null && containsSubQuery(project.getProjects().get(0))) {
      return walkExpression(project.getProjects().get(0), project.getInput(), metadataQuery);
    }
    return originSet(metadataQuery.getColumnOrigins(rel, 0));
  }

  private Set<ColumnOrigin> originSet(
      Set<org.apache.calcite.rel.metadata.RelColumnOrigin> rawOrigins) {
    if (rawOrigins == null) {
      return null;
    }
    Set<ColumnOrigin> origins = new LinkedHashSet<>();
    for (org.apache.calcite.rel.metadata.RelColumnOrigin rawOrigin : rawOrigins) {
      origins.add(ColumnOrigin.from(rawOrigin));
    }
    return origins;
  }

  /**
   * Locates the projection that carries the root output expressions,
   * descending through nodes that never change the output shape.
   */
  private Project findRootProject(RelNode root) {
    RelNode node = root;
    while (true) {
      if (node instanceof Project project) {
        return project;
      }
      if (node instanceof Sort sort) {
        node = sort.getInput();
      } else if (node instanceof Filter filter) {
        node = filter.getInput();
      } else {
        return null;
      }
    }
  }

  private boolean containsSubQuery(RexNode expression) {
    if (expression instanceof RexSubQuery) {
      return true;
    }
    if (expression instanceof RexCall call) {
      for (RexNode operand : call.getOperands()) {
        if (containsSubQuery(operand)) {
          return true;
        }
      }
    }
    return false;
  }
}
