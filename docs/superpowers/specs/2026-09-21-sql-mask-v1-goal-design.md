# sql-mask v1 目标与范围设计

- 日期：2026-09-21
- 状态：v1 目标定稿（基于对现状仓库 `sql-mask` 的完整审查）
- 性质：**新建独立工程**的 v1 目标文档；现有 `sql-mask` 仓库代码定位为「成熟实现的参考来源」（MIT 协议，可带版权声明复用）

## 1. 目的与决策记录

当前 `sql-mask` 仓库经多轮演进后功能面臃杂（rewrite 服务、CLI、policy-server、metadata 服务、query 服务、audit、metrics、多套 YAML 并存），已偏离最初「一个改写工具」的定位。本 v1 目标文档把 v1 明确为一个**收敛、自洽、可验收**的独立工程，并为本工程后续迭代（v2 及以后）留下单一事实源。

本次确认的关键决策：

| # | 决策点 | 结论 |
|---|---|---|
| D1 | 目标引擎方言 | **PostgreSQL / MySQL / Trino + Hive / SparkSQL**（五方言） |
| D2 | TPC-DS / TPC-H 验收口径 | **官方 99 + 22 条全量通关**：解析 + 改写 + 按目标方言回解析验证，golden 锁定 |
| D3 | 交付形态 | **新建独立工程**（新 pom / 新目录），成熟代码作为参考与可复用来源 |
| D4 | YAML 策略模型 | 收敛为 **Ranger 式 `policies.yaml`**：`metadata` 只管表结构，策略与行过滤在独立文件 |

## 2. 一句话目标

> v1 交付一个基于 Apache Calcite 的**纯 SQL 改写**脱敏工具（**不做查询执行**），以「原始查询作为内层、最外层对结果列调用脱敏 UDF」的方式工作，支持 PostgreSQL / MySQL / Trino / Hive / SparkSQL 五方言（输入输出同为该方言，不跨引擎转写），提供 **CLI + fat jar + classloader 隔离嵌入包**三种使用方式；策略与表结构元数据均由 **Ranger 式 YAML** 提供；官方 **TPC-DS(99) 与 TPC-H(22) 全量查询**在各目标方言下解析、改写并回验通过，作为验收主线。

## 3. 范围

### 3.1 v1 能力清单（in scope）

1. **UDF 外层包装改写**：输出列血缘追踪 + 命中策略的输出列在最外层一次调用脱敏 UDF；原始查询内部（WHERE/JOIN/GROUP BY/ORDER BY/LIMIT 等）不做改动。
2. **五方言**：PostgreSQL / MySQL / Trino / Hive / SparkSQL 各自的语言 profile（标识符与引号、大小写语义、类型集、内置函数、输出渲染、DML 形态）。
3. **TPC 全量语料**：TPC-DS 99 条 + TPC-H 22 条官方查询全量改写通关，五方言逐条回验，golden 锁定。
4. **关键字与语法扩展能力**：编译期 codegen 关键字注册表 + 每方言开关 + 扩展接入流程文档化（见 §6）。
5. **写语句（复制表）改写**：
   - `CREATE TABLE [IF NOT EXISTS] ... AS SELECT`（现状已支持）；
   - `INSERT INTO ... SELECT`（现状已支持）；
   - **`CREATE VIEW AS SELECT`（v1 新增）**；
   - **`INSERT OVERWRITE [TABLE] ... SELECT`（v1 新增，在 Hive / Spark 方言开启）**。
6. **行过滤**：静态谓词注入（`(SELECT * FROM t WHERE <rowFilter>) AS <alias>` 形态），复用现状实现方式。
7. **CLI + fat jar**：单 jar 内置全部依赖，命令行模式运行，多语句原子性失败语义。
8. **classloader 隔离嵌入包**：宿主侧瘦接口 jar + 子优先 `URLClassLoader` 隔离实现与 Calcite 依赖（见 §9）。
9. **YAML 配置**：`metadata`（表结构）+ `policies.yaml`（Ranger 式），Ranger 式为主模型，legacy 内嵌格式退出 v1。

### 3.2 明确非目标（out of scope）

以下**不属于 v1**，v1 文档与实现均不为其预留接口、不做假设：

- **查询引擎执行**：不接任何引擎跑业务 SQL，唯一输入是 SQL 文本，输出是改写文本。改写结果的「可执行正确性」以 golden + 方言回解析为证据（见 §10 的局限说明）。
- **服务形态**：不提供 Web / REST / 内置页面；不提供 policy-server / metadata 服务 / query 服务等任何微服务。
- **审计（ES）与指标（Prometheus）**：不在 v1。
- **`--pull-metadata`（JDBC 元数据采集）**：不在 v1 关键路径，core 不依赖 JDBC 驱动；YAML 手写或由后续工具生成。
- **跨引擎转写**：输入输出的方言必须一致（`dialect` 二选一由策略/声明决定），不做 PG→Hive 这类转译。
- **主体/权限策略细化**：v1 的 `policies.yaml` 支持 Ranger 式结构与校验，但**多主体（users/groups）语义、重叠策略优先级排序**以现状实现为准，不扩展新语义（见 §7 说明）。
- **递归 CTE**：`WITH RECURSIVE` 自引用维持 fail-closed。
- **`UPDATE` / `DELETE` / 其他 DML 与 DDL**：维持 fail-closed（写语句仅限 §3.1 第 5 条列出的形态）。

### 3.3 语句支持 / 拒绝矩阵（v1 目标态）

| 语句形态 | v1 行为 |
|---|---|
| `SELECT ...` | 支持（改写或透传） |
| `WITH ... SELECT`（非递归） | 支持，CTE 保留在内层 |
| `WITH RECURSIVE` 自引用 | 拒绝（`UNSUPPORTED_STATEMENT` / `LINEAGE_UNKNOWN`，fail-closed） |
| 根级集合操作 `UNION / INTERSECT / EXCEPT`（含括号） | **v1 必须支持（新增，D2 的前置）**，方案见 §5.3 |
| 输出位置标量子查询（相关与非相关） | **v1 必须支持（新增，D2 的前置）**，方案见 §5.4 |
| `CREATE TABLE [IF NOT EXISTS] ... AS SELECT` | 支持（现状） |
| `CREATE VIEW ... AS SELECT` | **支持（v1 新增）**，对视图体脱敏 |
| `INSERT INTO ... SELECT` | 支持（现状） |
| `INSERT INTO ... VALUES`（纯字面量） | 原样透传（无可追来源）；VALUES 藏子查询 → 拒绝 |
| `INSERT OVERWRITE [TABLE] ... SELECT` | **支持（v1 新增，Hive / Spark 方言）** |
| 带 `PARTITION` 等修饰的 `INSERT OVERWRITE` 变体 | v1 维持 fail-closed（现状 parser 已拒绝 compound/cast 列等，见 §5.5） |
| `UPDATE` / `DELETE` / 其他 DDL | 拒绝（fail-closed） |

### 3.4 术语

- **改写目标列**：输出列血缘追溯到已声明列并命中策略的输出列。
- **透传**：无命中策略的语句解析后原样回吐（Calcite 规范化快照）。
- **包装**：命中策略时生成 `SELECT mask_udf(r.col, args...) FROM (<原式>) AS r`。
- **fail-closed**：无法安全追踪/不支持的结构显式报错，绝不静默放过。

## 4. 引擎与方言

### 4.1 方言 profile 维度

每个方言是一个自包含 profile（现状已抽象化，延续之），差集集中在：

| 维度 | PostgreSQL | MySQL | Trino | Hive | SparkSQL（v1 新建） |
|---|---|---|---|---|---|
| 标识符引号 | 双引号（按需） | 反引号（包装层一律加） | 双引号（按需） | 反引号 | 反引号（`spark.sql.ansi` 双引号另行处理） |
| 非引号标识符 | 折叠小写 | 不折叠、大小写不敏感匹配 | 折叠小写 | 折叠小写（列名存储保留大小写） | 折叠小写（ANSI 模式外） |
| 字符串字面量 | 单引号 | 单引号（**双引号不是标识符**，直接拒） | 单引号 | 单引号 | 单引号 |
| 写语句形态 | CTAS / INSERT INTO | CTAS / INSERT INTO | CTAS / INSERT INTO | **OVERWRITE** | **OVERWRITE** |
| 类型集 | §现状清单 | §现状清单 | §现状清单 | v1 收敛：`STRING/TINYINT..BIGINT/DOUBLE/DECIMAL(p,s)/VARCHAR(n)/DATE/TIMESTAMP/BINARY/BOOLEAN` | v1 收敛：`STRING/BINARY/BOOLEAN/TINYINT..BIGINT/DOUBLE/FLOAT/DECIMAL(p,s)/DATE/TIMESTAMP(n)/INTERVAL`（跟随类型校验清单） |

> 表格中 v1 新建的 Hive / Spark 精确规则（类型映射、内置函数库、渲染差异、关键字集）在本设计定稿后进入实现，**以 TPC 语料全量回验为收敛依据**（§10），并回填本表。

### 4.2 解析层现状与差距

- 三方言已统一走 `mask-sqlparser` 的自定义解析器（`SqlMaskParserImpl`，Babel 等价语法为基底，方言扩展开关全关，与 Babel 差分等价）。
- `SELECT TOP(n)`（SQL Server 风格）与 `INSERT OVERWRITE`（Hive/Spark 风格）**已在解析器实现**，当前开关关闭、fail-closed。v1 在对应方言**开启**相关开关存在，具体开关口：
  - Hive / Spark：开 `INSERT OVERWRITE`；
  - `TOP` 仅当某个 v1 方言确实需要时才开（v1 五方言均非 SQL Server，**默认维持关闭**；`top()` 作函数调用的拒绝边界维持）。

## 5. 改写语义

### 5.1 核心模型（现状，延续）

- 脱敏 UDF 只出现在最外层投影，每个命中策略的输出列只调用一次；
- 无命中策略时原样透传；输出表达式多来源时按规范化 `catalog.schema.table.column` 字典序稳定选取一个策略；
- 原始查询内部不改动；CTE 在血缘分析时内联为派生表，改写产物 CTE 原样保留在内层；
- 包装层与目标列命名：别名重复 / 未命名列按现状规则处理（`mask_col_N`、`_2/_3` 后缀），需要包装的重复输出列名维持 fail-closed（跨方言不一致风险点，见 §12 R7）；
- 聚合列 / 窗口输出列命中策略时按现状整列套 UDF（UDF 需要对应类型重载，超 v1 域）。

### 5.2 行过滤（复用现状注入方式）

- 表声明带 `rowFilter` 字符串字段，所有引用该表的位置替换为 `(SELECT * FROM t WHERE <rowFilter>) AS <alias>`；
- 与列脱敏叠加时行过滤在内层生效、脱敏 UDF 在外层；只做写语句源查询，不作用目标表；
- 表达式白名单（registry 构建期逐节点检查，违规 `CONFIG_ERROR`）与三值语义维持现状；已知拒绝形态（函数调用/子查询/会话函数/动态参数）维持；
- 覆盖所有基表引用位置（FROM / JOIN / 派生表 / 子查询 / CTE 主体 / 集合操作分支 / 写语句源）。

### 5.3 根级集合操作（v1 新增，D2 硬前置 —— 硬缺口 1）

现状：根级 `UNION / INTERSECT / EXCEPT` 直接 `UNSUPPORTED_STATEMENT`（CTE 内部的集合操作已支持）。TPC-DS 官方查询含根级集合操作，**v1 必须支持**。

推荐方案（决策基准）：
- 对根级集合操作整体包一层外层投影：`SELECT mask_udf(r.col, ...) AS col FROM ( <整个集合操作> ) AS r`；
- 血缘按「集合操作各分支的列位置」合并：输出第 i 列的可能来源 = 各分支第 i 列追到的来源列并集；从中选策略（沿用字典序稳定选择）；
- 输出列名取首分支列名；首分支未命名/重复列按现有命名规则补齐；集合操作的列类型与列名语义交给 Calcite 校验器统一，包装层引用 `r.<col>`；
- 分支内已有内层 ORDER BY（非法子查询形态）与 `FETCH/LIMIT` 按现状校验规则处理。

落地前先做**官方语料盘点**（REQ-P1，见 §11 M0）：列出根级集合操作实际出现的查询编号与分支形态，作为实现与验收清单。

### 5.4 输出位置标量子查询（v1 新增，D2 硬前置 —— 硬缺口 2）

现状：`containsProjectSubQuery` 检测到投影内标量子查询即 fail（`LINEAGE_UNKNOWN`，因为 `getColumnOrigins` 对 `RexSubQuery` 不上升来源）。TPC 官方查询含投影标量子查询，**v1 必须支持**。

方案方向（实现确定）：
- 对投影表达式中 `RexSubQuery`/`SqlLiteral`/`Correlate` 隔离：把子查询体的列来源**独立追踪**——
  - 子查询体内部 `SELECT` 的输出列逐列追 `RelColumnOrigin`，得到该投影列的基础列来源集合；
  - 外层引用 `(子查询) as x` 的输出列来源 = 该子查询输出列来源（相关外表引用列不计入，它们不构成「被脱敏数据」的传播来源，或按规则计入并处理）；
- 能追到基础列并命中策略 → 包装；无策略 → 透传；**仍无法追到任何基础列 → 维持 fail-closed**，且计入验收文档（若官方某查询因此失败，属于需求级缺陷，需补血缘而非放宽）。

前置：**官方语料盘点**（REQ-P2，见 §11 M0）——统计投影标量子查询的实际形态（相关 / 非相关 / 多层嵌套），决定需要支持的血缘等级。

### 5.5 写语句（复制表）

语义：「复制表」语句是绕过脱敏的通道（不脱敏则等于把明文搬进新表/视图/覆盖目标），故对**写入的数据脱敏**。

- **目标表**：目标名、目标列清单、`IF NOT EXISTS` 等修饰原样保留；目标表通常无需在 metadata 声明；
- **源查询**：加行过滤（写语句源）→ 校验 → 血缘 → 包装 → 重组进目标语句；
- `CREATE VIEW AS SELECT`：对视图体做同样处理；视图定义即脱敏后的体（视图被引擎使用即已脱敏）；
- `INSERT OVERWRITE ... PARTITION (...) / compound/cast 列`：维持现状 parser 的 fail-closed 拒绝，在 Hive / Spark 方言文档明确拒绝清单；
- `INSERT INTO ... VALUES` 纯字面量透传；VALUES 藏子查询 → 拒绝；
- `UPDATE / DELETE`：拒绝。

## 6. 关键字与语法扩展能力

**定位**：v1 的「关键字扩展」是**编译期**能力——解析器是代码生成的（`fmpp + javacc`），无法在运行期加关键字。这不是缺陷，是生成式解析器的固有限制，v1 把它做成**有文档、可操作、每方言有开关**的扩展面：

1. **关键字注册表**：`keywords` / `nonReservedKeywordsToAdd` 覆盖层（现状已具备 `TOP` / `OVERWRITE` 的接入先例）；
2. **尽量非保留字**：能作标识符继续使用就加入 `nonReservedKeywordsToAdd`（避免「加了关键字 → TPC 语料里同名标识符解析失败」）；
3. **每方言开关**：语法扩展点用 conformance 开关门控，缺省关（fail-closed），匹配目标方言时开（现状 `SqlMaskConformance` 模式延续）；
4. **文档化接入流程**：新增方言 / 新增关键字 / 调整保留字 → 改 `config.fmpp` 覆盖层 + 生成器 + 差分测试（`BabelEquivalenceTest` 式）+ 关键字回归语料；
5. **归口**：`mask-sqlparser/EXTENSIONS.md` 式的扩展差异注册表随工程迁移，Calcite 升级 runbook（vendor codegen 数据 + 重放差异）一并纳入。

**验收关系**：每个引擎的保留字集合不会与 TPC 语料中作为标识符出现的名字冲突（冲突 → 该名字加引号区分或调关键字注册表），由 §10 的方言语料回归锁定。

## 7. YAML 配置模型（收敛为 Ranger 式）

v1 唯一配置模型：

- **`metadata`（表结构）**：`metadata.tables[].{catalog, schema, name, columns[].{name, type}, rowFilter?}`；类型按目标方言 TypeResolver 校验；
- **`policies.yaml`**：Ranger 式——`policies[].{name, enabled, priority, resources[], dataMaskItems[] | rowFilterItems[]}`。dataMask 到 column 级（可 `*`），rowFilter 到 table 级；
- 主体选择器 `users` / `groups`（`*` 通配）与优先级/资源 glob 的**解析与校验**维持现状（这是 v1 的策略声明能力，不扩展新语义）；
- **校验**：策略资源必须命中至少一张已声明表/列（fail-closed），条件白名单复用行过滤 registry；
- **互斥单一来源**：legacy 内嵌 `policies` / `columns` / `rowFilter` 从 v1 模型移除（不再内嵌），两套并存问题在 v1 终结。

配套交付：TPC-DS 24 表 + TPC-H 8 表的完整表结构 YAML 与示例策略，作为语料随仓库提供（`tpch/`、`tpcds/` 目录，复用现状 `tpcds/metadata.yaml` 的字段约定）。

## 8. CLI 与 Jar

- `java -jar sql-mask.jar --metadata <yaml> --policies <policies.yaml> [--dialect postgresql|mysql|trino|hive|spark] --sql <stmt> | --input <file> [--output <file>]`；
- 多语句按顺序原子处理，任一失败整体失败、不产出部分结果（`--output` 不创建/不覆盖）；
- 退出码：`0` 成功 / `1` 处理失败（含 fail-closed 分类诊断）/ `2` 用法错误；
- fat jar 用 shade 装配；**沿用现状踩坑教训**：`META-INF/services`、`spring.factories` 等同名资源需按键并集合并，否则运行期加载失败；
- CLI 参数层尽量瘦（不引入服务化依赖），依赖集中在 core。

## 9. 嵌入（classloader 隔离）

目标：宿主（查询引擎或其他应用）集成时不因 Calcite / Jackson / SnakeYAML 等版本冲突而失败。

- **两段式设计**：
  1. `mask-embed-api`：宿主 classpath 上的**瘦接口 jar**（仅接口 + 值对象，如 `MaskEngine` / `MaskRequest` / `MaskResult`），由 **parent 类加载器**加载，保证类型同一性；
  2. `mask-embed`：实现侧由 **子优先 `URLClassLoader`** 加载（含 Calcite、解析器、SnakeYAML 等全部依赖），实现类实现 parent 加载的接口；
- 宿主调用形态：`try (MaskEngine e = MaskEngineLoader.load(config)) { MaskResult r = e.rewrite(sql, dialect); }`；支持多实例/多配置并存；
- **已知局限（如实记录，写入文档）**：部分引擎靠 SPI（`META-INF/services`）/ 插件扫描 / 反射实例化识别扩展，**纯 classloader 隔离对这类宿主可能不够**。v1 提供 **shade + relocation 兜底**（把 Calcite 等 relocation 进 `io.sqlmask.shaded.*`）作为一个可选的第二部署形态，文档说明选择矩阵；
- **冲突场景测试**（REQ-E1）：宿主 classpath 故意放入旧版本 Calcite / Jackson / SnakeYAML，验证隔离容器内 rewrite 不受影响。

## 10. 验收标准

> 无执行环境，v1 正确性证据 = 「golden 锁定 + 方言回解析 + 失败原子性 + 结构不变性抽查」。明确写明这个局限：**v1 不保证改写产物在真实引擎执行后语义逐字节正确**，仅保证在目标方言语法下可解析且包装形态符合模型。

1. **TPC 全量（主线门）**：TPC-DS 99 条 + TPC-H 22 条 × 5 方言 = 605 条，每条「解析 + 改写 + 按目标方言回解析」全绿；`tpcds/` / `tpch/` 语料 + 结果集 golden 锁定，输出确定性（重复运行逐字节一致）。
2. **解析层差分**：`SqlMaskParserImpl` vs Babel baseline 差分等价（迁移现状差分测试）；关键字回归语料（各方言保留字 ↔ TPC 标识符不冲突）。
3. **行为回归**：行过滤覆盖矩阵、写语句（CTAS / VIEW / INSERT INTO / OVERWRITE）矩阵、fail-closed 拒绝清单不回归（现在拒绝的仍拒绝，且诊断可读）。
4. **配置层**：YAML 校验器正向/负向用例（Ranger 式模型收敛）。
5. **嵌入冒烟**：REQ-E1 冲突 classpath 场景。
6. **CLI**：退出码 / 原子性 / 输出格式契约。

## 11. 任务拆分（粗粒度里程碑）

| 里程碑 | 内容 | 依赖 / 说明 |
|---|---|---|
| **M0** 盘点与骨架 | REQ-P1/P2：官方语料盘点（根级集合、投影标量子查询形态）；新建 v1 工程骨架；vendor 成熟代码（parser、rewrite、lineage、rowfilter、yaml loader）；现有全部测试搬迁并转绿 | 从现有仓库按 MIT 收入并带版权声明 |
| **M1** 五方言 profile | Hive / Spark profile 落地；方言 profile 收敛归一；`TOP`/`OVERWRITE` 开关按方言明确；关键字注册表与接入文档 | 依赖解析器 base |
| **M2** 硬缺口 1 | 根级集合操作改写（§5.3） | 依赖 M0 盘点 |
| **M3** 硬缺口 2 | 投影标量子查询血缘（§5.4） | 依赖 M0 盘点 |
| **M4** 写语句扩展 | `CREATE VIEW AS SELECT`；`INSERT OVERWRITE` 在 Hive/Spark 开启 + 拒绝清单 | |
| **M5** CLI / jar | CLI 收敛 + fat jar（含 META-INF 合并） | |
| **M6** 嵌入 | `mask-embed-api` + `mask-embed`（classloader）+ shade 兜底 + REQ-E1 | |
| **M7** TPC 全量验收 | 官方 99 + 22 × 5 方言 golden 全绿；验收门 §10.1 | 主线门 |
| **M8** 文档与样例 | dialect 差异表回填、TPC metadata YAML、样例、接入流程文档 | |

## 12. 决策日志与风险（Decision Log）

| # | 类型 | 内容 | 处置 |
|---|---|---|---|
| R1 | 硬缺口 | 根级集合操作现状为拒绝，TPC 全量通关要求支持 | §5.3 整体包外层方案，M2 |
| R2 | 硬缺口 | 投影标量子查询现状 fail-closed | §5.4 子查询体独立血缘，M3；仍不可追者计需求缺陷 |
| R3 | 方言错配 | `INSERT OVERWRITE` 是 Hive/Spark 语法，PG/MySQL/Trino 无执行对象 | v1 仅在 Hive/Spark 开该开关（D1） |
| R4 | 语义待定 | `CREATE VIEW AS SELECT` 的脱敏语义 | 定：对视图体脱敏（写进视图定义），M4 |
| R5 | 现实局限 | 关键字扩展是编译期，非运行时可配 | §6 编译期注册表 + 文档化流程，写清预期 |
| R6 | 现实局限 | 部分引擎 SPI 扫描，纯 classloader 隔离可能不够 | §9 shade + relocation 兜底 + 选择矩阵 |
| R7 | 潜在不一致 | 需要包装的重复输出列名跨方言行为不一致（PG 无法可靠区分 → fail，MySQL 反引号可区分） | 维持 fail-closed，验收文档标注 |
| R8 | 局限性（如实写） | 无执行验证，改写产物正确性以 golden + 回解析为证据 | §10 验收叙事如实表述 |
| R9 | 范围纪律 | audit / metrics / 服务形态 / pull-metadata 明确不进 v1 | §3.2，不预留接口 |
| R10 | 依赖风险 | Calcite 升级需重放 codegen 差异并重跑差分 | runbook 随工程迁移（§6.5） |

## 13. 未决 / v2 占位

（占位，待用户描述 v2 后回填。v2 说明之前，v1 不做任何面向 v2 的接口预设——D1–D4 之外的取舍一律以 v1 自身需求为准。）