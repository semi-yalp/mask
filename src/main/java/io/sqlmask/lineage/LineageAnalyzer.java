package io.sqlmask.lineage;

import io.sqlmask.sql.ValidatedSql;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
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
 * <p>Sub-queries embedded in output expressions are treated as
 * {@link LineageStatus#UNKNOWN}: the metadata layer ignores the relational
 * subtree inside {@link RexSubQuery}, so a "no origin" verdict could hide a
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
