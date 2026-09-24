package io.sqlmask.lineage;

import io.sqlmask.sql.ValidatedSql;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
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
 * <p>Scalar subqueries inside projections make origin tracking unsound: the
 * metadata layer ignores the relational subtree inside {@link RexSubQuery}
 * and reports empty origins for the values it produces, so a subquery could
 * smuggle an unmasked sensitive column into the output (including from a
 * set-operation branch the root projection guard would never see). Before
 * analyzing, the whole relational tree is scanned for projections containing
 * a subquery; if one exists, every output field is reported as
 * {@link LineageStatus#UNKNOWN} and the statement fails (fail-closed).
 * Subqueries in other positions (WHERE / HAVING / JOIN conditions) never
 * contribute values to the output columns and stay allowed.
 */
public final class LineageAnalyzer {

  public List<OutputLineage> analyze(ValidatedSql validated) {
    return analyze(validated.rel(), validated.rowType());
  }

  public List<OutputLineage> analyze(RelNode root, RelDataType outputRowType) {
    RelMetadataQuery metadataQuery = root.getCluster().getMetadataQuery();
    boolean subQueryInProjection = containsProjectSubQuery(root);
    List<OutputLineage> result = new ArrayList<>();
    for (RelDataTypeField field : outputRowType.getFieldList()) {
      result.add(analyzeField(root, field, metadataQuery, subQueryInProjection));
    }
    return result;
  }

  private OutputLineage analyzeField(RelNode root, RelDataTypeField field,
      RelMetadataQuery metadataQuery, boolean subQueryInProjection) {
    if (subQueryInProjection) {
      return OutputLineage.of(field, Set.of(), LineageStatus.UNKNOWN);
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
   * True when any projection anywhere in the tree contains a scalar subquery.
   * The walk deliberately does not descend into {@link RexSubQuery#getRel()}:
   * values produced inside a subquery's own plan never become outer output
   * columns — only subqueries sitting in a projection of the analyzed tree do.
   */
  private static boolean containsProjectSubQuery(RelNode node) {
    if (node instanceof Project project) {
      for (RexNode expression : project.getProjects()) {
        if (containsSubQuery(expression)) {
          return true;
        }
      }
    }
    for (RelNode input : node.getInputs()) {
      if (containsProjectSubQuery(input)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsSubQuery(RexNode expression) {
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
