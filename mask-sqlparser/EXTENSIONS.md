# mask-sqlparser codegen provenance & extension registry

## Provenance

All codegen inputs are vendored verbatim from the **calcite-core 1.42.0** jar
(the version pinned by the parent pom `<calcite.version>`):

| Vendored file | Source in jar |
|---|---|
| `src/main/codegen/templates/Parser.jj` | `codegen/templates/Parser.jj` |
| `src/main/codegen/includes/parserImpls.ftl` | `codegen/includes/parserImpls.ftl` |
| `src/main/codegen/includes/compoundIdentifier.ftl` | `codegen/includes/compoundIdentifier.ftl` |
| `src/main/codegen/default_config.fmpp` | `codegen/default_config.fmpp` |

Extraction command (run from `mask-sqlparser/src/main/codegen`):

```bash
JAR=~/.m2/repository/org/apache/calcite/calcite-core/1.42.0/calcite-core-1.42.0.jar
unzip -o "$JAR" "codegen/templates/Parser.jj" "codegen/includes/*" "codegen/default_config.fmpp" -d /tmp/mask-parser-vendor
cp /tmp/mask-parser-vendor/codegen/templates/Parser.jj templates/
cp /tmp/mask-parser-vendor/codegen/includes/parserImpls.ftl /tmp/mask-parser-vendor/codegen/includes/compoundIdentifier.ftl includes/
cp /tmp/mask-parser-vendor/codegen/default_config.fmpp .
```

The jar's jar-side `parserImpls.ftl` / `compoundIdentifier.ftl` are Calcite's
downstream extension-point placeholders (comment-only); the full core grammar
is inline in `Parser.jj`. They are the files that later tasks edit to add
syntax. If a needed file is missing from the jar, pull it from
`https://raw.githubusercontent.com/apache/calcite/calcite-1.42.0/core/src/main/codegen/...`.

Note: `Parser.jj` conditionally includes `braces.ftl` and
`tokenManagerDeclarations.ftl`, which are NOT shipped in the binary jar. With
the default config (`includeBraces: true`,
`includeAdditionalDeclarations: false`) neither branch fires, so baseline
builds work. If a future task flips either flag, vendor those two files from
the calcite-1.42.0 tag first.

`config.fmpp` is self-contained: Calcite's `default_config.fmpp` (464 lines,
root key `parser`) is pasted verbatim under the `default:` key, and only the
overrides (`package`, `class`, `implementationFiles`) live under the top-level
`parser:` key. No build-time data merge is needed.

## Build chain

`fmpp-maven-plugin 1.0` expands `templates/Parser.jj` with `config.fmpp` data
into `target/generated-sources/fmpp/Parser.jj`; `javacc-maven-plugin 2.6`
compiles that into `target/generated-sources/javacc/io/sqlmask/parser/SqlMaskParserImpl.java`
(the `3.0.3` plugin version does not exist in Maven Central; 2.6 is the Flink-proven fallback).

Public interface produced: `io.sqlmask.parser.SqlMaskParserImpl.FACTORY`
(`org.apache.calcite.sql.parser.SqlParserImplFactory`).

## Extension diff registry

Baseline = unmodified Calcite 1.42.0 codegen. Current diff: **(empty)**.

| Task | File | Change |
|---|---|---|
| Task 3 | (to be recorded) | TODO |
| Task 4 | (to be recorded) | TODO |
| Task 7 | (to be recorded) | TODO |

## Calcite upgrade runbook

1. Bump `<calcite.version>` in the root pom.
2. Re-extract the 4 vendored files from the new `calcite-core-<v>.jar` (command above);
   if any file is absent from the jar, fetch it from the matching GitHub tag.
3. Replace the block under `default:` in `config.fmpp` with the new
   `default_config.fmpp` content verbatim (keep the `parser:` override key and
   the `freemarkerLinks` block untouched).
4. Replay every diff registered in the table above onto the new vendored files.
5. Re-run the differential test suite and the three-dialect (MySQL/PostgreSQL/Trino)
   suites; extend `ParserSmokeTest` coverage for any new grammar surface before merging.
