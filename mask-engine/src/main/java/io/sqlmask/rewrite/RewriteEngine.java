package io.sqlmask.rewrite;

import io.sqlmask.config.LegacyPolicyAdapter;
import io.sqlmask.config.LoadedConfig;
import io.sqlmask.config.MaskingConfig;
import io.sqlmask.config.PolicyResourceResolver;
import io.sqlmask.config.YamlConfigLoader;
import io.sqlmask.dialect.DialectAdapter;
import io.sqlmask.dialect.DialectRegistry;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.lineage.ColumnOrigin;
import io.sqlmask.lineage.LineageAnalyzer;
import io.sqlmask.lineage.LineageStatus;
import io.sqlmask.lineage.OutputLineage;
import io.sqlmask.metadata.ColumnKey;
import io.sqlmask.metadata.TableMetadata;
import io.sqlmask.metadata.YamlCalciteSchemaFactory;
import io.sqlmask.policy.PolicyException;
import io.sqlmask.policy.match.PolicyEngine;
import io.sqlmask.policy.match.PolicyIndex;
import io.sqlmask.policy.model.DataMaskItem;
import io.sqlmask.policy.model.Policy;
import io.sqlmask.policy.model.Subject;
import io.sqlmask.policy.store.PolicyYamlLoader;
import io.sqlmask.rowfilter.RowFilterRegistry;
import io.sqlmask.rowfilter.RowFilterRewriter;
import io.sqlmask.sql.SqlStatementSplitter;
import io.sqlmask.sql.ValidatedSql;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.ddl.SqlColumnDeclaration;
import org.apache.calcite.sql.ddl.SqlCreateTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The whole rewriting pipeline as a reusable engine: YAML metadata text plus
 * SQL text in, per-statement rewriting results out. Shared by the CLI and
 * the HTTP service. Every statement is parsed, validated, analyzed and
 * rewritten in order; any failure aborts the whole run without producing a
 * partial result.
 */
public final class RewriteEngine {

  /**
   * Result of one statement. {@code originalSql} is the statement as written
   * by the user (for read statements: the pre-validation, pre-row-filter
   * rendering; for write statements: the raw input; no trailing ';') — the
   * row filter, when applied, lives only in {@code rewrittenSql} so callers
   * can always diff input vs output. {@code masked} is true when the
   * statement got an outer masking wrapper; {@code rowFiltered} is true when
   * at least one row-filter condition was injected. {@code kind} is the
   * coarse statement class (SELECT / INSERT_SELECT / CTAS) — the query data
   * plane rejects everything but SELECT. {@code inheritedColumns} carries
   * the copy-inheritance records produced by write statements whose source
   * columns declared {@code inheritOnCopy}: the target column is written
   * clean (never wrapped) and each entry lets the caller register the
   * inherited policy on the target table; always empty for read statements
   * and for writes with no inherited column. {@code inheritedTables} carries
   * the target tables' full output-column structure (from the validated row
   * type) so the caller can register the complete target schema — a mixed
   * copy (inherited + masked + unpoliced columns) lands more columns in the
   * target table than the inherited entries alone; always empty for read
   * statements and for writes with no inherited column.
   */
  public record StatementRewrite(int ordinal, String originalSql, String rewrittenSql,
      boolean masked, boolean rowFiltered, StatementKind kind,
      List<InheritedColumn> inheritedColumns, List<InheritedTable> inheritedTables) {

    /** Convenience constructor for statements without a row filter. */
    public StatementRewrite(int ordinal, String originalSql, String rewrittenSql, boolean masked) {
      this(ordinal, originalSql, rewrittenSql, masked, false);
    }

    /** Convenience constructor: read statements default to {@link StatementKind#SELECT}. */
    public StatementRewrite(int ordinal, String originalSql, String rewrittenSql, boolean masked,
        boolean rowFiltered) {
      this(ordinal, originalSql, rewrittenSql, masked, rowFiltered, StatementKind.SELECT);
    }

    /** Convenience constructor: statements with no inherited columns. */
    public StatementRewrite(int ordinal, String originalSql, String rewrittenSql, boolean masked,
        boolean rowFiltered, StatementKind kind) {
      this(ordinal, originalSql, rewrittenSql, masked, rowFiltered, kind, List.of(), List.of());
    }

    /** Convenience constructor: write statements with inherited columns but
     * no separately declared target-table structures (callers fall back to
     * the inherited columns for structure registration). */
    public StatementRewrite(int ordinal, String originalSql, String rewrittenSql, boolean masked,
        boolean rowFiltered, StatementKind kind, List<InheritedColumn> inheritedColumns) {
      this(ordinal, originalSql, rewrittenSql, masked, rowFiltered, kind, inheritedColumns,
          List.of());
    }

    /** Serialized into API responses; the web UI keys the original-SQL view off it. */
    @com.fasterxml.jackson.annotation.JsonProperty("unchanged")
    public boolean unchanged() {
      return originalSql.equals(rewrittenSql);
    }
  }

  /**
   * Rewrites all statements in {@code sqlText} against {@code metadataYaml}.
   * Legacy path: policies come from the metadata's own sections, the subject
   * is anonymous.
   *
   * @param metadataYaml YAML configuration content (tables, columns, policies)
   * @param sqlText      one or more SQL statements separated by semicolons
   * @param dialectName  dialect name; see DialectRegistry for the registered dialects
   */
  public List<StatementRewrite> rewrite(String metadataYaml, String sqlText, String dialectName) {
    LoadedConfig loaded =
        new YamlConfigLoader().loadContent(metadataYaml, "metadata.yaml", dialectName);
    return rewrite(loaded, null, sqlText, dialectName, Subject.anonymous());
  }

  /**
   * String-based variant of {@link #rewrite(LoadedConfig, String, String, String, Subject)}:
   * the configuration is loaded from {@code metadataYaml} with the named dialect.
   */
  public List<StatementRewrite> rewrite(String metadataYaml, String policyYaml, String sqlText,
      String dialectName, Subject subject) {
    LoadedConfig loaded =
        new YamlConfigLoader().loadContent(metadataYaml, "metadata.yaml", dialectName);
    return rewrite(loaded, policyYaml, sqlText, dialectName, subject);
  }

  /**
   * Rewrites against an already-resolved configuration (inline YAML or policy
   * service). Legacy path: policies are the converted policy sections of the
   * configuration, the subject is anonymous.
   *
   * @param loaded      validated configuration
   * @param sqlText     one or more SQL statements separated by semicolons
   * @param dialectName dialect name; see DialectRegistry for the registered dialects
   */
  public List<StatementRewrite> rewrite(LoadedConfig loaded, String sqlText, String dialectName) {
    return rewrite(loaded, null, sqlText, dialectName, Subject.anonymous());
  }

  /**
   * Rewrites against a resolved configuration with an optional Ranger-style
   * policy file and query subject. A blank {@code policyYaml} means the
   * configuration's own legacy policy sections are the single policy source
   * (converted internally, byte-identical to the old registry path); a
   * non-blank one requires those sections to be empty.
   */
  public List<StatementRewrite> rewrite(LoadedConfig loaded, String policyYaml, String sqlText,
      String dialectName, Subject subject) {
    List<Policy> policies = buildPolicies(loaded, policyYaml);
    PolicyEngine engine = new PolicyEngine(PolicyIndex.of(policies));
    SchemaPlus schema = YamlCalciteSchemaFactory.create(loaded);
    DialectAdapter dialect = createDialect(dialectName);
    // an invalid row-filter condition fails the whole run before any
    // statement is touched (CONFIG_ERROR straight from the registry build);
    // the legacy path keeps the table-prefixed messages byte-identical
    RowFilterRegistry rowFilters = policyYaml == null || policyYaml.isBlank()
        ? RowFilterRegistry.build(loaded, dialect, schema)
        : RowFilterRegistry.buildFromPolicies(loaded.tables(), engine, subject, dialect, schema);
    RowFilterRewriter rowFilterRewriter = new RowFilterRewriter(dialect);
    LineageAnalyzer analyzer = new LineageAnalyzer();
    MaskSelector selector = new PdpMaskSelector(engine, subject);
    SqlRewriteService rewriteService = new SqlRewriteService();

    List<String> statements = new SqlStatementSplitter().split(sqlText == null ? "" : sqlText);
    List<StatementRewrite> results = new ArrayList<>();
    int ordinal = 0;
    for (String statement : statements) {
      ordinal++;
      try {
        results.add(rewriteOne(dialect, analyzer, selector, rewriteService, schema,
            loaded, rowFilters, rowFilterRewriter, statement, ordinal, engine, subject));
      } catch (SqlMaskException e) {
        // the dialect already prefixes its diagnostics with the statement
        // ordinal; avoid duplicating it
        String message = e.getMessage() != null && e.getMessage().startsWith("statement ")
            ? e.getMessage()
            : "statement " + ordinal + ": " + e.getMessage();
        throw new SqlMaskException(e.getCode(), message, e);
      }
    }
    return results;
  }

  /**
   * Policy source resolution: blank means the legacy sections of the
   * configuration (converted to subject-"*" policies); otherwise the
   * policyYaml must be the single source, with resources resolvable against
   * the declared tables.
   */
  private List<Policy> buildPolicies(LoadedConfig loaded, String policyYaml) {
    if (policyYaml == null || policyYaml.isBlank()) {
      return LegacyPolicyAdapter.convert(loaded.config());
    }
    requireNoLegacyPolicies(loaded);
    List<Policy> policies;
    try {
      policies = new PolicyYamlLoader().parse(policyYaml, "policies.yaml");
    } catch (PolicyException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, e.getMessage(), e);
    }
    new PolicyResourceResolver(loaded).validate(policies);
    return policies;
  }

  private static void requireNoLegacyPolicies(LoadedConfig loaded) {
    MaskingConfig config = loaded.config();
    if (!config.policies().isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policies must come from a single source: metadataYaml declares non-empty 'policies' "
              + "but policyYaml was also given");
    }
    if (!config.columnPolicies().isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policies must come from a single source: metadataYaml declares 'columns' bindings "
              + "but policyYaml was also given");
    }
    for (TableMetadata table : config.tables()) {
      if (table.rowFilter() != null) {
        throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
            "policies must come from a single source: metadataYaml declares rowFilter for table '"
                + table.qualifiedName() + "' but policyYaml was also given");
      }
    }
  }

  private StatementRewrite rewriteOne(DialectAdapter dialect, LineageAnalyzer analyzer,
      MaskSelector selector, SqlRewriteService rewriteService, SchemaPlus schema,
      LoadedConfig loaded, RowFilterRegistry rowFilters, RowFilterRewriter rowFilterRewriter,
      String statementText, int ordinal, PolicyEngine engine, Subject subject) {
    SqlNode parsed = dialect.parse(statementText, ordinal);

    // write statements: mask the rows being written by wrapping their source
    // query; the target name/column list stay untouched (targets are usually
    // new tables that need no metadata declaration)
    if (parsed.getKind() == org.apache.calcite.sql.SqlKind.INSERT
        || parsed.getKind() == org.apache.calcite.sql.SqlKind.CREATE_TABLE) {
      StatementKind writeKind = parsed.getKind() == org.apache.calcite.sql.SqlKind.INSERT
          ? StatementKind.INSERT_SELECT : StatementKind.CTAS;
      if (dialect.isPassThroughWrite(parsed)) {
        return new StatementRewrite(ordinal, statementText, statementText, false, false, writeKind);
      }
      SqlNode source = dialect.querySourceOf(parsed);
      if (source == null) {
        // not pass-through, but also no traceable query: e.g. a scalar
        // subquery hidden inside INSERT ... VALUES would go unmasked
        throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
            "this write statement carries no traceable query source; masking cannot be "
                + "applied safely (queries hidden inside INSERT ... VALUES are not supported)");
      }
      // row filters apply to the source query only — never to the target —
      // and the filtered source text is rendered before validation so the
      // validator's in-place edits (ORDER BY/FETCH duplication) cannot leak
      // into the composed statement
      RowFilterRewriter.Result filtered = rowFilterRewriter.apply(source, loaded, rowFilters);
      String filteredSourceSql =
          filtered.injections() > 0 ? dialect.unparse(filtered.node()) : null;
      ValidatedSql validated = dialect.validate(filtered.node(), schema);
      List<OutputLineage> lineage = analyzer.analyze(validated);
      InheritDecider decider = new InheritDecider(engine, loaded, parsed, lineage, subject);
      List<InheritedColumn> inherited = decider.inheritedColumns();
      if (decider.conflictColumn() != null) {
        throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
            "复制继承被拒绝:目标表列 " + decider.conflictColumn()
                + " 已有自己的脱敏策略;请先移除目标表策略或改用直接改写");
      }
      // 继承列在计划中必须 passthrough——源列虽命中策略,但数据应干净写入;
      // 用无来源输出替换继承列的 lineage,使 RewritePlan 判定为不包装
      List<OutputLineage> planLineage = new ArrayList<>(lineage);
      for (int i = 0; i < lineage.size(); i++) {
        if (decider.inheritedOrdinals().contains(i)) {
          planLineage.set(i, new OutputLineage(lineage.get(i).ordinal(),
              lineage.get(i).outputName(), lineage.get(i).outputType(),
              Set.of(), LineageStatus.NO_ORIGIN));
        }
      }
      RewritePlan plan = RewritePlan.of(planLineage, selector);
      if (!plan.requiresWrapper() && inherited.isEmpty()) {
        if (filtered.injections() > 0) {
          return new StatementRewrite(ordinal, statementText,
              dialect.composeWriteStatement(parsed, filteredSourceSql), false, true, writeKind);
        }
        return new StatementRewrite(ordinal, statementText, statementText, false, false, writeKind);
      }
      String rewritten;
      if (plan.requiresWrapper()) {
        rewritten = dialect.composeWriteStatement(parsed,
            rewriteService.rewrite(validated, plan, dialect));
      } else if (filtered.injections() > 0) {
        // 无包装但存在继承列:数据干净写入,但行过滤必须保留——过滤条件只存在于
        // 过滤后的源查询文本里,不组合就静默丢失(提交前掩码与过滤一并消失)
        rewritten = dialect.composeWriteStatement(parsed, filteredSourceSql);
      } else {
        rewritten = statementText;
      }
      return new StatementRewrite(ordinal, statementText, rewritten, plan.requiresWrapper(),
          filtered.injections() > 0, writeKind, inherited,
          inheritedTablesOf(validated, inherited, decider.targetColumnNames()));
    }

    // read statement: snapshot the statement as written before the row
    // filter rewriter runs, so originalSql never contains the injection
    RowFilterRewriter.Result filtered = rowFilterRewriter.apply(parsed, loaded, rowFilters);
    String originalSql = filtered.injections() > 0
        ? dialect.unparse(parsed)
        : null;
    ValidatedSql validated = dialect.validate(filtered.node(), schema);
    RewritePlan plan = RewritePlan.of(analyzer.analyze(validated), selector);
    String rewritten = rewriteService.rewrite(validated, plan, dialect);
    return new StatementRewrite(ordinal,
        filtered.injections() > 0 ? originalSql : validated.originalSql(),
        rewritten, plan.requiresWrapper(), filtered.injections() > 0);
  }

  /** Joins statement results into a single script (semicolon per statement). */
  public static String join(List<StatementRewrite> statements) {
    return statements.stream()
        .map(s -> s.rewrittenSql() + ";")
        .reduce((a, b) -> a + "\n\n" + b)
        .orElse("");
  }

  /**
   * 目标表完整输出列结构:按继承条目的目标三元组分表,每表填验证后行类型的
   * **全部**输出列(列名 + 方言类型声明)——混合复制(继承列 + 脱敏列 + 无策略
   * 列)时目标表落库的列不止继承列,注册方需要完整结构才能如实登记目标表。
   * 目标列名与 {@link InheritedColumn#targetColumn()} 同源解析({@code
   * targetColumnNames}):语句带目标列清单时按 ordinal 取清单项,否则用输出名
   * ——{@code INSERT INTO t2(mobile) SELECT phone ...} 的结构列是 mobile 而非
   * 输出名 phone。precision/scale 未指定(Calcite 的负数哨兵值)时传 null,
   * {@code typeDeclaration} 输出不带括号的类型名。
   */
  private static List<InheritedTable> inheritedTablesOf(ValidatedSql validated,
      List<InheritedColumn> inherited, List<String> targetColumnNames) {
    List<RelDataTypeField> fields = validated.rowType().getFieldList();
    List<InheritedTable.ColumnInfo> columns = new ArrayList<>(fields.size());
    for (int i = 0; i < fields.size(); i++) {
      RelDataTypeField field = fields.get(i);
      RelDataType type = field.getType();
      Integer precision = type.getPrecision() >= 0 ? type.getPrecision() : null;
      Integer scale = type.getScale() >= 0 ? type.getScale() : null;
      String targetName = i < targetColumnNames.size()
          ? targetColumnNames.get(i)
          : field.getName();
      columns.add(new InheritedTable.ColumnInfo(targetName,
          new TableMetadata.Column(field.getName(), type.getSqlTypeName(),
              precision, scale).typeDeclaration()));
    }
    Map<String, InheritedTable> byTable = new LinkedHashMap<>();
    for (InheritedColumn column : inherited) {
      byTable.putIfAbsent(column.targetCatalog() + "." + column.targetSchema() + "."
              + column.targetTable(),
          new InheritedTable(column.targetCatalog(), column.targetSchema(),
              column.targetTable(), columns));
    }
    return List.copyOf(byTable.values());
  }

  private DialectAdapter createDialect(String name) {
    return DialectRegistry.create(name);
  }

  /**
   * 写语句继承判定:输出列血缘恰好一个来源列、且该来源列声明了
   * {@code inheritOnCopy} 且带可继承的脱敏指令 → 记一条继承条目(目标列不包装,
   * 数据干净写入);来源列含继承语义但血缘非直接单列(表达式 / 多来源)或来源
   * 列无脱敏指令 → fail-closed 抛 {@link UNSUPPORTED_STATEMENT};目标列自身已有
   * 该 subject 的掩码策略 → 记冲突列,整条语句被拒绝。
   */
  private static final class InheritDecider {
    private final PolicyEngine engine;
    private final LoadedConfig loaded;
    private final SqlNode write;
    private final List<OutputLineage> lineage;
    private final Subject subject;
    private final List<InheritedColumn> inherited = new ArrayList<>();
    private final Set<Integer> inheritedOrdinals = new LinkedHashSet<>();
    private String conflictColumn;

    InheritDecider(PolicyEngine engine, LoadedConfig loaded, SqlNode write,
        List<OutputLineage> lineage, Subject subject) {
      this.engine = engine;
      this.loaded = loaded;
      this.write = write;
      this.lineage = lineage;
      this.subject = subject;
      analyze();
    }

    List<InheritedColumn> inheritedColumns() {
      return List.copyOf(inherited);
    }

    /**
     * 全部输出列的目标列名(位置 = 输出 ordinal):与 {@link #targetColumnOf}
     * 同一解析——语句带目标列清单(INSERT 目标列清单 / CREATE TABLE 列清单)时
     * 按 ordinal 取清单项,否则用输出名。供目标表结构({@code inheritedTables})
     * 使用,保证结构列名与继承条目的目标列名不漂移。
     */
    List<String> targetColumnNames() {
      String[] names = new String[lineage.size()];
      for (OutputLineage output : lineage) {
        names[output.ordinal()] = targetColumnOf(output.ordinal(), output.outputName());
      }
      return List.of(names);
    }

    Set<Integer> inheritedOrdinals() {
      return Set.copyOf(inheritedOrdinals);
    }

    String conflictColumn() {
      return conflictColumn;
    }

    private void analyze() {
      for (OutputLineage output : lineage) {
        Set<ColumnOrigin> origins = output.origins();
        boolean anyInherit = origins.stream().anyMatch(o ->
            engine.maskInheritsOnCopy(o.key().catalog(), o.key().schema(),
                o.key().table(), o.key().column()));
        if (!anyInherit) {
          continue;
        }
        if (origins.size() != 1) {
          throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
              "输出列 " + output.ordinal() + " ('" + output.outputName()
                  + "') 的血缘来自多个源列,复制继承仅支持直接列引用;请改写为逐列复制");
        }
        ColumnOrigin origin = origins.iterator().next();
        List<DataMaskItem> items = engine.maskItemsFor(
            origin.key().catalog(), origin.key().schema(), origin.key().table(),
            origin.key().column());
        if (items.isEmpty()) {
          throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
              "复制继承被拒绝:源列 " + origin.key()
                  + " 声明了 inheritOnCopy 但没有可继承的脱敏指令");
        }
        if (origin.isDerived()) {
          throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
              "输出列 " + output.ordinal() + " ('" + output.outputName()
                  + "') 是对继承列的表达式变换,复制继承仅支持直接列引用");
        }
        // 每个继承目标:先做目标列自身策略冲突检查(该列已有策略即拒绝)
        String targetColumn = targetColumnOf(output.ordinal(), output.outputName());
        String[] target = targetTableOf(origin);
        if (engine.maskFor(target[0], target[1], target[2], targetColumn, subject).isPresent()) {
          conflictColumn = target[0] + "." + target[1] + "." + target[2] + "." + targetColumn;
          return;
        }
        inheritedOrdinals.add(output.ordinal());
        inherited.add(new InheritedColumn(target[0], target[1], target[2], targetColumn,
            sourceTypeDeclaration(origin), origin, items));
      }
    }

    /**
     * 目标表三元组:catalog.schema.table。限定段数不足时,缺失段继承来源列的
     * catalog/schema(与引擎"1 段 → schema/catalog 继承、2 段 → catalog 继承"
     * 的解析约定一致)。
     */
    private String[] targetTableOf(ColumnOrigin origin) {
      SqlIdentifier target;
      if (write.getKind() == SqlKind.INSERT) {
        target = (SqlIdentifier) ((SqlInsert) write).getTargetTable();
      } else if (write.getKind() == SqlKind.CREATE_TABLE) {
        target = ((SqlCreateTable) write).name;
      } else {
        throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
            "无法解析复制语句的目标表");
      }
      List<String> names = target.names;
      if (names.size() == 3) {
        return new String[] {names.get(0), names.get(1), names.get(2)};
      }
      if (names.size() == 2) {
        return new String[] {origin.key().catalog(), names.get(0), names.get(1)};
      }
      if (names.size() == 1) {
        return new String[] {origin.key().catalog(), origin.key().schema(), names.get(0)};
      }
      throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
          "复制语句的目标表名必须是 1~3 段限定标识符,实际为 " + names.size() + " 段");
    }

    /** 目标列名:INSERT 目标列清单 / CREATE TABLE 列清单按输出序号取,否则用输出列名。 */
    private String targetColumnOf(int ordinal, String outputName) {
      if (write.getKind() == SqlKind.INSERT) {
        SqlNodeList columns = ((SqlInsert) write).getTargetColumnList();
        if (columns != null && !columns.isEmpty()) {
          return targetColumnAt(columns, ordinal);
        }
      } else if (write.getKind() == SqlKind.CREATE_TABLE) {
        SqlNodeList columns = ((SqlCreateTable) write).columnList;
        if (columns != null && !columns.isEmpty()) {
          return targetColumnAt(columns, ordinal);
        }
      }
      return outputName;
    }

    private static String targetColumnAt(SqlNodeList columns, int ordinal) {
      if (ordinal < 0 || ordinal >= columns.size()) {
        throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
            "复制语句的列清单与输出列数不匹配:输出列 " + ordinal + " 没有对应的目标列名");
      }
      SqlNode node = columns.get(ordinal);
      if (node instanceof SqlIdentifier identifier) {
        return identifier.getSimple();
      }
      if (node instanceof SqlColumnDeclaration declaration) {
        return declaration.name.getSimple();
      }
      return node.toString();
    }

    /** 来源列的类型声明;元数据中找不到来源表列时返回 null(注册方跳过该列类型)。 */
    private String sourceTypeDeclaration(ColumnOrigin origin) {
      ColumnKey key = origin.key();
      return loaded.findTable(key.catalog(), key.schema(), key.table())
          .flatMap(table -> table.columns().stream()
              .filter(column -> ColumnKey.normalize(column.name(), "column").equals(key.column()))
              .findFirst())
          .map(TableMetadata.Column::typeDeclaration)
          .orElse(null);
    }
  }
}
