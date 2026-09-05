# SQL 脱敏改写 CLI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于 Apache Calcite 实现一个只改写查询、不执行 SQL 的 PostgreSQL SQL 脱敏 CLI。

**Architecture:** CLI 读取 YAML 表结构和列策略，构造 Calcite schema 后逐条解析并校验 `SELECT`/`WITH ... SELECT`。改写器将有策略命中的原始查询整体包入派生表，在最外层对最终输出列调用配置的 UDF；普通 CTE、子查询和查询条件保持在内层。列来源由 Calcite 的关系表达式和 `RelMetadataQuery.getColumnOrigins` 分析，策略索引使用精确的 `catalog.schema.table.column`。

**Tech Stack:** Java、Maven、Apache Calcite、SnakeYAML（或同等 YAML 库）、picocli（或同等 CLI 库）、JUnit 5。

**Spec:** `docs/superpowers/specs/2026-09-05-sql-mask-cli-design.md`

## Global Constraints

- 只接受 `SELECT` 和 `WITH ... SELECT`；`INSERT`、`UPDATE`、`DELETE`、DDL 和 `CREATE TABLE AS SELECT` 第一版直接失败。
- 一次输入允许多条 SQL；逐条处理并保持顺序，任意一条失败则整次执行失败且不输出部分结果。
- PostgreSQL 是第一版唯一实现的方言；不使用 PostgreSQL 派生表列别名列表作为通用改写机制。
- 原始查询内部不插入 UDF；只在至少一个最终输出列命中策略时增加最外层包装。
- 普通非递归 CTE 和子查询必须参与最终输出列血缘追踪；CTE 内部不脱敏。
- 精确匹配 `catalog.schema.table.column`；不支持通配符、正则、标签和 `search_path`。
- 来源集合存在多个策略时，第一版按规范化 `ColumnKey` 字典序稳定选择一个；不实现优先级和类型适配校验。
- 找到原始列但没有策略时原样输出；SQL 列无法解析或来源状态为 `UNKNOWN` 时失败。
- YAML 声明所有 SQL 引用的表及其完整列结构；类型只用于 Calcite 解析和推导，不参与策略选择。
- 工具不连接查询引擎、不执行 SQL、不实现 UDF。
- 不创建 git commit；每个任务只运行测试并留下工作区变更。

---

## 文件结构与职责

计划完成后形成以下主要结构（包名以项目实际 groupId 为准，但职责和接口保持一致）：

```text
pom.xml
src/main/java/.../cli/SqlMaskApplication.java
src/main/java/.../config/MaskingConfig.java
src/main/java/.../config/YamlConfigLoader.java
src/main/java/.../metadata/ColumnKey.java
src/main/java/.../metadata/TableMetadata.java
src/main/java/.../metadata/YamlMetadataProvider.java
src/main/java/.../policy/MaskingPolicy.java
src/main/java/.../policy/PolicyRegistry.java
src/main/java/.../dialect/DialectAdapter.java
src/main/java/.../dialect/PostgresqlDialectAdapter.java
src/main/java/.../sql/SqlStatementSplitter.java
src/main/java/.../sql/SqlValidatorFactory.java
src/main/java/.../lineage/OutputLineage.java
src/main/java/.../lineage/LineageAnalyzer.java
src/main/java/.../rewrite/RewritePlan.java
src/main/java/.../rewrite/SqlRewriteService.java
src/main/java/.../error/SqlMaskException.java
src/test/java/.../...
```

`LineageAnalyzer` 只负责输出字段与基础列来源的分析；`PolicySelector` 负责策略选择；`SqlRewriteService` 负责生成外层 SQL。CLI、配置、Calcite 和改写逻辑不互相越权。

## Task 1: Maven 项目骨架与可执行入口

**Files:**
- Create: `pom.xml`
- Modify: `README.md`
- Create: `src/main/java/<group-path>/cli/SqlMaskApplication.java`
- Create: `src/main/java/<group-path>/error/SqlMaskException.java`
- Create: `src/test/java/<group-path>/cli/SqlMaskApplicationTest.java`

**Interfaces:**
- Produces `public static void main(String[] args)` as the CLI entry point.
- Produces a testable `SqlMaskApplication.run(String[] args, InputStream in, PrintStream out, PrintStream err): int`.
- Produces `SqlMaskException` with an error code/message suitable for stderr.

- [ ] **Step 1: Write the failing smoke test**

```java
@Test
void noArgumentsReturnsNonZeroAndWritesDiagnostic() {
  var out = new ByteArrayOutputStream();
  var err = new ByteArrayOutputStream();
  int code = app.run(new String[0], System.in,
      new PrintStream(out), new PrintStream(err));
  assertNotEquals(0, code);
  assertTrue(err.toString(UTF_8).contains("metadata"));
}
```

- [ ] **Step 2: Run the test and verify the project is not yet configured**

Run: `mvn -q -Dtest=SqlMaskApplicationTest test`
Expected: FAIL because `pom.xml` and the application classes do not yet exist.

- [ ] **Step 3: Add the minimal Maven project**

Configure Java release, Calcite core/server dependencies, YAML dependency, CLI dependency, and JUnit 5. Configure the Surefire plugin and an executable main class. Keep dependency versions in properties so later upgrades are isolated.

- [ ] **Step 4: Implement argument validation and error return**

Support the required options `--metadata`, exactly one of `--sql`/`--input`, optional `--output`, and optional `--dialect` defaulting to `postgresql`. Return zero only after the service completes successfully; write diagnostics to stderr and never write a partial result.

- [ ] **Step 5: Run the smoke test**

Run: `mvn -q -Dtest=SqlMaskApplicationTest test`
Expected: PASS.

- [ ] **Step 6: Update README with build and CLI examples**

Document:

```bash
mvn test
java -jar target/sql-mask.jar --metadata metadata.yaml --sql "SELECT phone FROM customer;"
java -jar target/sql-mask.jar --metadata metadata.yaml --input query.sql --output masked.sql
```

Do not commit.

## Task 2: YAML configuration model and validation

**Files:**
- Create: `src/main/java/<group-path>/config/MaskingConfig.java`
- Create: `src/main/java/<group-path>/config/YamlConfigLoader.java`
- Create: `src/main/java/<group-path>/metadata/ColumnKey.java`
- Create: `src/main/java/<group-path>/metadata/TableMetadata.java`
- Create: `src/main/java/<group-path>/policy/MaskingPolicy.java`
- Create: `src/main/java/<group-path>/policy/PolicyRegistry.java`
- Create: `src/test/java/<group-path>/config/YamlConfigLoaderTest.java`
- Create: `src/test/resources/metadata/valid.yaml`
- Create: `src/test/resources/metadata/invalid.yaml`

**Interfaces:**
- `ColumnKey.of(String catalog, String schema, String table, String column): ColumnKey`.
- `PolicyRegistry.find(ColumnKey key): Optional<MaskingPolicy>`.
- `YamlConfigLoader.load(Path path): LoadedConfig`.
- `MaskingPolicy` exposes `name`, `udf`, and ordered scalar `arguments`.
- `TableMetadata` exposes complete qualified identity and ordered columns with Calcite-compatible type descriptors.

- [ ] **Step 1: Write tests for the exact YAML contract**

```java
@Test
void loadsCompleteTableAndPolicy() { /* assert crm.public.customer.phone -> phone_mask */ }

@Test
void rejectsMissingQualifiedTablePart() { /* catalog/schema/table/name failure */ }

@Test
void rejectsColumnPolicyReferencingUnknownPolicy() { /* explicit diagnostic */ }

@Test
void preservesArgumentOrder() { /* [3, 4] remains [3, 4] */ }
```

- [ ] **Step 2: Run tests and verify failure**

Run: `mvn -q -Dtest=YamlConfigLoaderTest test`
Expected: FAIL because the configuration classes do not yet exist.

- [ ] **Step 3: Implement immutable configuration records/classes**

Represent table columns separately from policy bindings. Require complete catalog/schema/table/column fields for policy entries and complete table identity plus column name/type for metadata. Use immutable lists and maps; reject duplicate table keys, duplicate column definitions, duplicate policy names, and unknown policy references.

- [ ] **Step 4: Implement identifier normalization**

Centralize normalization in `ColumnKey`: unquoted PostgreSQL-style names are normalized consistently with the Calcite lexical configuration; quoted/case-sensitive names retain their case as represented by the parser/configuration. Do not silently merge semantically distinct names.

- [ ] **Step 5: Implement YAML loading and semantic validation**

Load `metadata.tables`, `columns`, and `policies`; validate scalar argument values; create the `PolicyRegistry`; report path-oriented errors such as `columns[0].policy` instead of generic parser errors.

- [ ] **Step 6: Run tests**

Run: `mvn -q -Dtest=YamlConfigLoaderTest test`
Expected: PASS.

## Task 3: Calcite schema, PostgreSQL parser, and statement splitting

**Files:**
- Create: `src/main/java/<group-path>/metadata/YamlCalciteSchemaFactory.java`
- Create: `src/main/java/<group-path>/dialect/DialectAdapter.java`
- Create: `src/main/java/<group-path>/dialect/PostgresqlDialectAdapter.java`
- Create: `src/main/java/<group-path>/sql/SqlStatementSplitter.java`
- Create: `src/main/java/<group-path>/sql/SqlValidatorFactory.java`
- Create: `src/test/java/<group-path>/sql/SqlStatementSplitterTest.java`
- Create: `src/test/java/<group-path>/dialect/PostgresqlDialectAdapterTest.java`

**Interfaces:**
- `DialectAdapter.parse(String sql): List<SqlNode>`.
- `DialectAdapter.validate(SqlNode node, SchemaPlus schema): ValidatedSql`.
- `DialectAdapter.unparse(SqlNode node): String`.
- `DialectAdapter.capabilities(): DialectCapabilities`.
- `SqlStatementSplitter.split(String sql): List<String>`.
- `YamlCalciteSchemaFactory.create(LoadedConfig): SchemaPlus`.

- [ ] **Step 1: Write parser and splitter tests**

Cover:

```sql
SELECT phone FROM customer;

WITH active AS (SELECT phone FROM customer)
SELECT phone FROM active;
```

Assert two statements, preserved order, empty statements ignored, and semicolons inside quoted strings not split. Assert `INSERT`, `CREATE`, and `UPDATE` are rejected after parsing/classification.

- [ ] **Step 2: Run tests and verify failure**

Run: `mvn -q -Dtest=SqlStatementSplitterTest,PostgresqlDialectAdapterTest test`
Expected: FAIL because the dialect and schema implementations do not exist.

- [ ] **Step 3: Build Calcite schema from YAML**

Create catalog/schema namespaces and `Table` implementations exposing ordered fields and `RelDataType`. Map the supported scalar YAML types (`boolean`, integer variants, floating types, decimal, char/varchar/text, date, timestamp) to `SqlTypeName`. Reject unknown types during configuration load.

- [ ] **Step 4: Configure the PostgreSQL dialect**

Use Calcite PostgreSQL parser/unparser configuration where available, configure case handling consistently with `ColumnKey`, and register the schema as the default validation schema. Keep the adapter interface free of PostgreSQL-only types.

- [ ] **Step 5: Implement safe statement splitting and classification**

Use Calcite parsing rather than a regex to classify statements. Accept only `SqlSelect` and `SqlWith` whose body is a query; reject DML/DDL with a diagnostic containing the statement ordinal.

- [ ] **Step 6: Run tests**

Run: `mvn -q -Dtest=SqlStatementSplitterTest,PostgresqlDialectAdapterTest test`
Expected: PASS.

## Task 4: Output field metadata and column lineage analysis

**Files:**
- Create: `src/main/java/<group-path>/lineage/LineageStatus.java`
- Create: `src/main/java/<group-path>/lineage/ColumnOrigin.java`
- Create: `src/main/java/<group-path>/lineage/OutputLineage.java`
- Create: `src/main/java/<group-path>/lineage/LineageAnalyzer.java`
- Create: `src/test/java/<group-path>/lineage/LineageAnalyzerTest.java`
- Create: `src/test/resources/metadata/lineage.yaml`

**Interfaces:**
- `LineageAnalyzer.analyze(RelNode root): List<OutputLineage>`.
- `OutputLineage` contains output ordinal, output name, output type, `Set<ColumnOrigin>`, and `LineageStatus`.
- `ColumnOrigin` contains `ColumnKey`, source table ordinal/name where available, and equality based on the normalized `ColumnKey`.
- `LineageStatus` is `RESOLVED`, `NO_ORIGIN`, or `UNKNOWN`.

- [ ] **Step 1: Write lineage tests first**

Cover:

```sql
SELECT c.id, c.phone FROM crm.public.customer c;
SELECT lower(c.email) AS normalized_email FROM crm.public.customer c;
SELECT concat(c.email, '-', c.phone) AS contact FROM crm.public.customer c;
SELECT 1 AS constant_value FROM crm.public.customer c;
WITH active AS (SELECT phone FROM crm.public.customer) SELECT phone FROM active;
WITH a AS (SELECT phone FROM crm.public.customer), b AS (SELECT phone FROM a) SELECT phone FROM b;
```

Assert direct columns and CTE outputs resolve to the base `ColumnKey`; the constant is `NO_ORIGIN`; unknown metadata is `UNKNOWN`.

- [ ] **Step 2: Run tests and verify failure**

Run: `mvn -q -Dtest=LineageAnalyzerTest test`
Expected: FAIL because the lineage model/analyzer does not exist.

- [ ] **Step 3: Convert validated SQL to `RelNode` and identify root output fields**

Use `RelBuilder`/`SqlToRelConverter` from the dialect validator configuration. Locate the root projection and preserve output ordinal, field name and type. Do not analyze internal CTE projections as output candidates.

- [ ] **Step 4: Use `RelMetadataQuery.getColumnOrigins`**

For each root output ordinal, call `getColumnOrigins(rootProject, ordinal)` (or the appropriate relational node carrying the output). Treat `null` as `UNKNOWN`, an empty set as `NO_ORIGIN`, and a non-empty set as `RESOLVED`. Convert each `RelColumnOrigin` to normalized `ColumnKey` using the YAML-backed table identity.

- [ ] **Step 5: Handle CTE and project mappings**

Ensure Calcite’s converted relational tree preserves CTE/derived-table field ordinals and that origin metadata is requested from the correct node. Add a bounded recursion guard for recursive relations; do not infinitely expand `WITH RECURSIVE`. If the origin cannot be determined, return `UNKNOWN`.

- [ ] **Step 6: Run lineage tests**

Run: `mvn -q -Dtest=LineageAnalyzerTest test`
Expected: PASS.

## Task 5: Strategy selection and outer projection planning

**Files:**
- Create: `src/main/java/<group-path>/policy/PolicySelector.java`
- Create: `src/main/java/<group-path>/rewrite/OutputRewrite.java`
- Create: `src/main/java/<group-path>/rewrite/RewritePlan.java`
- Create: `src/test/java/<group-path>/policy/PolicySelectorTest.java`
- Create: `src/test/java/<group-path>/rewrite/RewritePlanTest.java`

**Interfaces:**
- `PolicySelector.select(Set<ColumnOrigin> origins, PolicyRegistry registry): Optional<MaskingPolicy>`.
- `RewritePlan` contains the original query, ordered `OutputRewrite` entries, and `requiresWrapper()`.
- `OutputRewrite` contains output ordinal/name, selected policy (optional), and the inner result reference.

- [ ] **Step 1: Write policy selection tests**

```java
@Test
void noOriginPolicyProducesNoSelection() { /* empty origins -> empty */ }

@Test
void oneMatchingOriginSelectsItsPolicy() { /* exact ColumnKey lookup */ }

@Test
void multipleMatchesUseNormalizedColumnKeyOrder() { /* independent of YAML order */ }
}
```

- [ ] **Step 2: Run tests and verify failure**

Run: `mvn -q -Dtest=PolicySelectorTest,RewritePlanTest test`
Expected: FAIL because the selector and plan classes do not exist.

- [ ] **Step 3: Implement deterministic selector**

Filter origins to those with a policy, sort by normalized `ColumnKey` comparator, and return the first policy. Keep this comparator behind `PolicySelector` so a priority-based selector can replace it later. Do not inspect output aliases or expression traversal order.

- [ ] **Step 4: Implement plan construction**

For each output lineage, create one output rewrite. `UNKNOWN` raises a rewrite error; `NO_ORIGIN` or resolved-without-policy produces a passthrough entry. Set `requiresWrapper` only when at least one policy is selected.

- [ ] **Step 5: Run tests**

Run: `mvn -q -Dtest=PolicySelectorTest,RewritePlanTest test`
Expected: PASS.

## Task 6: SQL outer-wrapper generation

**Files:**
- Create: `src/main/java/<group-path>/rewrite/SqlRewriteService.java`
- Create: `src/main/java/<group-path>/rewrite/SqlIdentifierRenderer.java`
- Create: `src/test/java/<group-path>/rewrite/SqlRewriteServiceTest.java`

**Interfaces:**
- `SqlRewriteService.rewrite(ValidatedSql validated, RewritePlan plan, DialectAdapter dialect): String`.
- `SqlIdentifierRenderer.render(Identifier identifier): String`.

- [ ] **Step 1: Write SQL rewrite tests**

Assert exact structural results for:

```sql
SELECT c.id, c.phone FROM crm.public.customer c
```

becoming an outer projection with `r.id` and `mask_phone(r.phone, 3, 4)`; assert the inner SQL retains `WHERE`, `JOIN`, `GROUP BY`, `ORDER BY`, `LIMIT`, `DISTINCT`, and CTE text/semantics. Assert an all-unmasked query is returned unchanged.

- [ ] **Step 2: Run tests and verify failure**

Run: `mvn -q -Dtest=SqlRewriteServiceTest test`
Expected: FAIL because wrapper generation does not exist.

- [ ] **Step 3: Build the wrapper only when required**

Generate:

```sql
SELECT
    r.id,
    mask_phone(r.phone, 3, 4) AS phone
FROM (
    <original query>
) AS r;
```

Use the validated root output names as references. Generate UDF calls from the configured UDF identifier and ordered scalar arguments; render literals through Calcite rather than string concatenation where possible.

- [ ] **Step 4: Preserve output names and quoting**

Use output ordinal internally, but render each original output name/alias in the outer projection. Preserve quoted case and reserved-word quoting. For duplicate output names, retain the original output shape and route the case through a dialect capability check; do not silently bind both references to an ambiguous name.

- [ ] **Step 5: Protect inner query semantics**

Do not add outer predicates, ordering, grouping, pagination, or UDFs inside the original SQL. Ensure a `WITH` query is wrapped as one complete inner query rather than moving the `WITH` outside the wrapper.

- [ ] **Step 6: Run tests**

Run: `mvn -q -Dtest=SqlRewriteServiceTest test`
Expected: PASS for the supported unique-output-name cases and a clear unsupported diagnostic for unresolved duplicate-name wrapping.

## Task 7: CLI orchestration, multi-statement atomic output, and file I/O

**Files:**
- Modify: `src/main/java/<group-path>/cli/SqlMaskApplication.java`
- Create: `src/main/java/<group-path>/cli/CliOptions.java`
- Create: `src/main/java/<group-path>/cli/SqlMaskRunner.java`
- Create: `src/test/java/<group-path>/cli/SqlMaskRunnerTest.java`
- Create: `src/test/resources/queries/multi.sql`

**Interfaces:**
- `SqlMaskRunner.run(CliOptions options): String` returns the complete output only after every statement succeeds.
- `CliOptions` contains metadata path, optional inline SQL, optional input/output paths, and dialect name.

- [ ] **Step 1: Write orchestration tests**

Cover:

```sql
SELECT phone FROM customer;

WITH active AS (SELECT email FROM customer)
SELECT email FROM active;
```

Assert both statements are rewritten in order. Add a second statement with an unsupported `INSERT` and assert an exception, no output file creation/replacement, and a non-zero CLI status.

- [ ] **Step 2: Run tests and verify failure**

Run: `mvn -q -Dtest=SqlMaskRunnerTest test`
Expected: FAIL because the runner does not exist.

- [ ] **Step 3: Implement service composition**

Load YAML once; create schema, dialect, parser/validator, lineage analyzer, policy selector, planner, and rewrite service. For each statement, validate, convert, analyze, select, and rewrite. If no policy is selected, append the original statement output unchanged.

- [ ] **Step 4: Implement atomic result handling**

Collect all rendered statements in memory. Only write to stdout or the output path after the entire input succeeds. Join statements with a stable separator and trailing semicolon policy; document the formatting guarantee as Calcite-generated SQL rather than original formatting preservation.

- [ ] **Step 5: Implement input/output options**

Support `--sql` and `--input`; reject both together unless a deterministic precedence is explicitly documented (prefer reject). Write `--output` using UTF-8. Never overwrite an output file after a failed statement.

- [ ] **Step 6: Run tests**

Run: `mvn -q -Dtest=SqlMaskRunnerTest,SqlMaskApplicationTest test`
Expected: PASS.

## Task 8: End-to-end regression suite and documentation alignment

**Files:**
- Create: `src/test/java/<group-path>/integration/SqlMaskIntegrationTest.java`
- Create: `src/test/resources/metadata/integration.yaml`
- Create: `src/test/resources/queries/with-and-nested.sql`
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-09-05-sql-mask-cli-design.md` only if implementation-discovered behavior requires an explicit correction

- [ ] **Step 1: Add end-to-end fixtures**

Use metadata for `crm.public.customer` with `id`, `phone`, `email`, `status`, and policies `mask_phone` and `mask_email`. Include queries for:

```sql
SELECT id, phone, email FROM customer WHERE status = 'ACTIVE';
WITH active AS (SELECT phone FROM customer WHERE status = 'ACTIVE') SELECT phone FROM active;
SELECT concat(email, '-', phone) AS contact FROM customer;
SELECT id, name FROM customer;
```

- [ ] **Step 2: Add assertions for required semantics**

Assert:

- no-policy input is byte-for-byte or normalized-equivalent original output according to the selected output policy;
- partial policy input gets one outer wrapper;
- inner `WHERE` remains unmasked;
- CTE and nested query output resolves to base columns;
- multi-source selection is deterministic by normalized key, not YAML order;
- UDF is called once per output column;
- multiple input statements preserve order;
- invalid/unknown/ambiguous input fails atomically.

- [ ] **Step 3: Run the complete test suite**

Run: `mvn test`
Expected: PASS.

- [ ] **Step 4: Run the executable CLI manually**

Run:

```bash
java -jar target/sql-mask.jar --metadata src/test/resources/metadata/integration.yaml --input src/test/resources/queries/with-and-nested.sql
```

Expected: rewritten SQL on stdout and no query-engine connection attempt.

- [ ] **Step 5: Align README and spec**

Document actual command behavior, error examples, supported scalar types, CTE lineage behavior, no-policy passthrough, and the duplicate-output-name dialect limitation. Keep all deferred features in the spec’s backlog.

- [ ] **Step 6: Run final verification**

Run: `mvn clean test`
Expected: PASS with no generated output accidentally tracked and no git commit performed.

## Self-review checklist

- [ ] Every spec-supported behavior maps to a task: PostgreSQL parsing/output (Tasks 1, 3, 6), multi-statement CLI (Task 7), outer-only masking (Task 6), CTE/subquery lineage (Task 4), exact YAML policies and full table metadata (Task 2), no-policy passthrough (Tasks 5–7), and errors (Tasks 3, 4, 7).
- [ ] Every spec-deferred behavior remains deferred: DML/DDL, wildcard/regex/tag policies, engine metadata loading, priority rules, UDF type validation, recursive CTE completeness, complex PostgreSQL types, non-output masking, and multi-dialect implementations.
- [ ] No task requires modifying inner query aliases as a generic cross-dialect workaround.
- [ ] No task silently treats an unknown lineage result as “no policy”.
- [ ] No task commits changes; the user explicitly requested no git commit.
