# TPC-DS 全量 SQL × mask-lite 基准测试报告

> 2026-09-23（第二轮,方言缺口修复后重跑） · 语料:TPC-DS 99 条 · 模式:仅脱敏 / 仅行过滤 / 脱敏+行过滤 · 引擎:mask-lite(主) + mask-core(参照)
> 逐查询详情:[CASES.md](CASES.md) 索引 → [cases/](cases/) 每查询一页(原始 SQL、三模式改写 SQL、判定、问题、解决思路)。

## 1. TL;DR

1. **能力结论**:上一轮报告的 4 条失败(q05/q80 的 `concat()`、q72 的 `DATE + INTEGER`、q09 的标量子查询血缘)已全部修复——**mask-lite 三种模式下 99/99 条全部成功改写、零失败**(lite 与 core 的差距由此拉开:core 仍为 95/99,四个缺口原样保留,等待对齐移植)。15 条产物命中脱敏 UDF,91 条被注入行过滤谓词。
2. **语义结论**(DuckDB sf=0.01 实测对拍,修复后全量 99 条参检):行过滤产物结果集是原始结果集的真子集、基数不增;脱敏产物基数不变、非脱敏列取值分布不变、脱敏列的产物值在原始列中零出现(无泄漏)。**三模式 VIOLATION 均为 0**。
3. **修复方式**(详见 §5/§6):P1 不是"缺 concat"而是**重复注册**——删掉手工 CONCAT,改用 Calcite PG library 自带的 PG 语义 CONCAT;P3 新增 `PostgresqlTypeCoercion`,校验期把 `DATE ± 整数字面量` 归一为 `INTERVAL 'n' DAY`(只发生在分析树,产物保留用户原文);P2 血缘器支持标量子查询递归追溯(EXISTS/IN 等仍 fail-closed)。
4. **工具侧修正**:语义对拍器首步的"结果集逐行相同"改为**多重集相同即 same**——`ORDER BY ... LIMIT` 在并列名次(ROLLUP NULL 等)下的行序是非确定的,上一轮 q77 的 same→masked 翻转即此测量伪差异(其产物与上轮逐字节一致、零 UDF)。

## 2. 被测对象

| 项 | 说明 |
|---|---|
| mask-lite | 从 sql-mask 抽出的独立模块,PostgreSQL 方言,`mvn package` fat jar,CLI:`--metadata <yaml> < input.sql > output.sql` |
| mask-core | 平台版改写内核,同 YAML 格式,作为逐字节参照(**本轮未改动**,仍保留 4 个缺口) |
| 本轮代码改动 | 修复上轮问题清单 P1/P2/P3(§6):方言函数表去重、PG 算术类型 coercion、标量子查询血缘;`mvn test` 150 例全绿(净增 17) |

## 3. 基准设计

### 3.1 语料

- 来源:`.bench/tpcds-corpus`(datamindedbe/blog-tpcds-dbt-duckdb 的 99 条 TPC-DS dbt model,随机种子固定);
- 归一化:`corpus/normalize.awk` 把 `{{ source(...) }}` jinja 还原成 `tpcds.public.<表>` 三段名、删除同名包装 CTE(仅限 `select *` 透传体),得到 vanilla PostgreSQL 方言查询;
- 每条查询一个文件 `corpus/queries/qNN.sql`,共 **99 条**。

### 3.2 策略配置(三份 YAML,只换策略维度)

**脱敏列(7 列,5 种 UDF 策略)**——覆盖 TPC-DS 最敏感的 customer 维度:

| 表.列 | 策略(UDF) |
|---|---|
| customer.c_email_address | `mask_email(x)` |
| customer.c_phone | `mask_phone(x, 3, 4)` |
| customer.c_first_name / c_last_name | `mask_name(x)` |
| customer.c_customer_id | `mask_hash(x, 'sha256')` |
| customer.c_birth_country | `mask_text(x)` |
| customer_address.ca_street_name | `mask_text(x)` |

**行过滤(3 表,静态谓词)**:

| 表 | 条件 |
|---|---|
| customer | `c_birth_year >= 1930` |
| customer_address | `ca_country = 'United States'` |
| date_dim | `d_year <= 2002` |

三份配置:`config/tpcds-mask.yaml`(仅脱敏)、`config/tpcds-rowfilter.yaml`(仅行过滤)、`config/tpcds-both.yaml`(两者)。

### 3.3 矩阵与判定

```
99 查询 × 3 模式 × 2 引擎(lite / core) = 594 格
```

每格判定依次为:

1. **改写成功**(退出码 0,错误码契约见 mask-lite README);
2. **产物可解析**(round-trip:用 mask-lite 生产 PG 解析器重新解析产物);
3. **注入/命中检查**(产物含多少脱敏 UDF 调用、三条行过滤谓词出现次数);
4. **语义对拍**:在 DuckDB 内存库(sf=0.01,`tpcds.public` 三段名 + 脱敏 UDF 用宏仿真)实际执行原查询与产物:
   - 行过滤:产物行集 ⊆ 原始行集(多重集包含),基数不增;
   - 脱敏:基数不变、非脱敏列逐列取值多重集不变、脱敏列产物值 ∉ 原始列值(排除 NULL/空串)。

### 3.4 计时方法(重要)

- **冷启动墙钟**:`run-bench.sh -P 1` 串行执行,每格一次 JVM 启动 + 解析 + 改写;**并行跑批的计时不可用**(首轮 -P 8 实测均值被争抢污染,已废弃);
- **纯改写耗时**:`run-warm.sh`——三种模式加载进**同一个 JVM**,预热 3 轮后按轮次交错测量 20 轮,`System.nanoTime` 逐条计时,取每查询 p50;跨模式共享 JIT 状态,横向可比;
- 两轮报告的绝对耗时不可横比(机器负载不同),**模式间比值**是稳定信号。

## 4. 结果

### 4.1 成功矩阵与覆盖率(99 条)

| 引擎 | 模式 | 改写成功 | 失败 | 命中脱敏(产物含 UDF) | 注入行过滤 |
|---|---|---|---|---|---|
| lite | 仅脱敏 | **99** | **0** | 15 | — |
| lite | 仅行过滤 | **99** | **0** | — | 91 |
| lite | 脱敏+行过滤 | **99** | **0** | 15 | 91 |
| core | 仅脱敏 | 95 | 4 | 15 | — |
| core | 仅行过滤 | 95 | 4 | — | 88 |
| core | 脱敏+行过滤 | 95 | 4 | 15 | 88 |

- core 的失败集合仍是 `q05, q09, q72, q80`——正是 lite 本轮修复的四个缺口,与模式无关;
- 注入数 88→91:q05/q72/q80 引用 `date_dim`(q09 只碰 store_sales/reason,不涉及三张被过滤表);
- 7 条未命中脱敏的产物属预期:输出列不含 3.2 节的 7 个脱敏列(如纯 store/item 聚合查询);
- "产物与输入不同"包括规范化差异(LIMIT→FETCH、注释丢失),见 §7。

### 4.2 与 mask-core 的一致性(参照口径)

core 未改,四条失败格与上轮一致;两引擎都有产物的 285 组可比对中 **283 组逐字节一致**,2 组分歧仍是上轮已记录的 q79 `mask`/`both`(core 引用解析器私有合成名 `EXPR$2`,DuckDB Binder 拒绝执行;lite 改名 `mask_col_1` 可执行)。**q05/q09/q72/q80 的 lite 产物为新增,core 无对应产物**——core 对齐后应补一轮逐字节比对。

### 4.3 耗时

冷启动墙钟(串行,含 JVM 启动,毫秒;数据:results/summary.txt):

| 引擎 | 模式 | avg | p95 |
|---|---|---|---|
| lite | 仅脱敏 | 3061 | 3658 |
| lite | 仅行过滤 | 3130 | 3698 |
| lite | 脱敏+行过滤 | 3195 | 3804 |
| core | 仅脱敏 | 3654 | 4143 |
| core | 仅行过滤 | 3841 | 4696 |
| core | 脱敏+行过滤 | 3857 | 4410 |

绝对值由 JVM 启动主导;模式间差值(行过滤注入 +70ms 量级)与 lite 恒快于 core(~0.6s)的信号与上轮一致。

纯改写耗时(同 JVM 交错,20 轮,每查询 p50 的中位数,毫秒):

| 模式 | 中位数 | 上轮 | 说明 |
|---|---|---|---|
| 仅脱敏 | 6.54 | 10.23 | 基线:全管线(解析→行过滤(空)→校验→血缘→包装);fail-closed 负载消失后整体回落 |
| 仅行过滤 | 12.47 | 18.55 | +派生表重建/再解析、校验多一层作用域(91/99 条引用被过滤表) |
| 脱敏+行过滤 | 12.05 | 17.85 | 注入成本之上,15 条再包一层 UDF 投影,增量可忽略 |

行过滤注入仍稳定抬高改写耗时约 ×1.9,与是否叠加脱敏无关——与上轮结论一致。

### 4.4 语义对拍(DuckDB sf=0.01,引擎 lite,99 条全参检)

| 判定 | 仅脱敏 | 仅行过滤 | 脱敏+行过滤 | 含义 |
|---|---|---|---|---|
| same | 90 | 82 | 81 | 结果集多重集相同(未触策略/过滤未改变可见行;含行序不同但多重集相同) |
| masked | 7 | — | — | 基数不变、非脱敏列分布不变、脱敏列零泄漏 |
| subset | — | 11 | — | 产物行集 ⊆ 原始行集(行级明细查询) |
| both | — | — | 12 | 行过滤包含性 + 脱敏泄漏检查同时通过 |
| filtered-aggregates | — | 4 | 4 | 聚合在过滤后数据上重算,聚合值变化是行过滤的定义性语义(非缺陷) |
| VIOLATION | **0** | **0** | **0** | — |
| orig-error | 2 | 2 | 2 | q30/q90:DuckDB 侧残差(见下) |

要点:

- **零泄漏**:7 条脱敏产物的脱敏列值在原始列值中零出现(NULL/空串按契约透传);
- **零伪造**:11 条行级明细查询的产物行全部能在原始结果集中找到(多重集包含),基数不增;
- 4 条"聚合重算"(q15/q60/q61/q87)是行过滤的定义语义,非改写错误;
- 2 条 `orig-error`(q30/q90)是 DuckDB 侧残差:q30 引用的 `c_last_review_date` 不在 dsdgen 生成 schema 中,q90 的标识符 `AT` 被 DuckDB 当作关键字——与改写器无关;
- 本轮修复的 4 条查询语义判定均为 same(它们不触任何策略;q09 为解析后规范化快照原样通过);
- **判定口径修正**:上一轮"same"要求行序也相同;本轮起结果集**多重集相同即 same**(报告 §3.4 声明的口径)。`ORDER BY ... LIMIT` 在并列名次下的行序跨执行非确定——上一轮与本轮之间 q77 的 same→masked→same 翻转就是该伪差异(产物两轮逐字节一致、零 UDF),修正后消除。

### 4.5 Round-trip(产物可被生产解析器重新解析)

594 格产物中 582 格有产物(lite 3×99 + core 3×95),**全部通过** mask-lite/mask-core 各自生产 PG 解析器的重新解析,**0 失败**;12 格为 core 改写失败跳过(4 查询 × 3 模式)。数据:results/roundtrip.txt。

## 5. 问题清单(本轮:已修复 ✅)

> 逐条在对应 [cases/qNN.md](cases/) 页有完整现场(原始 SQL/产物/修复说明)。P4/P5 为上轮基建与 core 侧记录,保持原状。

| # | 查询 | 上轮现象 | 根因 | 修复 | 状态 |
|---|---|---|---|---|---|
| P1 | q05, q80 | `concat('store', s_store_id)` → VALIDATION_ERROR("No match found for function signature CONCAT") | **不是缺 concat**:Calcite PG library 自带 PG 语义 `CONCAT_FUNCTION_WITH_NULL`;mask-lite 又手工注册了同名 CONCAT,名字解析出两个重载后 `SqlUtil.lookupRoutine` 的类型优先级过滤把非固定参数候选全部筛掉,转换期重推导失败 | 删除手工重复注册,仅保留 PG library CONCAT(类注释钉住"勿再手写") | ✅ lite 99/99;core 待对齐 |
| P2 | q09 | 投影 5 个输出列由标量子查询构成 → LINEAGE_UNKNOWN(fail-closed) | Calcite 列来源元数据看不到 RexSubQuery 内部关系树,"无来源"判定可能掩盖泄漏,v1 因此整体拒绝 | `LineageAnalyzer` 对含子查询表达式手写遍历:SCALAR 子查询递归取投影列来源;EXISTS/IN/ANY 等仍 fail-closed;子查询引用脱敏列时输出列照常包装(单测钉住) | ✅ lite 99/99;core 待对齐 |
| P3 | q72 | `d1.d_date + 5` → VALIDATION_ERROR("Cannot apply '+' to <DATE> + <INTEGER>") | PG 允许 date ± integer(加/减天数),Calcite 标准规则只认 DATETIME ± INTERVAL | 新增 `PostgresqlTypeCoercion`(经 `typeCoercionFactory` 注入校验器):DATE ± 整数字面量在类型检查前归一为 `± INTERVAL 'n' DAY`;归一只在分析树,产物保留用户原文;非字面量整数、timestamp ± integer(PG 自身也拒绝)仍 fail-closed | ✅ lite 99/99;core 待对齐 |
| P4 | q66 | 孤儿 `WITH` → PARSE_ERROR(语料缺陷) | normalize.awk `/^SELECT/` 大小写敏感 | 已改 `[Ss]...` 并重新生成(上轮已修复) | ✅ |
| P5 | q79 | lite/core 产物分歧:core 引用私有合成名 `EXPR$2`,DuckDB Binder 拒绝 | core 合成列命名策略不可移植 | 建议 core 对齐 lite 的 `mask_col_N` 策略(未变,core 待办) | ⏳ core 待办 |

### 5.1 过程中的基建教训(上轮记录,继续有效)

- **坏 fat jar 假象**:中断的 package 产物会制造 `NoClassDefFoundError` 假象;发布前必须干净构建 + 产物完整性冒烟;
- **并行计时陷阱**:-P 8 跑批的冷启动计时被争抢污染一个数量级;微基准必须单实例或声明隔离;
- **grep 判定假阳性**:文本 grep 判定"注入行过滤"会把原查询中恰好同形的谓词误判;以 AST 注入计数与 DuckDB 语义对拍为准;
- **(新增)行序伪差异**:`ORDER BY ... LIMIT` 并列名次下 DuckDB 行序跨执行非确定,结果比对必须以多重集为口径——比对器已按此修正。

## 6. 本轮代码改动(mask-lite 方言缺口修复)

| 文件 | 变更 |
|---|---|
| `dialect/PostgresqlFunctions.java` | **删除手工 CONCAT 注册**(与 PG library 重复是 q05/q80 的根因);注释说明原因,防回归 |
| `dialect/PostgresqlTypeCoercion.java` | 新增:`TypeCoercionImpl` 子类,`binaryArithmeticCoercion` 里把 `DATE ± 整数字面量`(及 `整数字面量 + DATE`)改写为 `INTERVAL 'n' DAY` 字面量并登记类型;其余交给标准规则 |
| `dialect/DialectProfile.java` / `sql/SqlValidatorFactory.java` / `dialect/PostgresDialect.java` / `dialect/AbstractCalciteDialect.java` | Profile 增加 `typeCoercionFactory` 组件,经 `SqlValidator.Config.withTypeCoercionFactory` 安装 |
| `lineage/LineageAnalyzer.java` | 含子查询的输出表达式改用手写 Rex 遍历:SCALAR 子查询递归追溯(嵌套子查询、聚合链),EXISTS/IN/多列标量 → UNKNOWN(fail-closed);无子查询路径行为不变 |
| `mask-lite/README.md` | 能力说明更新(concat/日期算术/标量子查询血缘) |
| 测试 | 新增 `PostgresDialectExtrasTest`(17 例:concat 四态、date±int 正反例、标量子查询正反例/嵌套/CASE 多分支/EXISTS-IN 拒绝)+ `pgdialect.yaml` 测试元数据;`RewriteEdgeCasesTest` 的旧 fail-closed 用例改为钉新契约;`mvn test` 150 例全绿 |
| bench 工具 | `SemanticCheck` 首步判定改为多重集口径(行序非确定伪差异);`GenReport` 的 PROBLEMS 文案更新为"已修复"口径 |

## 7. 已知限制(逐条现场见 cases/)

1. **注释与排版不保真**:任何产物都是"解析后快照"——q72 里的 `-- SQL Server: DATEADD...` 注释在产物中消失;这是 Calcite 无损改写的结构性代价(v1 文档已声明);
2. **语法规范化**:`LIMIT 100` → `FETCH NEXT 100 ROWS ONLY`、`JOIN` → `INNER JOIN`、关键字大写、标识符小写折叠;语义等价,字节不等价;
3. **两段名 `schema.table` 不支持**(PG 搜索路径口径):`FROM public.customer` 在校验期报错——行过滤与脱敏口径一致;
4. **DATE ± INTEGER 仅整数字面量**:非字面量整数表达式没有 PG 合法的 int→interval 改写形态,仍 fail-closed(设计取舍,见 §5 P3);
5. **非标量子查询(EXISTS/IN/ANY)仍 fail-closed**:血缘上无法安全证明其输出值与脱敏列的关系(v1 立场:绝不静默放行);
6. **语义对拍自身的边界**:DuckDB 不认识部分 PG 形态(`orig-error` 查询);行序非确定已在比对器内以多重集口径消除。

## 8. 复现步骤

```bash
cd mask-lite && mvn package            # 构建 fat jar(测试见 mvn test)
cd bench/tpcds-mask-lite
bash run-bench.sh 1                    # 串行冷启动矩阵(594 格)+ summarize
bash run-warm.sh 20                    # 同 JVM 交错热身基准
java -cp "../../mask-lite/target/mask-lite.jar;$(cygpath -m warm/lib/duckdb_jdbc.jar);warm/classes" \
    SemanticCheck lite results/raw mask,rowfilter,both
java -cp "../../mask-lite/target/mask-lite.jar;warm/classes" GenReport results/raw report lite
```

产物:`results/matrix.tsv`、`results/summary.txt`、`results/warm-*.tsv`、`results/semantic-*.tsv`、`results/roundtrip.txt`、`report/CASES.md` + `report/cases/qNN.md`(本报告与案例页均已提交入库,可对照复核)。

## 9. 下一步建议

1. **core 对齐**:把三个修复(CONCAT 去重、date±int coercion、标量子查询血缘)移植回 mask-core,消除 4 条失败并恢复 lite/core 全量逐字节可比;
2. core 的 `mask_col_N` 合成列命名对齐(P5);
3. 若 v2 需要 `date + 非字面量整数表达式`,评估 `make_interval(days => x)` 形态的产物可执行性。
