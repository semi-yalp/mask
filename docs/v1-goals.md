# sql-mask v1 目标与范围(v1 Goals & Scope)

> 状态:草案 v1(2026-09-23)。本文档是**新仓库的奠基文档**:旧仓库(`orca/mask`)整体封存只读,
> v1 在全新仓库从内核代码拷贝起步。本文同时记录对最初设想的审查结论与既定决策。
>
> 修订:2026-09-23 策略主体维度(users/groups)移出 v1,见 F6 与决策记录。

## 1. 一句话定位

基于 Apache Calcite 的**多引擎 SQL 脱敏改写工具**:只做解析、校验、血缘分析与 SQL 改写输出,
**从不执行业务 SQL、不接入任何查询引擎做查询**。改写方式为「原始查询作为内层、最外层对
结果列调用脱敏 UDF」;输入与输出同为目标方言,不做跨引擎转写。

产品形态三件套:**纯库 jar + CLI + classloader 隔离包装**,供上层平台或查询引擎嵌入集成。

## 2. 背景与旧仓库资产盘点

旧仓库演化为「4 微服务 + 前端控制台」的平台形态,改写内核与服务平台耦合在 mask-core 单模块内
(rewrite/dialect/rowfilter 与 Spring Boot Web/CLI 混装)。**内核本身经过充分锤炼**,v1 全部继承:

| 资产 | 旧仓库位置 | v1 处置 |
| --- | --- | --- |
| Calcite 解析定制(conformance、INSERT OVERWRITE AST 节点、babel 等价性测试) | `mask-sqlparser` | 整体移植 |
| 5 方言适配(dialect adapter / type resolver / unparse / identifier policy) | `mask-core/.../dialect` | 整体移植,剥离 Spring |
| UDF 外包裹改写引擎(含 INSERT/CTAS 写语句改写、pass-through 判定、fail-closed) | `mask-core/.../rewrite` | 整体移植,剥离 Spring |
| 血缘分析 | `mask-core/.../lineage` | 整体移植 |
| 行过滤(表级静态谓词,校验前 AST 表引用替换,两轮评审加固) | `mask-core/.../rowfilter` | 整体移植 |
| YAML 配置与内嵌策略装载(metadata.yaml 的 `policies`/`columns`/`rowFilter`) | `mask-core/.../config` | 移植(裁掉 policies.yaml 主体装载器与双来源仲裁,见 F6) |
| CLI 骨架(picocli) | `mask-core/.../cli` | 移植后独立成模块 |
| TPC-DS 语料与验收骨架 | `mask-core/tpcds`、`.bench/tpcds-corpus` | 移植并扩成全量矩阵 |
| 设计文档(行过滤、多方言、策略、UDF registry 等) | `docs/superpowers/{specs,plans}` | 拷贝入新仓库 `docs/` |
| 4 微服务(core Web / policy-server / metadata / query)、审计、前端控制台 | 各模块 + `frontend/` | **不迁移**,随旧仓库封存(v2 平台化时再评估复活) |
| mask-lite(最小化抽取尝试,PG-only、无行过滤) | `mask-lite` | 不迁移(其"无 Spring 库化"经验已被内核移植吸收) |
| 元数据在线采集(`--pull-metadata`,PG/MySQL/Trino 只读采集) | `mask-core/.../introspect` | **不进 v1**(元数据只吃 YAML);列为 v1.x 首个回填候选 |

## 3. v1 功能范围

### F1 UDF 脱敏改写内核(继承)

- SELECT(含 `WITH`、集合操作、子查询、窗口、JOIN)→ 内层快照渲染 + 外层 UDF 包装;
- 血缘无法追溯的输出列**显式报错,绝不静默放行**(fail-closed 是全工具的安全立场);
- 多语句脚本逐条改写;错误码契约沿用旧仓库
  (`CONFIG_ERROR / PARSE_ERROR / VALIDATION_ERROR / UNSUPPORTED_STATEMENT / LINEAGE_UNKNOWN / REWRITE_ERROR / IO_ERROR / INTERNAL_ERROR`);
- 确定性:同样的 YAML + SQL 永远产出同样的输出。

### F2 方言矩阵:5 种(继承,已确认)

PostgreSQL / MySQL / Trino / Hive / Spark SQL。输入与输出同为所选方言,不做跨引擎转写。
每个方言一套 adapter(TypeResolver / UnparseDialect / IdentifierPolicy / 函数表)。

### F3 关键字与方言扩展能力(预期已对齐)

目标:**适配各引擎方言语法差异,尽量少报错;报错时精确可诊断,绝不静默吞语法**。

实现路线(遵循 Calcite 的现实约束——语法关键字编译在 parser 里,做不到运行时任意增删关键字表):

1. 分方言 parser 配置(parser factory + `SqlConformance` 开关,沿用现有 `SqlMaskConformance`);
2. `calcite-babel` 合并语法兜底(现有 BabelEquivalenceTest 保证等价性);
3. 方言关键字/标识符策略(引用、大小写敏感)走 `IdentifierPolicy` 配置化;
4. 解析不了的语法 fail-closed 报 `PARSE_ERROR`,带语句序号与位置——**报错是特性**:宁失败不漏保。

v1 不承诺:运行时用户自定义关键字表;引擎私有语法(如 `QUALIFY`、`LATERAL FLATTEN`)全覆盖
(按方言逐个补,进方言矩阵测试集)。

### F4 写语句改写(部分继承 + 一项新开发)

理由:复制表类语句会把源表数据带入新表,脱敏必须发生在写入之前。

| 语句 | 现状 | v1 动作 |
| --- | --- | --- |
| `INSERT INTO ... SELECT` | 已实现(包装源查询,目标表不动) | 继承 |
| `INSERT OVERWRITE [TABLE] ... [PARTITION(...)]` | AST 节点与单测已有(`SqlInsertOverwrite`) | 继承并纳入方言矩阵 |
| `CREATE TABLE ... AS SELECT`(含列清单变体) | 已实现 | 继承 |
| `CREATE [OR REPLACE] VIEW ... AS SELECT` | **缺失** | **v1 新开发** |
| Hive 多表插入 `FROM ... INSERT OVERWRITE ...` | 未实现 | v1 显式报 `UNSUPPORTED_STATEMENT`,v2 再议 |
| `UPDATE / DELETE / MERGE`、`INSERT ... VALUES` | 未实现 / fail-closed | 保持显式报错(VALUES 内无来源查询可包装) |

行过滤与写语句叠加:过滤只作用于**源查询**,永不作用于写入目标(行为与旧仓库一致)。

### F5 行过滤(继承,模式与旧仓库完全一致)

- 表级静态条件:条件内联声明在表上,凡查询该表一律注入;
- 注入方式:校验前把命中表引用替换为派生表 `(SELECT * FROM t WHERE <条件>) AS <别名>`;
- 谓词 AST 白名单(列引用/字面量/布尔/比较/算术/IS NULL/IN 常量列表),禁子查询、函数、
  CAST、动态参数、会话函数——保证无状态与确定性;
- 严格名称解析(CTE 优先、多候选显式失败)、FROM 形态白名单、一次注入、真正 AST 隔离;
- 一切无法证明可安全改写的形态显式失败(fail-closed)。

设计依据:旧仓库 `docs/superpowers/specs/2026-09-06-row-filter-design.md`(随仓库拷贝)。

### F6 策略与元数据:纯 YAML(继承,无主体)

- **单一策略来源**:`metadata.yaml` 内嵌 `policies`(策略定义)/ `columns`(列绑定)/
  `rowFilter`(行过滤),**对所有人无条件生效**——v1 不引入查询主体(用户/用户组)概念;
- Ranger 式 `policies.yaml`(主体 `users`/`groups` 维度、资源通配、`priority`)连同其装载器、
  `--user`/`--groups` 参数与"双来源唯一性仲裁"逻辑整体后置 v2,不迁移;内部策略模型的
  subject 字段保留、恒为通配 `*`,v2 加回主体时数据结构零迁移;
- v1 无在线元数据采集、无策略服务:一切配置来自 YAML 文件或调用方注入的 YAML 字符串。

**连带影响(有意取舍)**:v1 因此没有资源通配与 `priority`——列绑定需逐表逐列显式声明,
不能一条策略批量命中"所有表的 phone 列"。v2 引入主体时随 policies.yaml 一并回来。

### F7 交付形态(新开发)

1. **纯库 jar**(`mask-rewrite-core` + `mask-parser`,无 Spring 无 picocli):
   最小 API 面,形如 `MaskRewriter.fromYaml(...).rewrite(sql, dialect)`;
2. **CLI**(fat jar):`--metadata/--sql/--input/--output/--dialect`,
   结果走 stdout、诊断走 stderr,退出码 0/1/2(沿用 mask-lite 已验证的契约);
3. **classloader 隔离包装**(`mask-embedded`):
   - 目的:嵌入查询引擎(Hive/Spark 等)时与本引擎依赖隔离,避免 jar 版本冲突;
   - 手段:shade **全量 relocation**(Calcite、Guava、ANTLR 等,包名统一改前缀)+
     facade 类通过独立 `URLClassLoader` 加载,宿主只需依赖一个薄接口 + 包装 jar;
   - JVM 基线:**Java 11/17 起步**(已确认,面向 Hive 4 / Spark 3 / Trino 等新引擎;
     Calcite 1.42 可用,无降级成本)。Java 8 基线明确不做,老引擎不支持;
   - 验收:在 classpath 上预置冲突版本依赖(旧 Calcite/不同 Guava)的环境里加载并改写成功。

### F8 UDF 契约与参考实现(已确认范围)

- **契约文档**:脱敏函数名、参数顺序与类型、语义(如 `mask_phone(x, 3, 4)`)、
  NULL 处理、各引擎注册方式——改写器只负责产出调用,契约是改写器与引擎的接口;
- **参考实现:一份,Hive UDF jar**(含编译期冒烟,沿用旧仓库 `.udf-smoke` 思路);
  其余引擎(PG/MySQL 函数、Spark 函数、Trino plugin)由使用方按契约自备或后续版本补。

### F9 验收:TPC 全量语料矩阵(扩容)

「支持 TPC-DS / TPC-H」的验收口径:

- 语料:TPC-DS 99 条 + TPC-H 22 条**全量**查询;
- 矩阵:每条查询 × 5 方言;
- 每格判定:解析 → 改写 → **产物可被同方言重新解析**(round-trip)→ 与 golden 产物比对一致;
- 语义对拍(测试基建,非产品能力):借 duckdb/PG 实际执行原查询与改写产物、比对结果集
  脱敏前后关系。这只存在于 CI 测试环境,**不构成"接入查询引擎"的产品能力**,与第 1 节定位不冲突;
- 元数据:TPC-DS 已有(metadata.yaml + 5 策略),TPC-H 需新增等价物;
- 现状基线:旧仓库仅有 5 条 TPC-DS 主用例 + 预期失败用例,全量矩阵是 v1 的主要测试工程量。

## 4. 非目标(v1 明确不做)

- 执行业务 SQL、统一查询服务/受控执行面(mask-query 形态);
- Web 服务、REST API、前端控制台;
- 策略服务、实例管理、主体缓存/轮询、审计日志、指标;
- 元数据在线采集(`--pull-metadata`);
- 跨方言转写(输入 Hive 输出 Trino 之类);
- 策略主体维度:`users`/`groups` 匹配、`--user`/`--groups` 参数、Ranger 式 `policies.yaml`(见 F6);
- 按主体/会话的动态行过滤(占位符如 `${user.depts}`)、`rowFilters: [{role, condition}]`;
- `UPDATE / DELETE / MERGE`、多表插入的改写;
- SQL 注释与原始格式的逐字节保真(内层会重渲染;注释丢失列为已知限制,文档写明);
- Java 8 运行时。

## 5. 新仓库工程结构(建议)

```
sql-mask/                     # 新仓库
├── mask-parser/              # Calcite 定制:conformance、SqlInsertOverwrite、方言 parser、babel 兜底
├── mask-rewrite-core/        # 纯库:dialect / rewrite / rowfilter / lineage / config(YAML)/ 错误码
├── mask-cli/                 # picocli CLI,fat jar
├── mask-embedded/            # classloader 隔离包装 + shade relocation;宿主接入的薄 facade 接口
├── mask-udf-hive/            # Hive UDF 参考实现
├── docs/                     # 本文档、UDF 契约、从旧仓库拷贝的设计 specs
└── test-corpus/              # TPC-DS / TPC-H 全量语料 + golden + 元数据
```

模块依赖:`parser → (calcite)`;`rewrite-core → parser`;`cli / embedded / udf-hive → rewrite-core`。
`rewrite-core` 严格不依赖 Spring / picocli / JDBC 驱动(以 mask-lite 的零依赖验证为准绳)。

构建基线:Java 17、Calcite 1.42.0(随旧仓库)、JUnit 5;`mvn package` 一次性产出 CLI fat jar、
relocation 后的库 jar 与包装 jar。

## 6. 关键决策记录(2026-09-23 已确认)

| 决策 | 结论 | 理由 |
| --- | --- | --- |
| 工程落地方式 | 全新仓库,内核代码拷贝起步,旧仓库封存只读 | 彻底摆脱平台耦合;代价是丢 git 关联,以本文档资产映射表补偿 |
| 方言矩阵 | 保持 5 种(PG/MySQL/Trino/Hive/Spark) | 代码现成;验收矩阵直接铺开 |
| UDF 本体 | 契约文档 + Hive UDF 参考实现一份 | 没有参考实现验收无法闭环;全引擎实现范围失控 |
| JVM 基线 | Java 11/17,不做 Java 8 | 与现有构建一致;classloader+relocation 已隔离宿主冲突,老引擎不支持 |
| 行过滤模式 | 与旧仓库完全一致(表级静态、AST 注入、fail-closed) | 已两轮评审加固,不重新设计 |
| 策略主体维度 | **不进 v1**(2026-09-23 确认):策略对所有人无条件生效,单一 metadata.yaml 内嵌来源;policies.yaml、资源通配与 `--user/--groups` 后置 v2 | 无主体后 Ranger 式格式失去主要价值;单一来源简化装载、CLI 与验收语料 |

## 7. 风险与已知限制

1. **CREATE VIEW 改写是新开发**(内核只覆盖 INSERT/CTAS),需补 parser 识别 + 各方言 unparse
   (`CREATE OR REPLACE VIEW` 各引擎语法差异:PG/MySQL/Spark/Hive/Trino 各不相同);
2. **全量 TPC 语料是主要工程量**:99+22 条 × 5 方言,预期会暴露长尾解析/校验问题
   (方言函数、`EXCEPT/INTERSECT`、window、rollup/cube、`LIMIT/OFFSET` 方言形态),
   修复节奏按方言逐一清零;
3. **关键字扩展有天花板**:Calcite 语法关键字静态编译,引擎强私有语法仍会 `PARSE_ERROR`
   (预期内的 fail-closed,不是缺陷);
4. **relocation 完整性**:shade 重定位漏一个包(如 `com.google.common.*`、`org.apache.calcite.*`
   的资源文件、`META-INF/services`)就会在嵌入场景炸出难查的 `LinkageError`,
   需要专项验收(F7-3 的冲突 classpath 冒烟);
5. **注释/格式不保真**:内层重渲染丢弃注释与原始排版;
6. 旧仓库分支 `feature/keyword-expand` 上未合并的前端提交(5 个 commit)随仓库封存,
   不影响 v1(v1 无前端)。

## 8. v1 之后(v2 展望,非承诺)

按需复活平台能力:元数据在线采集回填 → 策略/实例服务 → 受控执行面与审计 → 控制台;
主体维度(users/groups)策略、资源通配与按主体动态行过滤;更多方言(ClickHouse/Doris/StarRocks);
UDF 参考实现补齐其余引擎。
