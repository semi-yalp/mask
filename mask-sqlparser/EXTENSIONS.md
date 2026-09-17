# mask-sqlparser codegen provenance & extension registry

## Provenance: Babel base (since Task 5)

The grammar base is **calcite-babel 1.42.0's codegen data**, per the Task 5
ruling (Ruling-5): the differential requirement is equivalence with
`SqlBabelParserImpl`, which ships Babel's dialect extensions (LEFT SEMI/ANTI
JOIN, `CREATE TABLE ... AS SELECT`, `::` infix cast, `<=>`, Oracle-style
CONVERT resolution via non-reserved INT/INTEGER, POSIX operators, custom
identifier token, ...). Those live in Babel's own `config.fmpp` +
`includes/*.ftl`, **not** in a Babel-specific `Parser.jj`.

Upstream source: GitHub tag `calcite-1.42.0`
(`https://codeload.github.com/apache/calcite/tar.gz/refs/tags/calcite-1.42.0`).
The calcite-babel binary jar ships **no** codegen files, so they are taken
from the tag. Note 1.42.0 builds with Gradle; its `babel/build.gradle.kts`
pins how the pieces fit:

```kotlin
val fmppMain by tasks.registering(FmppTask::class) {
    config.set(file("src/main/codegen/config.fmpp"))
    templates.set(file("$rootDir/core/src/main/codegen/templates"))  // core's!
}
// FmppTask data: tdd(babel config.fmpp), default: tdd(core default_config.fmpp)
```

Consequences (verified against the 1.42.0 tag):

| Vendor file | Upstream origin |
|---|---|
| `templates/Parser.jj` | **core** `core/src/main/codegen/templates/Parser.jj` (byte-identical to what babel builds from; babel ships no template of its own) |
| `default_config.fmpp` (nested in `config.fmpp` under `data.default`) | **core** `core/src/main/codegen/default_config.fmpp` (babel has none; Gradle FmppTask passes core's as the `default` data layer) |
| `includes/parserImpls.ftl` | **babel** `babel/src/main/codegen/includes/parserImpls.ftl` (verbatim) |
| `includes/parserPostgresImpls.ftl` | **babel** `babel/src/main/codegen/includes/parserPostgresImpls.ftl` (verbatim, added in Task 5) |
| `includes/compoundIdentifier.ftl` | **core** `core/src/main/codegen/includes/compoundIdentifier.ftl` (verbatim; babel ships none and inherits core's) |
| `includes/maskParserImpls.ftl` | **mask-owned** (mask extensions only; never carries upstream content) |
| `config.fmpp` | mask-owned merge: `data.parser` override layer = **babel's config.fmpp values + mask additions** (whole-key replacement), `data.default` = core `default_config.fmpp` verbatim, `freemarkerLinks` unchanged |

Core vs babel codegen diff conclusion (Task 5, tag `calcite-1.42.0`):

- `templates/Parser.jj`: babel uses core's unchanged — no diff to carry.
- `default_config.fmpp`: babel has none; core's is the effective default for
  babel too — no diff to carry.
- `includes/compoundIdentifier.ftl`: babel has none; inherits core's — no
  diff to carry.
- `includes/parserImpls.ftl`: babel's replaces core's jar-side
  comment-only placeholder; it contains real productions (LeftSemiJoin,
  LeftAntiJoin, IfNotExistsOpt/TableCollectionTypeOpt/VolatileOpt/
  ExtendColumnList/ColumnWithType/SqlCreateTable -> `SqlBabelCreateTable`,
  DatePart/Dateadd builtin calls, extra tokens `DATE_PART/DATEADD/DATEDIFF/
  DATEPART/NEGATE/ TILDE`, InfixCast, NullSafeEqual).
- `includes/parserPostgresImpls.ftl`: new file (babel only): postgres
  SHOW/SET RESET/BEGIN/COMMIT/ROLLBACK/DISCARD productions.
- `config.fmpp`: babel overrides `package/class`, `imports`, `keywords`,
  `nonReservedKeywordsToAdd`, `joinTypes`, `builtinFunctionCallMethods`,
  `createStatementParserMethods`, `statementParserMethods`,
  `binaryOperatorsTokens`, `customIdentifierToken`,
  `extraBinaryExpressions`, `implementationFiles`,
  `setOptionParserMethod`, and flips `includePosixOperators`,
  `includeParsingStringLiteralAsArrayLiteral`,
  `includeIntervalWithoutQualifier`, `includeStarExclude`,
  `includeSelectBy` to `true`.

Because generated `SqlMaskParserImpl` references
`org.apache.calcite.sql.babel.*` node classes (`SqlBabelCreateTable`,
postgres nodes, ...), `calcite-babel` is a **main-scope** dependency of this
module (not test-scope). `BabelEquivalenceTest` additionally uses its
`SqlBabelParserImpl.FACTORY` as the differential baseline.

Extraction command (Task 5, from the tag tarball):

```bash
curl -sL -o calcite.tar.gz https://codeload.github.com/apache/calcite/tar.gz/refs/tags/calcite-1.42.0
tar -xzf calcite.tar.gz calcite-calcite-1.42.0/babel/src/main/codegen \
    calcite-calcite-1.42.0/core/src/main/codegen
cp calcite-calcite-1.42.0/babel/src/main/codegen/includes/parserImpls.ftl      mask-sqlparser/src/main/codegen/includes/
cp calcite-calcite-1.42.0/babel/src/main/codegen/includes/parserPostgresImpls.ftl mask-sqlparser/src/main/codegen/includes/
# Parser.jj / default_config.fmpp / compoundIdentifier.ftl: core versions already vendored
```

Note: `Parser.jj` conditionally includes `braces.ftl` and
`tokenManagerDeclarations.ftl`, which are NOT shipped in the binary jar. With
the current config (`includeBraces` default true,
`includeAdditionalDeclarations` default false) neither branch fires, so
baseline builds work. If a future task flips either flag, vendor those two
files from the calcite-1.42.0 tag first.

`config.fmpp` is self-contained (same mechanism as upstream): upstream passes
`data: tdd(module config), default: tdd(default_config.fmpp)`; we nest
`default_config.fmpp` (464 lines, root key `parser`) verbatim under the
`default:` key, so the template's `parser.X!default.parser.X` fallbacks
resolve identically. The top-level `parser:` key holds the override layer.
Upstream (and therefore our merge) replaces **whole keys**: every key that
babel's config.fmpp sets must appear in the override layer with
**babel's original value + mask additions** merged.

## Build chain

`fmpp-maven-plugin 1.0` expands `templates/Parser.jj` with `config.fmpp` data
into `target/generated-sources/fmpp/Parser.jj`; `javacc-maven-plugin 2.6`
compiles that into `target/generated-sources/javacc/io/sqlmask/parser/SqlMaskParserImpl.java`
(plugin `3.0.3` does exist on Maven Central, but its default-bound javacc 7.x
flattens switch-case action blocks into a single scope — babel's productions
trip duplicate-variable-declaration compile errors on javacc 5+; 2.6 is
therefore pinned with `net.java.dev.javacc:javacc:4.0` inside the plugin,
matching the upstream Gradle build, plus `lookAhead 2`).

Public interface produced: `io.sqlmask.parser.SqlMaskParserImpl.FACTORY`
(`org.apache.calcite.sql.parser.SqlParserImplFactory`).

## Extension diff registry

Baseline = **calcite-babel 1.42.0 codegen data on core 1.42.0's template**
(i.e. an unmodified Babel parser). Current diff:

| Task | File | Change |
|---|---|---|
| Task 3 | `includes/maskParserImpls.ftl` (mask-owned) | `SqlMaskInsertOverwrite()` production; gated by `SqlMaskConformance.isInsertOverwriteAllowed()`, node `io.sqlmask.parser.SqlInsertOverwrite` |
| Task 3 | `config.fmpp` override layer | `keywords += OVERWRITE`; `nonReservedKeywordsToAdd += OVERWRITE`; `imports += SqlInsertOverwrite, SqlMaskConformance`; `statementParserMethods += SqlMaskInsertOverwrite()`; `implementationFiles += maskParserImpls.ftl` |
| Task 4 | `templates/Parser.jj` | Two patches on core's template, line anchors valid for the vendored 1.42.0 template (re-check on upgrade): (a) `SqlSelect()` — `SqlNode topFetch = null;` declared at **line 1387**; after `SqlSelectKeywords`/`AllOrDistinct` an optional `topFetch = SqlMaskTopN()` at **lines 1405–1410**, guarded by semantic LOOKAHEAD `getToken(1).kind == TOP && (getToken(2).kind == LPAREN \|\| getToken(2).kind == UNSIGNED_INTEGER_LITERAL)`; result wired into the select's `fetch` slot in `new SqlSelect(...)` at **line 1439**; (b) `OrderByLimitOpt()` conflict guard at **lines 742–751** — if the sub-query already carries a fetch (i.e. TOP) and OFFSET/FETCH also present, throw `ParseException("TOP cannot be combined with OFFSET/LIMIT/FETCH ...")` |
| Task 4 | `includes/maskParserImpls.ftl` (mask-owned) | `SqlMaskTopN()` production: `TOP (expr | literal)`, rejects `TOP ... PERCENT` and `TOP ... WITH TIES`, gated by `SqlMaskConformance.isTopNAllowed()` |
| Task 4 | `config.fmpp` override layer | `keywords += TOP`; `nonReservedKeywordsToAdd += TOP` |
| Task 5 | `config.fmpp` override layer | switched from core baseline to **babel merged values**: `keywords`/`nonReservedKeywordsToAdd`/`imports`/`statementParserMethods`/`implementationFiles` became "babel originals + mask items" (see rows above); all other babel-only keys carried verbatim |
| Task 5 | `includes/parserImpls.ftl`, `includes/parserPostgresImpls.ftl` | replaced with babel 1.42.0 verbatim (dialect productions; no mask edits) |
| Task 5 | `pom.xml` | `calcite-babel` main-scope dependency (generated parser needs `org.apache.calcite.sql.babel.*`) |

Nothing else: `SqlMaskInsertOverwrite`/`SqlMaskTopN` only rely on
`this.conformance` and base-grammar productions, so they compile unchanged on
the babel base.

### Override-layer key checklist (config.fmpp `data.parser`, whole-key replacement)

The mask-owned override layer touches exactly these keys (everything else is
babel-carried verbatim or resolved via the nested `default:` layer):

| Key | Content |
|---|---|
| `package` / `class` | `io.sqlmask.parser` / `SqlMaskParserImpl` |
| `implementationFiles` | babel's `parserImpls.ftl`, `parserPostgresImpls.ftl` + mask's `maskParserImpls.ftl` |
| `keywords` | babel originals (ANTI, DISCARD, IF, PLANS, SEED, SEMI, SEQUENCES, SNAPSHOT, TEMP, VOLATILE) + mask `TOP`, `OVERWRITE` |
| `nonReservedKeywordsToAdd` | babel's full list + mask `TOP`, `OVERWRITE` (kept non-reserved so identifier usages keep working) |
| `imports` | babel originals (SqlBabelCreateTable, postgres nodes, ...) + mask `SqlInsertOverwrite`, `SqlMaskConformance` |
| `statementParserMethods` | babel's postgres statements + mask `SqlMaskInsertOverwrite()` (SqlStmt first-alternative hook, LOOKAHEAD(2), before base `SqlInsert()`) |

### Status write-back (2026-09-17, after Tasks 6–7)

- Production switch is complete: MySQL (`MYSQL_5`), PostgreSQL and Trino
  (`DEFAULT`) all parse with `SqlMaskParserImpl.FACTORY` via
  `SqlMaskConformance.of(<enum>, false, false)` — both extension switches off,
  differential-equivalent to Babel (`BabelEquivalenceTest`, 40-statement corpus
  with a >= 40 size lower-bound assertion).
- spec §11.3 conclusion: the `SqlValidatorImpl` subclass-dispatch risk does not
  exist. `SqlInsertOverwrite` (extends `SqlInsert`, kind stays `INSERT`) and
  `SqlBabelCreateTable` (extends `SqlCreateTable`) flow through the pipeline
  without any tree replacement; `AbstractCalciteDialectAdapter.validate` is
  query-root-scoped and the production pipeline (`RewriteEngine.rewriteOne`)
  validates the query extracted by `querySourceOf` — proven end to end by
  `mask-core` `MaskParserPipelineTest` (3 cases: OVERWRITE parse → source
  validate → compose → round-trip; TOP validate; rejection list).
- Known boundary (class-agnostic, pre-existing): feeding a multi-field
  `INSERT ... SELECT` root directly into `AbstractCalciteDialectAdapter.validate`
  trips the adapter's ORDER BY shape-guard (`RelOptUtil.createProject`
  misfire, `IndexOutOfBoundsException`); plain `INSERT INTO` behaves
  identically, so this is a write-root-vs-query-root contract matter, not a
  `SqlInsertOverwrite` dispatch problem. The pipeline never does this.
- Rejection list with switches on (`MaskParserPipelineTest`, fail-closed):
  `INSERT OVERWRITE ... PARTITION (...)`, `TOP ... PERCENT`,
  `TOP n ... LIMIT/FETCH`. `top(x)` function-call boundary is pinned by
  `IdentifierRegressionTest` (`SELECT top(1) FROM t` fails closed).
- Compound insert columns in `INSERT OVERWRITE` (fail-closed, parser
  production): the mask production drops the base grammar's `p.right`
  (column-type extend list) that `SqlInsert()` folds back into the table
  reference, so a non-empty `p.right` is rejected outright — pinned by
  `InsertOverwriteTest.rejectsCompoundInsertColumns`
  (`INSERT OVERWRITE TABLE t (a.b VARCHAR(10)) SELECT 1` fails with
  "compound insert columns are not supported"; `p.right` fills only when a
  column carries a type annotation — a bare compound name like `(a.b)`
  lands in `p.left` unchanged, same as base `INSERT INTO`, and is accepted).

## Calcite upgrade runbook

1. Bump `<calcite.version>` in the root pom.
2. Extract the new tag tarball; re-vendor `babel/src/main/codegen/includes/*.ftl`
   verbatim, `core/src/main/codegen/templates/Parser.jj`,
   `core/src/main/codegen/default_config.fmpp`
   (babel still has no template/default_config/compoundIdentifier of its own;
   re-check `babel/build.gradle.kts` if the build tooling changed).
3. Replace the block under `default:` in `config.fmpp` with the new
   core `default_config.fmpp` content verbatim; re-merge babel's
   `config.fmpp` original values into the `parser:` override layer, then
   re-apply the mask additions listed in the table above.
4. Replay every diff registered in the table above onto the new vendored files.
5. Re-run the differential test suite (`BabelEquivalenceTest`) and the
   three-dialect (MySQL/PostgreSQL/Trino) suites; extend `ParserSmokeTest`
   coverage for any new grammar surface before merging.
