package io.sqlmask.sql;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.util.SqlOperatorTables;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlNameMatchers;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;

import java.util.List;

/**
 * Wires the Calcite validator and SQL-to-relational converter against a
 * YAML-backed schema. Name matching, conformance and the engine-defined
 * function table come from the caller (the dialect profile); unqualified
 * tables resolve against every declared search path.
 */
public final class SqlValidatorFactory {

  private final CalciteSchema rootSchema;
  private final List<List<String>> schemaPaths;
  private final RelDataTypeFactory typeFactory;
  private final SqlConformance conformance;
  private final boolean caseSensitiveNameMatching;
  private final SqlOperatorTable functionTable;

  public SqlValidatorFactory(SchemaPlus rootSchema, List<List<String>> schemaPaths,
      SqlConformance conformance,
      boolean caseSensitiveNameMatching,
      SqlOperatorTable functionTable) {
    this.rootSchema = CalciteSchema.from(rootSchema);
    this.schemaPaths = List.copyOf(schemaPaths);
    this.typeFactory = new JavaTypeFactoryImpl(RelDataTypeSystem.DEFAULT);
    this.conformance = conformance;
    this.caseSensitiveNameMatching = caseSensitiveNameMatching;
    this.functionTable = functionTable;
  }

  public SqlValidator createValidator() {
    CalciteCatalogReader catalogReader = catalogReader();
    SqlOperatorTable operators = SqlOperatorTables.chain(
        functionTable,
        catalogReader,
        // engine-defined functions must not block validation: unknown names
        // resolve as opaque scalar UDFs (see UnknownFunctionTable)
        new io.sqlmask.dialect.UnknownFunctionTable(functionTable));
    SqlValidator.Config config = SqlValidator.Config.DEFAULT
        .withSqlConformance(conformance);
    return SqlValidatorUtil.newValidator(operators, catalogReader, typeFactory, config);
  }

  public SqlToRelConverter createConverter(SqlValidator validator) {
    CalciteCatalogReader catalogReader = catalogReader();
    RelOptPlanner planner = new HepPlanner(new HepProgramBuilder().build());
    RexBuilder rexBuilder = new RexBuilder(typeFactory);
    RelOptCluster cluster = RelOptCluster.create(planner, rexBuilder);
    cluster.setMetadataProvider(DefaultRelMetadataProvider.INSTANCE);
    RelOptTableNoViews viewExpander = (rowType, queryString, schemaPath, viewPath) -> {
      throw new UnsupportedOperationException("views are not supported");
    };
    return new SqlToRelConverter(viewExpander, validator, catalogReader, cluster,
        StandardConvertletTable.INSTANCE,
        SqlToRelConverter.config()
            .withTrimUnusedFields(false)
            // RelBuilder's aggregate-input pruning drops projected columns and
            // rewrites aggregate arguments (count(col) -> count()), which
            // erases column origins; keep the converter's structure intact
            .addRelBuilderConfigTransform(c -> c.withPruneInputOfAggregate(false)));
  }

  private CalciteCatalogReader catalogReader() {
    return new MultiSchemaPathCatalogReader(rootSchema, schemaPaths, typeFactory,
        caseSensitiveNameMatching);
  }

  private interface RelOptTableNoViews
      extends org.apache.calcite.plan.RelOptTable.ViewExpander {
  }

  private static final class MultiSchemaPathCatalogReader extends CalciteCatalogReader {
    MultiSchemaPathCatalogReader(CalciteSchema rootSchema, List<List<String>> schemaPaths,
        RelDataTypeFactory typeFactory, boolean caseSensitiveNameMatching) {
      super(rootSchema, SqlNameMatchers.withCaseSensitive(caseSensitiveNameMatching), dedupe(schemaPaths),
          typeFactory, CalciteConnectionConfigImpl.DEFAULT);
    }

    private static List<List<String>> dedupe(List<List<String>> schemaPaths) {
      java.util.LinkedHashSet<List<String>> unique = new java.util.LinkedHashSet<>(schemaPaths);
      unique.add(List.of()); // allow root-level lookups as CalciteCatalogReader does
      return List.copyOf(unique);
    }
  }
}
