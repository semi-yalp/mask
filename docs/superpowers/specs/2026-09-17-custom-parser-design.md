# 自定义 SQL 解析器设计方案（mask-sqlparser：TOP / OVERWRITE 语法扩展）

日期：2026-09-17
状态：已评审（用户确认路线 B、基础设施先行、mask-core 落点、三方言全量切换）
前置文档：`2026-09-06-multi-dialect-design.md`（其 §9 已预告 `INSERT OVERWRITE` 作为
Spark 系方言的增量钩子，本文档为其提供解析层基础设施）

## 1. 背景与目标

现有解析管线基于 calcite-core 1.42.0 + calcite-babel（`SqlBabelParserImpl.FACTORY`）。
实测（1.42.0 jar 直跑验证）：`SELECT TOP 10` 与 `INSERT OVERWRITE TABLE` 在 Babel
下不可解析，且错误发生在语法层——任何 conformance 配置都解不了，因为这两个关键字
根本不在词表里。

目标：新建共享自定义解析器 `SqlMaskParserImpl`（包 `io.sqlmask.parser`），在
Babel 等价语法之上新增两个方言扩展：

- `SELECT TOP (n)` / `SELECT TOP n`（SQL Server 风格，映射进 `SqlSelect.fetch`）；
- `INSERT OVERWRITE [TABLE] t [(cols)] query`（Hive/Spark/Doris 风格）。

现有 mysql / postgresql / trino 三方言**全量切换**到该解析器（扩展开关全关），
现有测试套件作为差分验证。

### 1.1 已确认的决策

| 决策点 | 结论 |
|---|---|
| 范围 | 解析器基础设施先行；sqlserver/hive 完整方言 profile 另起任务 |
| 代码落点 | mask-core 体系，新建 maven 子模块 `mask-sqlparser`；mask-lite 不动 |
| 切换策略 | 三方言全量切换新解析器（开关关闭时行为须与 Babel 等价） |
| 扩展开关 | 自定义 `SqlConformance`（委托 + `allowTopN`/`allowInsertOverwrite`） |
| 语法基底获取 | 厂商化 vendored（构建时解包+patch 方案已评估未采用，见 §2） |

## 2. 方案选型记录

- **官方扩展机制是前提**：calcite-core 1.42.0 二进制 jar 内打包完整 `codegen/`
  目录（`templates/Parser.jj`、`config.fmpp`、`default_config.fmpp`、
  `includes/*.ftl`），且 `config.fmpp` 头部注释明示其用途即为客户端扩展解析器
  （自定义包名/类名/imports/新增关键字/新增产生式文件）。fork 不是无官方支持的
  暴力改造，而是走 Calcite 的扩展数据模型，仅 SELECT 中段的 TOP 挂点需要改模板。
- **语法基底获取**：
  - 方案 A（采用，厂商化）：jar 内 4 个 codegen 文件一次性解出提交进仓库，
    `EXTENSIONS.md` 记录 provenance 与 diff 清单，附再提取脚本。构建链简单，
    无 CI 环境依赖。
  - 方案 B（未采用）：构建时 `dependency:unpack` 解包 + `git apply` patch。
    diff 是唯一维护物、基底永远与依赖版本一致，但 Windows CRLF 与 CI 的 git
    可用性都是构建期风险点。
- **扩展开关**：
  - 方案 A（采用）：`SqlMaskConformance` 委托 `SqlDelegatingConformance`
    （1.42 存在性已验证），两个布尔开关由语法动作查询。Calcite 惯用做法，
    错误带精确位置，方言不开时行为可证明与基线一致。
  - 方案 B（未采用）：无条件解析、`classify` 按方言拒绝——错误信息差，
    classify 混入方言判断，非目标方言的 AST 中出现带怪标志的节点。
- **兜底宽容解析器（Druid/JSqlParser）**：只能做「识别表 → 放行/拦截」决策，
  无法等价执行掩码改写，放行即裸奔；本项目 fail-closed 已提供拦截决策。不采用。

## 3. 模块与构建架构

### 3.1 模块与文件清单

新 maven 子模块 `mask-sqlparser`（父 pom `<modules>` 登记；mask-core 增加依赖），
包名 `io.sqlmask.parser`：

```text
mask-sqlparser/
  pom.xml                                  fmpp + javacc 插件
  EXTENSIONS.md                            provenance、diff 清单、再提取 runbook
  src/main/codegen/config.fmpp             【自有】parser.package=io.sqlmask.parser、
                                           parser.class=SqlMaskParserImpl、
                                           imports += io.sqlmask.parser.SqlInsertOverwrite、
                                           keywords += TOP、OVERWRITE（并列入
                                           nonReservedKeywords）、
                                           statementParserMethods += SqlMaskInsertOverwrite()、
                                           implementationFiles = [parserImpls.ftl,
                                           maskParserImpls.ftl]
                                           （注：`+=` 表示最终生效内容含新增项；
                                           各键在双层合并下是替换还是与
                                           default_config.fmpp 拼接，由 §10.1
                                           验证项钉死，验收标准是：标准关键字
                                           清单完整保留 + TOP/OVERWRITE 存在 +
                                           两词均为非保留）
  src/main/codegen/default_config.fmpp     【vendor】calcite-core 1.42.0 jar 内解出
  src/main/codegen/templates/Parser.jj     【vendor + 3~6 行 TOP 挂点】
  src/main/codegen/includes/parserImpls.ftl        【vendor】
  src/main/codegen/includes/compoundIdentifier.ftl 【vendor】
  src/main/codegen/includes/maskParserImpls.ftl    【自有】SqlMaskInsertOverwrite()、
                                           SqlMaskTopN() 产生式
  src/main/java/io/sqlmask/parser/SqlMaskConformance.java
  src/main/java/io/sqlmask/parser/SqlInsertOverwrite.java
  src/test/java/...                        单测（见 §7）
```

### 3.2 构建链

FMPP 用自有 `config.fmpp` 展开模板生成 `Parser.jj`，`javacc-maven-plugin`
生成 `io.sqlmask.parser.SqlMaskParserImpl`（模板自带静态 `FACTORY`
`SqlParserImplFactory`，与 `SqlBabelParserImpl.FACTORY` 同形）。

已验证的机制事实（实现依赖）：

- 模板通过 FreeMarker 缺省表达式 `parser.X!default.parser.X` 读配置，
  即 `default_config.fmpp` 的内容需作为数据模型的 `default` 变量与自有
  `config.fmpp` 的 `parser` 覆盖层**双层合并**后注入 FMPP；
- 生成解析器持有 `conformance` 字段（`Parser.jj` 第 173 行）并随
  `SqlParser.config` 注入，语法动作直接可查；
- `imports` / `statementParserMethods` / `keywords` / `nonReservedKeywords`
  均为官方配置键，默认空。

插件版本目标：`com.googlecode.fmpp-maven-plugin:fmpp-maven-plugin:1.0`
（Flink 同款）、`org.codehaus.mojo:javacc-maven-plugin` 3.x；本地仓库无缓存，
构建时从 Central 拉取。

## 4. 语法扩展设计

### 4.1 扩展点落位（锚点为 1.42.0 模板实测行号）

| 扩展 | 机制 | Parser.jj 改动 |
|---|---|---|
| `INSERT OVERWRITE` | 官方 `statementParserMethods` 钩子：`SqlStmt()` 备选项首批渲染为 `LOOKAHEAD(2) stmt = <method>`（约 1170 行），先于 `SqlInsert()`（1762 行）匹配；产生式实现放自有 `maskParserImpls.ftl` | **零改动** |
| `SELECT TOP (n)` | `SqlSelect()` 产生式（1363 行）内 `SqlSelectKeywords`（1382 行）之后加挂点，`new SqlSelect(...)`（1419 行）合并 fetch | 约 3–6 行 |

### 4.2 TOP 语义

- `TOP n` 与 `TOP (n)` 均支持：非括号形式仅整数字面量；括号形式接受 Calcite
  表达式语法内的任意表达式（映射进 `SqlSelect.fetch`，语义即无 OFFSET 的
  FETCH）。
- TOP 子句与语句尾部 `LIMIT` / `FETCH` 并存 → 解析错误（fetch 槽位冲突）。
- `PERCENT` / `WITH TIES` 无法无损映射 → 明确拒绝。
- 与 `ORDER BY` 共存合法（TOP 应用序语义与 LIMIT+ORDER BY 等价）。
- **前瞻规则**：语义前瞻 `token(1)==<TOP> && token(2)∈{<LPAREN>, 整数字面量}`
  才进入 TOP 产生式，保证 `SELECT top FROM t`、`SELECT t.top ...`、别名
  `AS top` 等标识符用法不受影响。
- 已知边界：名为 `top` 的函数调用 `SELECT top(x) FROM t` 会被识别为 TOP 子句
  导致解析失败——fail-closed（解析错误，非错误掩码），写入文档安全失败清单。
- 将来 SQL Server 方言接入时，`MssqlSqlDialect` unparse `SqlSelect.fetch`
  自动输出 `TOP n`，回写无需新代码。

### 4.3 OVERWRITE 语义

- 生成 `SqlInsertOverwrite extends SqlInsert`，新增 `overwrite` 标志；
  `getKind()` 保持 `INSERT`，校验、血缘、直通判断（`classify` /
  `querySourceOf` / `isPassThroughWrite` 均按 kind==INSERT 分派，代码已核）
  自然兼容。
- 支持形态：`INSERT OVERWRITE [TABLE] t [(cols)] query`（TABLE 关键字可选，
  覆盖 Hive 必写 / Spark、Doris 可省两种形态）。
- 明确拒绝：`PARTITION (...)` 静态/动态分区说明、`INSERT OVERWRITE DIRECTORY`
  （compose 重组无法无损重现，fail-closed）。
- conformance 关闭时：`LOOKAHEAD(2)` 已提交到本分支，方法体内查询 conformance
  抛带明确消息的 `ParseException`（"INSERT OVERWRITE is not enabled for this
  dialect"），不回退到普通 INSERT 分支（其错误信息更差）。

### 4.4 关键字策略

`TOP`、`OVERWRITE` 经 `config.fmpp` 的 `keywords` 列表成为 token，同时列入
`nonReservedKeywords`：lexer 产出专用 token（`Parser.jj` 第 9137 行起的
`NonReservedKeyWord0of3` 等产生式保证其可出现在标识符位置），语法里按
LOOKAHEAD 区分子句身份与标识符身份。回归测试覆盖两词作列名、别名、表名、
函数名（边界）的解析。

### 4.5 conformance 开关

- `SqlMaskConformance` 继承 `SqlDelegatingConformance`，新增
  `allowTopN()` / `allowInsertOverwrite()`。
- `DialectProfile.parserConfig` 内的 parser conformance 换为包装实例；
  **`validatorConformance` 保持原 enum 原样传给校验器**，验证语义零变化。
- 现有三方言两个开关均为 false → 解析行为与 Babel 等价（差分验证保证）。

## 5. mask-core 集成

- 三个 adapter（`MysqlDialectAdapter` / `PostgresqlDialectAdapter` /
  `TrinoDialectAdapter`）的 `parserConfig` 换为
  `SqlParser.config().withParserFactory(SqlMaskParserImpl.FACTORY)…withConformance(包装实例)`，
  其余 parser 选项（quoting、casing、caseSensitive）不变。
- `AbstractCalciteDialectAdapter.composeWriteStatement` 增加
  `SqlInsertOverwrite` 分支：重组为
  `INSERT OVERWRITE TABLE <t> [(cols)] <wrappedQuery>`（TABLE 恒保留，Hive
  规范形态，Spark/Doris 亦接受）。当前无注册方言能产生该节点（开关全关），
  此分支为下个方言任务铺路。
- 测试侧提供一个开启开关的 profile 构造（test scope），驱动含 TOP / OVERWRITE
  的端到端管线测试（§7.3）。
- `classify` / `querySourceOf` / `isPassThroughWrite` / `mask-lite` 不动。

## 6. 错误处理

- 全部新语法失败落 `SqlParseException`（带行/列位置），沿用现有
  `SqlMaskException(PARSE_ERROR)` fail-closed 口径，不新增错误码或通道。
- 明确拒绝项给出专有消息而非泛化语法错误：`TOP … PERCENT`、`WITH TIES`、
  TOP+LIMIT 并存、`PARTITION (…)`、`DIRECTORY`、方言未启用 TOP / OVERWRITE。

## 7. 测试策略

### 7.1 mask-sqlparser 单测（JUnit 5）

- TOP：`TOP 10` / `TOP (10)` / 括号表达式、TOP+ORDER BY、fetch 槽位断言、
  `PERCENT` / `WITH TIES` / TOP+LIMIT 拒绝、开关关闭报错。
- OVERWRITE：带/不带 `TABLE`、带列清单、`PARTITION` / `DIRECTORY` 拒绝、
  开关关闭的明确报错、节点 `getKind()==INSERT` 与 overwrite 标志断言。
- 标识符回归：`top` / `overwrite` 作列名、限定列（`t.top`）、别名、表名、
  `top(x)` 函数调用边界（钉死 fail-closed 行为）。
- 基线等价抽样：同一批普通 SQL 在 `SqlBabelParserImpl` 与新解析器（开关关）
  下均解析成功，且语句 kind 与 unparse 输出一致。

### 7.2 差分验证（机器化）

收集 mask-core 现有测试资源中的 SQL 语料（golden 文件 + 测试内联语句），对每条
SQL 分别用 Babel 与新解析器（开关关）解析，断言语句 kind 树与 unparse 字符串
完全一致。差异即 fork 走样，构建期可证。

### 7.3 mask-core 全量套件 + 端到端

- 三方言切换后 mask-core 全部现有测试必须全绿——fork 未破坏基线的**主证据**。
- 测试专用 profile（开关全开）跑完整管线：`INSERT OVERWRITE TABLE t SELECT …`
  与 `SELECT TOP n …` 经改写→重组后，头部还原为 `INSERT OVERWRITE TABLE`、
  内层包裹正确、产物可被新解析器 round-trip 再解析；TOP 在输出侧按当前方言
  渲染为 LIMIT 形态（TOP 回写属后续 mssql 方言任务）。

## 8. 升级与维护

`EXTENSIONS.md` 固化：

1. provenance：基文件来自 calcite-core 1.42.0 二进制 jar 的 `codegen/` 目录
   （unzip 命令原样记录）；
2. diff 清单：`Parser.jj` 仅 `SqlSelect()` 挂点一处（3–6 行），其余扩展全部
   在自有 `config.fmpp` / `maskParserImpls.ftl`；
3. Calcite 升级 runbook：解出新版 codegen → 重放挂点 diff → 全量差分
   （§7.2）+ 三方言套件全绿后合入。

## 9. 明确不做（YAGNI）

- sqlserver / hive / spark / starrocks 完整方言 profile（本任务只交付解析层
  基础设施与开关）；
- `PARTITION (…)` 静态/动态分区说明、`OVERWRITE DIRECTORY`；
- `PERCENT` / `WITH TIES`；
- mask-lite 的同步改造；
- 兜底宽容解析器（Druid / JSqlParser）。

## 10. 实现期验证项（每项需有对应产出或测试）

1. **FMPP 双层配置合并的 maven 接法**：模板的 `default` 变量注入方式（候选：
   exec-maven-plugin/antrun 调 fmpp CLI 注入 `default_config.fmpp`；或验证
   fmpp-maven-plugin 的 cfgFile 机制能否承载双层合并）。产出：可复现的构建
   配置，写回本节。
2. **插件版本与构建正确性**：fmpp/javacc 插件版本选定；生成类可编译且 §7.2
   差分全绿即为正确性证明。
3. **`SqlValidatorImpl` 对 `SqlInsert` 子类的分派**：预期按 kind 分派 +
   `(SqlInsert)` 向下转型（子类安全）；若存在对具体类的严格检查，回退方案为
   validate 输入树中替换为普通 `SqlInsert`、compose 前回填标志。产出：端到端
   管线测试（§7.3）通过。
4. **TOP 前瞻边界**：`top(x)` 函数调用等边界用例钉死 fail-closed 行为，
   记入模块文档安全失败清单。
5. **差分语料收集方式**：mask-core 测试资源 SQL 的收集实现（清单文件或资源
   扫描），保证后续新增语料自动纳入差分。
