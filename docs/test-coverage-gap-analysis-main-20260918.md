# sql-mask main 分支测试覆盖缺口分析报告

- **分析对象**：`main` 分支 `944d4a2`（fix(build): shade 合并 META-INF 同名资源），与 `origin/main` 一致
- **分析日期**：2026-09-18（临时 worktree `/tmp/mask-coverage-fN6u` 内完成，未触碰工作区分支）
- **分析方法**：逐模块通读主代码与对应测试类（6 路并行子代理），对照 README / `docs/superpowers/specs` 设计承诺，找出未覆盖的功能点、分支、边界、异常路径与配置组合
- **规模**：主代码 196 个 Java 文件，测试 131 个文件（含 2 个测试基建类）

---

## 一、结论摘要

现有测试对「三方言改写主干、策略匹配主轴、审计批量写主路径、两个 API key 过滤器、错误码契约」覆盖扎实，但仍有 **99 条缺口用例**：

| 优先级 | 数量 | 说明 |
|---|---|---|
| P0 | 10 | 主流程 / 安全承诺 / 生产存储，回归即事故 |
| P1 | 39 | 重要分支与异常路径、对外契约 |
| P2 | 50 | 防御性分支、错误消息契约、次要边界 |
| **合计** | **99** | |

按模块分布：

| 模块 | P0 | P1 | P2 | 小计 |
|---|---|---|---|---|
| mask-core · 服务与 CLI 层 | 3 | 10 | 10 | 23 |
| mask-core · 方言与改写引擎 | 2 | 4 | 8 | 14 |
| mask-core · SQL 处理/血缘/内省 | 0 | 6 | 8 | 14 |
| mask-audit | 1 | 4 | 6 | 11 |
| mask-policy | 0 | 1 | 4 | 5 |
| mask-policy-server | 2 | 6 | 4 | 12 |
| mask-metadata | 1 | 1 | 3 | 5 |
| mask-sqlparser | 1 | 2 | 2 | 5 |
| mask-lite（已废弃边缘，见 §4.9） | 0 | 5 | 5 | 10 |

**最需要优先补的 10 条 P0**（详见 §5）：写入语句「脱敏+行过滤」组合、聚合列包装形态、审计定时刷新、mask-lite/parser 各 1 条、CLI `--strict`、轮询失败缓存保全、实例模式错误码映射、`/api/audit` 门禁注册盲区、effective 指标三分支、JdbcMetaStore 连接字段 round-trip。

---

## 二、功能全景

同一仓库产出 **3 个微服务 + 1 个 CLI + 1 个轻量独立版 + 1 个自研 parser 基底**：

| 模块 | 端口 | 职责 |
|---|---|---|
| mask-core | 8080 | `/api/rewrite`（内联 YAML / instance 模式）、内置 Web 页面、CLI（改写 + `--pull-metadata` 元数据采集） |
| mask-policy-server | 8081 | 实例/策略/UDF 管理面、按主体编译 `/api/effective` 数据面、导入元数据快照 |
| mask-metadata | 8082 | 库表结构采集与存储（PG 持久化 / YAML 导入） |
| mask-audit | 库 | 审计事件异步写 Elasticsearch（批量 + 定时刷新 + 丢弃计数 + 六指标） |
| mask-policy | 库 | Ranger 式策略文件加载与匹配（主体 users/groups、资源 glob、priority） |
| mask-sqlparser | 库 | Calcite+FMPP+JavaCC 自研 parser 分叉（INSERT OVERWRITE、SELECT TOP 扩展） |
| mask-lite | 独立 | 早期 PostgreSQL 单方言轻量版；**不在根 pom 聚合内**（构建孤儿，code review 已定性待废弃） |

核心功能域：

1. **SQL 改写**：SELECT / WITH..SELECT / INSERT..SELECT / CTAS 解析→校验→血缘→最外层脱敏 UDF 包装；WITH RECURSIVE、VALUES 藏子查询、不可追踪血缘一律 fail-closed；行过滤注入（白名单算子）可叠加。
2. **策略体系**：旧版 metadata 内嵌策略 vs 新版 Ranger 式 `policies.yaml`（二选一互斥）；按查询主体（user/groups）过滤；priority 决定命中。
3. **instance 模式**：改写服务按实例名从策略服务拉编译配置，LRU 256 进程内缓存 + 30s 轮询；stale-but-available（短暂不可用用缓存）、无缓存 fail-closed；`POST /admin/cache/refresh` 紧急清缓存。
4. **元数据采集**：`--pull-metadata` / `POST /api/metadata/pull` 三引擎只读系统目录，类型降级告警，`--strict` 拒绝降级导出。
5. **审计**：改写/管理面/数据面事件 → ES（索引模板 + 批量 + flush-interval + 不重试 + FailureReporter 限频告警）。
6. **可观测**：Micrometer/Prometheus——改写指标、effective 指标、审计六指标、管理面 admin/collect 指标。
7. **CLI**：单 jar 双模式（带参数走 CLI），退出码 0/1/2 契约。

---

## 三、现有测试覆盖概览

- **mask-core**（62 个测试文件）：整体质量较高。CLI 参数互斥/退出码、方言三适配器差异、RewritePlan 三血缘状态、包装形态钉死（WHERE/ORDER BY 留内层）、行过滤注入位置与 fail-closed、PG 字节级 golden + TPC-DS 预期失败分类、PolicyApiKeyFilter（编码绕过/context-path/常量时间比较）、PolicyServiceConfigSource 的 LRU/stale/fail-closed、三内省器 mock 单测 + PG 类型映射参数化全表、MetadataYamlGenerator 6 个 golden。薄弱：轮询失败传播、写入语句组合场景、MySQL/Trino 内省 SQL 文本、`SqlNodeCopier`/`SqlValidatorFactory`/`YamlCalciteSchemaFactory` 无直接测试。
- **mask-audit**（10）：FakeEsServer 覆盖批量写/队列满丢弃/ES 失败/六指标/close 超时；弱在 flush-interval 定时刷新（完全未测）、close 排空成功路径、ES 认证分支、属性绑定。
- **mask-policy**（8）：PolicyEngine 主轴（priority/顺序/enabled/主体特异性/miss）+ 24 个 YAML 守卫用例；弱在入参防御与 glob 边界。
- **mask-policy-server**（16）：PolicyValidator/Compiler/JdbcPolicyStore CRUD/过滤器分段覆盖好；**无 Spring 上下文装配测试**、异常处理器未测、effective 指标零断言、MetadataImportController 失败路径空白。
- **mask-metadata**（19）：Importer/StructureService/CollectService/ConnectionInfo 分支矩阵覆盖好；JdbcMetaStore 的 connection 字段持久化几乎零断言、AdminMetrics 无测试。
- **mask-sqlparser**（6）：两个扩展产生式主路径与拒绝分支已盖；`SqlInsertOverwrite.unparse` 完全未触达。
- **mask-lite**（10）：splitter/loader/输出格式覆盖好；`Main` 退出码、聚合位置未知函数、行过滤键静默丢弃（安全分叉）未测。

---

## 四、缺失用例全量清单

格式：**用例名称** — 被测位置；缺失内容与风险；建议类型｜优先级。

### 4.1 mask-core · 服务与 CLI 层（23 条）

1. **refresh() 遇服务故障时的异常传播与缓存保全** — `mask-core/.../config/source/PolicyServiceConfigSource.java#refresh`（L87-97）+ `server/InstanceConfigSources.java#refreshAll`（L63-75）；现有测试只测同版本/版本升级，`CacheRefreshControllerTest` 的"轮询存活"用例用空缓存根本没进循环体——"有缓存的实例轮询失败后异常被吞、stale 缓存保留"无证明；误清缓存/异常逃逸回归无法发现｜单元｜**P0**
2. **实例模式下 policy service 返回 404/401/500 时 /api/rewrite 的错误码映射** — `server/RewriteController.java#rewrite`（L93-96），`RewriteInstanceModeTest` 的 `stubStatus` 从未设为非 200；POLICY_INSTANCE_NOT_FOUND / CONFIG_ERROR(401) / POLICY_SERVICE_UNAVAILABLE(500/拒连) → 400 + 对应 code + 失败审计，controller 层完全未测；数据面对外错误契约无回归保护｜集成｜**P0**
3. **CLI `--strict` 下类型降级导出被拦截（STRICT_DEGRADED，exit 1 且不写输出）** — `cli/SqlMaskApplication.java#executePullMetadata`（L258-263）；`strict && warnings 含 "degraded to varchar"` → exit 1、不写 `--output`，CLI 层零测试，"endsWith" 字符串契约无人锁定；反向分支（非降级 warning 仍 exit 0 写文件）也未测；安全门槛失效不可发现｜单元/集成｜**P0**
4. **CLI `--instance` 与 `--policies` 互斥拒绝** — `SqlMaskApplication.java#execute`（L188-191）；双策略来源应 exit 2，全测试目录无此组合；回归时 policies 文件会被静默忽略｜单元｜P1
5. **PGPASSWORD 环境变量回退的正路径与空白值** — `SqlMaskApplication.java#executePullMetadata`（L244-248）；只测过"两者都缺→exit 2"（依赖测试进程无 PGPASSWORD 的脆弱假设）；"--password 优先于 PGPASSWORD"与"PGPASSWORD 空白串仍拒绝"未测；凭据路由入口｜单元（需注入 env）｜P1
6. **PolicyServiceConfigSource 对 HTTP 500、坏 JSON、线程中断、null API key 的处理** — `fetchAndAssemble`（L145-151 / L152-161 / L122-129 / L109）；只覆盖 200/404/401/拒连；500、结构不符 JSON、中断标志恢复、null key 发空头的异常映射未测，可能抛未映射异常绕过 POLICY_SERVICE_UNAVAILABLE 语义｜单元｜P1
7. **EffectiveConfigAssembler 的重复表/重复列/重复绑定/非法 arguments/未知 dialect/rowFilter 透传** — `config/source/EffectiveConfigAssembler.java#assemble`（L56-58、L71-73、L91、L110-112、L34）；YAML loader 有对称守卫测试，assembler 侧 fail-closed 分支一个没有；策略服务下发的非法配置可能绕过校验直达改写引擎；`TablePayload.rowFilter` 非 null 透传也未测（测试里全 null）｜单元｜P1
8. **AuditQueryController 剩余参数校验分支与边界** — `server/AuditQueryController.java#events`（L52-86）；size=0/负数、page=-1、from 晚于 to、to 非 ISO-8601、区间恰好 7 天（应放行）、10 个参数逐一传递（mock 用 `any()` 没验证）均未测；分页/时间窗校验回归会直通 ES 查询层｜集成｜P1
9. **ApiExceptionHandler 的非法 JSON 体与兜底 500 / PolicyException 分支** — `server/ApiExceptionHandler.java`（L40-44、L46-50、L26-29）；无任何非法 JSON 用例（BAD_REQUEST 路径未测）；兜底 Exception→500 INTERNAL_ERROR（message null 用类名）未测；UI 依赖的结构化错误体在框架层异常时退化为白板 500｜集成｜P1
10. **SqlMaskServiceApplication 的 CLI/服务双模式分发与 API key 装配** — `server/SqlMaskServiceApplication.java#looksLikeCliInvocation`（L50-57）、`#main`（L42-48）、bean 装配（L70-77）；该类完全无测试：白名单命中/未知选项分叉、`SQLMASK_ADMIN_API_KEY`/`SQLMASK_DATA_API_KEY` null/blank/有值三态注入 filter bean 均未测；启动模式判错属安全链路问题｜单元+集成｜P1
11. **MetadataClient 的 versionOf 异常路径与其余状态码** — `metadataclient/MetadataClient.java`（L36-49、L86-96、L51-64）；versionOf 的 401→CONFIG_ERROR、500→METADATA_SERVICE_UNAVAILABLE、响应体损坏，fetch 的 500/损坏体、apiKey null 发空头均未测；影响元数据同步的 fail-closed 判断｜单元｜P1
12. **CLI `--pull-metadata` 的缺 `--output`、缺 `--user`、introspection 失败 exit 1** — `SqlMaskApplication.java#executePullMetadata`（L240-257）；三条退出路径 + `--engine " MySQL "` 归一化在 CLI 层未测；用法错误(2)与运行错误(1)的退出码契约可能混淆脚本化调用｜单元｜P1
13. **RewriteController 中 RuntimeException 路径的 REWRITE_ERROR 指标与审计** — `RewriteController.java`（L106-108、L121-123、`#recorded` L141-150 的 code==null 分支）；非 SqlMaskException 的意外异常→审计 errorCode 用类名 + metrics 记 REWRITE_ERROR + 500 的链路未测；意外异常的可观测性静默失效｜集成｜P1
14. **InstanceConfigSources 的 configured()/baseUrl trim/带 metrics 构造** — `server/InstanceConfigSources.java`（L44-46、L38、L35-41）；显式空白 url、null url、refreshAll 成功日志路径未直接测｜单元｜P2
15. **CLI instance 模式 `--policy-service " "` 空白回退 env、call() 退出码 1 映射** — `cli/SqlMaskRunner.java#run`（L36-43）、`SqlMaskApplication.java#call`（L162-164）、`readUtf8(null)`（L60-64）；instance 模式 runner 抛 POLICY_SERVICE_UNAVAILABLE 时整体 exit 1 + `[POLICY_SERVICE_UNAVAILABLE]` 前缀未测｜单元｜P2
16. **CliOptions 紧凑构造器校验与空白 instance 语义** — `cli/CliOptions.java`（L27-33）；`IllegalArgumentException` 路径、空白 instance 走 metadataPath 分支未测｜单元｜P2
17. **MetadataController 的 user/password 缺失校验与 includeViews/schemas 透传** — `server/MetadataController.java#pull`（L48-59）；user/password 缺失两个同型分支、spec 组装字段透传无断言｜集成｜P2
18. **CacheRefreshController 传空白 instance 触发全清** — `server/CacheRefreshController.java#refresh`（L27-29）+ `InstanceConfigSources#clear`（L54-61）；`{"instance": "  "}` 应等价全清未测｜单元｜P2
19. **PolicyApiKeyFilter"配置了空白 key 视为开放"分支** — `server/PolicyApiKeyFilter.java#requiredKey`（L74/L77/L80）；空白 key→null（放行）三分支未测｜单元｜P2
20. **主体缓存键的组顺序归一化与 user 的 URL 编码** — `PolicyServiceConfigSource.java#keyOf`（L99-102）、`#effectiveUri`（L169-179）；groups 乱序命中同一缓存项、user/groups 含空格/中文/`&` 的请求 URL 未测｜单元｜P2
21. **instance 名称含非法 URI 字符时的行为** — `PolicyServiceConfigSource.java#effectiveUri`（L178）经 `RewriteController` L94；空格/`#`/`?` → IllegalArgumentException → 500 而非结构化 400，无测试｜集成｜P2
22. **application.yml 的 policy.service.poll-interval-ms 绑定与 /actuator/health** — `InstanceConfigSources.java` L63 + `resources/application.yml`（L23-34）；属性可绑定/覆盖、health 端点未测（拼写错误静默走默认值）｜集成｜P2
23. **空白 sql（isBlank）与 /api/config/parse 空白 dialect 默认** — `RewriteController.java` L66、`ConfigController.java#parse`（L31-33）；`"sql": "   "` 报 "sql is required"、`"dialect": "  "` 默认 postgresql 未测｜集成｜P2

### 4.2 mask-core · 方言与改写引擎（14 条）

1. **写入语句源查询同时命中「脱敏策略 + 行过滤」的引擎级组合** — `rewrite/RewriteEngine.java#rewriteOne`（L226-229）；读路径组合已测（`RowFilterIntegrationTest#filterAndMaskingCompose`），写路径只分别测过 filter-only 与 mask-only，`masked=true && rowFiltered=true` 从未产生；「对写入数据脱敏」承诺的核心安全组合无护栏｜集成｜**P0**
2. **聚合输出列命中文本策略时的包装形态** — `rewrite/SqlRewriteService.java#buildWrapper`（L67-82）；`count(phone)` 绑文本策略应生成 `SELECT mask_phone(r.cnt,3,4) AS cnt FROM (...)` 且不插 CAST，无任何测试（血缘侧测过、包装侧没有；TPC-DS golden 里 `COUNT(*)` 未绑策略）；README「包装目标类型」承诺无回归保护｜集成｜**P0**
3. **PostgreSQL 包装层标识符的保留字/特殊字符引用** — `dialect/PostgresqlIdentifierPolicy.java#render`（L33-39）；RESERVED 集合与 PLAIN_IDENTIFIER 正则零测试（Trino 测过混合大小写，PG 的 golden 里没有带引号输出名）；保留字别名不加引号 → 生成非法 SQL｜单元+集成｜P1
4. **UDF 参数字面量的 Boolean / 浮点 / 不支持类型分支** — `SqlRewriteService.java#toLiteral`（L105-125）、`#renderLiteral`（L96-103）；只测过 Integer/String；Boolean→createBoolean、Double/Float→createApproxNumeric、else→CONFIG_ERROR 消息契约均未测｜单元｜P1
5. **显式 `WITH RECURSIVE` 关键字在三方言下失败** — `sql/CteExpander.java#recursiveCte`（L313-322）；隐式自引用已测，`WITH RECURSIVE` 解析路径只在 postgresql golden 里以预期失败出现，trino/mysql 未验证；mysql conformance 若意外接受将静默改写不可追踪查询｜集成｜P1
6. **`BETWEEN SYMMETRIC` 保持渲染、不被静默降级** — `dialect/MysqlUnparseDialect.java#unparseCall`（L35-42）、`TrinoUnparseDialect.java`（L36-43）；plain/NOT BETWEEN 已钉死，SYMMETRIC 走 super 分支（保留 flag、round-trip 响亮失败）无测试；javadoc 明言绝不能静默降级｜单元+冒烟｜P1
7. **未知函数出现在聚合位置的失败路径** — `dialect/UnknownFunctionTable.java#asFunction`（L79-84，类注释 L39-41 声明）；`SELECT my_agg(phone)` 应 VALIDATION_ERROR 未测；兜底表若被改成接受聚合语义会静默改变查询语义｜单元｜P2
8. **行过滤白名单的正向「全家桶」条件** — `rowfilter/RowFilterRegistry.java#ALLOWED_OPERATORS`（L51-59）；`IN ('a','b')`、OR/NOT、算术、IS NULL/IS NOT NULL 的正向分支未测（负向只有 IN-子查询）；白名单误收紧无护栏｜单元｜P2
9. **MySQL/Trino 类型解析器未覆盖分支** — `dialect/MysqlTypeResolver.java#parseColumn`（L20-63）、`TrinoTypeResolver.java`（L15-74）；MySQL 的 boolean/dec/binary/char 缺省长度/date 带参拒绝/text(10) 拒绝，Trino 的 `time with time zone`、`timestamp(3) with time zone`、decimal 缺精度带 scale 拒绝均未测（PG 侧有完整测试可作模板）｜单元｜P2
10. **`DialectProfiles.byName(null)` 与 `names()`** — `dialect/DialectProfiles.java`（L23-35）；null 方言名的错误消息与 names() 声明顺序未测｜单元｜P2
11. **空/null SQL 文本的引擎行为** — `rewrite/RewriteEngine.java#rewrite`（L126）；null/空白输入应返回空结果列表不抛异常，未测｜单元｜P2
12. **LATERAL 形态的 fail-closed 拒绝** — `rowfilter/RowFilterRewriter.java#failClosedOnUnknownFrom`（L304-316）；README 错误清单点名 LATERAL，只测过 TABLESAMPLE 与 UNNEST｜单元｜P2
13. **CTE 带列别名清单（`WITH x(a,b) AS`）与行过滤注入组合** — `RowFilterRewriter.java#rewriteWith`（L193-209）；columnList 与注入后派生表列数一致性无护栏｜单元｜P2
14. **MySQL 标识符内嵌反引号双写、大小写变体重名的包装拒绝** — `dialect/MysqlIdentifierPolicy.java#render`（L12）、`SqlRewriteService.java#ensureWrapperIsSafe`（L56-64）；两分支均无直接测试｜单元｜P2

### 4.3 mask-core · SQL 处理/血缘/内省（14 条）

1. **MySQL/Trino 内省 SQL 的真实（或近似真实）验证** — `introspect/MysqlMetadataIntrospector.java#queryTables`（L92-109）、`TrinoMetadataIntrospector.java`（L87-108）；MySQL 侧完全没有 SQL 文本断言，Trino 只验证过一个缺省谓词；`TABLE_SCHEMA = DATABASE()` vs `IN(...)` 两支、includeViews 对 TABLE_TYPE 的影响、`--schema`→IN 谓词、`includeViews=false` 只含 'BASE TABLE' 均未覆盖；两引擎缺 `PgMetadataIntrospectorRealTest` 的等价物，SQL 拼错单测全绿、打真实库才炸｜冒烟（testcontainers 或 ArgumentCaptor 补齐）｜P1
2. **异常消息中 JDBC URL 的脱敏分支** — 三个内省器的 `sanitize`（各 L155-161）；现有失败消息不含 `jdbc:` 前缀，走原样返回支路；含 URL 消息被截断分支、`message == null → "unknown error"` 未测；脱敏回归会把连接细节泄进 stderr/审计｜单元｜P1
3. **MySQL unsigned 剥离告警与 `--strict` 的不触发契约** — `introspect/MysqlMetadataIntrospector.java`（L52-60 第二支）；unsigned 告警文案 `"loses unsigned range semantics..."` 不得触发 strict（suffix 匹配是脆弱耦合），该分支本身无内省器级测试；文案改动会让 strict 静默失效或误杀｜单元｜P1
4. **`SqlNodeCopier` 对 SqlOrderBy / SqlJoin 子树的深拷贝** — `sql/SqlNodeCopier.java#copy`（L32-47）；无任何直接测试，两个显式重建分支从未执行（现有间接调用只拷过无 ORDER BY/JOIN 的普通 SqlSelect）；回归会 ClassCastException 或悄悄丢 ORDER BY｜单元｜P1
5. **CTE 体含 ORDER BY/LIMIT、CTE 在 WHERE IN/EXISTS 子查询内被引用、CTE 体为 UNION** — `CteExpander.java#expandOrderBy`（L110-118）、`#rewriteCall`（L260-282）；三条"scope 生命周期 × 操作数遍历"交点均无测试（历史上正是回归高发区）｜单元｜P1
6. **`MetadataYamlGenerator.scalar` 的转义分支** — `introspect/MetadataYamlGenerator.java#scalar`（L45-62）；6 个 golden 全是安全 token；含空格/引号/中文/控制字符/null 值的转义输出未测；转义缺陷会产出无法解析甚至注入语义的 YAML｜单元｜P1
7. **三引擎内省器 `queryCurrentDatabase` 空结果集分支与未知 kind 告警去重** — `PgMetadataIntrospector.java`（L78-79、L130-133）等；标题查询 0 行 → INTROSPECT_ERROR 未测；同表多列重复触发未知 relkind 的"只告警一次"未测｜单元｜P2
8. **`ConnectionSpec` 的参数兜底分支** — `introspect/ConnectionSpec.java`（L18-36）；host null→NPE、sslmode 空白默认 disable、schemas null→List.of()、PG 任意 sslmode 透传不校验的文档化差异均未显式断言｜单元｜P2
9. **`ColumnKey.normalize` 空白拒绝与 `ORDER` 比较器** — `metadata/ColumnKey.java`（L26-38）；ORDER 决定多来源列的字典序选择（被 `PdpMaskSelector` L39 消费），回归即改变脱敏决策但不报错｜单元｜P2
10. **`YamlCalciteSchemaFactory` 直接单测** — `metadata/YamlCalciteSchemaFactory.java#subSchema`（L34-38）、`#typeOf`（L59-75）；共享子 schema 复用、DECIMAL 无精度分支、全列 nullable 无直接断言｜单元｜P2
11. **`SqlValidatorFactory` 的 schemaPath 去重与根路径回退** — `sql/SqlValidatorFactory.java`（L102-106、L73-75）；无直接测试｜单元｜P2
12. **`LineageAnalyzer` 的跨表多来源去重** — `lineage/LineageAnalyzer.java`（L70-74）、`ColumnOrigin.equals`（L73-75）；JOIN 两侧不同表各贡献 origin、同 key 去重行为未测（null→UNKNOWN 分支不可达可接受）｜单元｜P2
13. **`SqlStatementSplitter` 剩余词法死角** — `sql/SqlStatementSplitter.java`（L129-136、L203、L186-189）；标识符末尾 E 接串、`$-...$`、带 tag 的未终止 dollar quote 未测｜单元｜P2
14. **`MetadataIntrospectors.byEngine` 的 mysql/trino 解析分支** — `introspect/MetadataIntrospectors.java`（L15-16）；一行补齐｜单元｜P2

### 4.4 mask-audit（11 条）

1. **低流量下 flush-interval-ms 到时刷新小批次** — `EsAuditRecorder.java#loop`（L129 timedOut 分支）；所有测试用 flushIntervalMs=60_000 刻意避开超时路径，"批次不足、等 flush-interval 后由定时器刷出"这一低流量主路径从未验证；定时刷新条件写错会导致低流量事件无限滞留内存、审计丢失且测试全绿｜集成（FakeEsServer + 小 interval）｜**P0**
2. **close() 在 5 秒内成功排空队列（draining 分支）** — `EsAuditRecorder.java#loop`（L130）、`#close`（L187-202）；现有 close 测试只覆盖超时丢弃；"队列有积压、close 后被排空写入且不计 SHUTDOWN"未测；停机审计不丢的契约无护栏｜集成｜P1
3. **ES 认证配置（apiKey / username+password）与 AuditProperties 绑定** — `AuditAutoConfiguration.java#auditRestClient`（L29-50）+ `AuditProperties.java`；认证两条分支从未执行，全部 setter 与 `@ConfigurationProperties` 绑定零调用零测试；认证头拼装错误只在真实带鉴权 ES 上暴露（401 全量丢审计）｜单元+冒烟（FakeEsServer 断言 Authorization 头）｜P1
4. **AuditEventJson 的 error 子对象与空集合省略分支** — `AuditEventJson.java`（L34-39、L25-28、L53）；所有测试事件 errorCode/errorMessage 均为 null，error.code/error.message 映射、groups 空省略、actor 全空省略、detail 空 map 省略未测；ES 侧告警/检索契约失效不报错｜单元｜P1
5. **recorder 集成路径下索引模板安装与自定义 indexPrefix** — `EsAuditRecorder.java`（L72、L122 ensureIfStale）；从未断言 `/_index_template` 请求，`setIndexPrefix` 无任何测试调用；模板安装漏调将长期动态映射（keyword 检索退化）｜集成｜P1
6. **AuditSearchClient 单边时间范围与响应边界** — `AuditSearchClient.java`（L78-91、L57-61、L94、L68-74）；from-only/to-only、hits.total==null 回退、_source==null 跳过、空白过滤值省略、非法 JSON→AuditSearchUnavailableException 未测｜集成｜P2
7. **失败批次"不重试"的严格断言** — `EsAuditRecorder.java#flush`（L167-172）；现有断言偏弱（隐式重试也能让计数达标），应断言失败后到新 record 之前 /_bulk 请求数不增长；spec §4 明确不重试｜集成｜P2
8. **AuditAdminHelper 成功路径 detail 异常与 errorCode 函数异常** — `AuditAdminHelper.java`（L48-54、L42）；errorCode 函数自身抛异常会替换原始异常并跳过 record（潜在缺陷，spec §5.1 契约破坏）｜单元｜P2
9. **AuditAutoConfiguration 自定义 AuditRecorder 时 Noop 不注册** — `AuditAutoConfiguration.java#noopAuditRecorder`（L76-80）；@ConditionalOnMissingBean 让位分支未测；双 recorder 会重复写审计｜单元（ApplicationContextRunner）｜P2
10. **连接拒绝（ES 进程不存在）下的 recorder 降级** — `EsAuditRecorder.java#flush`（L167 IOException catch）；现有失败用例均为 HTTP 503，真实拒连/超时路径从未演练；这是 spec 验收表里最常见的生产形态｜集成｜P2
11. **FailureReporter 跨窗口 down-since 累计** — `FailureReporter.java#recordBatchFailure`（L52-55）；第二窗口 WARN 的 down-since-ms 应基于首次失败时间累计，只断言过 total-dropped-batches｜单元｜P2

### 4.5 mask-policy（5 条）

1. **maskFor/rowFiltersFor 对空白标识符入参抛 PolicyException** — `match/PolicyEngine.java`（L34-37、L64-66，经 `PolicyNames#normalize`）；normalize 抛错分支只在 PolicyResource 构造路径被测，Engine 入口防御无测试；这是 PEP 边界契约，绕过即空白列名 fail-open 不脱敏｜单元｜P1
2. **GlobMatcher 大小写敏感与 `?` 字面量边界** — `match/GlobMatcher.java#matches`（L15-46）；`"ABC"` vs `"abc"` 不命中、`?` 仅字面匹配、空 value、pattern 后缀长于 value 未测；顺手改大小写语义会改变全部策略匹配｜单元｜P2
3. **PolicyEngine 空 Subject 与字面 `"*"` 主体/同分 item 平局** — `PolicyEngine.java`（L44-50）、`model/SubjectSelector.java#matchLevel`（L54、L57）；null Subject、主体名为 `*` 不得获得精确匹配特权（提权边界）、同 matchLevel 保留声明在前者未测｜单元｜P2
4. **PolicyYamlLoader 其余错误形态** — `store/PolicyYamlLoader.java`（L209-215、L41-44、L182-190、L152-158）；非字符串 name、`policies:` null、显式空 `users: []`、arguments 含 null 元素未测；漏网形态可能以 ClassCastException/NPE 裸抛而非路径化 PolicyException｜单元｜P2
5. **PolicyController null 请求体与 row_filter DTO 形态** — `server/PolicyController.java`（L28、L34-41）；`parse(null)`、row_filter 的 type 字符串与 itemCount 未测｜单元｜P2

### 4.6 mask-policy-server（12 条）

1. **/api/audit 门禁分支是注册盲区里的死代码（生产装配未覆盖）** — `PolicyServerApplication.java#policyApiKeyFilter`（L70-77）只注册 `/api/instances/*`、`/api/effective/*`，`PolicyApiKeyFilter.requiredKey` L79-81 的 `/api/audit` 分支单测已测但实际拦不到任何路径；且全模块无"设置 env 后经 MockMvc 验证 401"的上下文级测试（metadata 侧有）；单测给人"audit 面受保护"错觉，将来加端点或调注册会静默失去门禁｜集成（Spring 上下文 + MockMvc 401）｜**P0**
2. **EffectiveConfigController 数据面指标（sqlmask.effective.*）三分支零测试** — `web/EffectiveConfigController.java`（L52、L57-58、L59-60）+ `metrics/MetricsConfiguration.java`（L16-19）；`sqlmask.effective.pull/config_version/policies` gauge 与 compile timer 无一处断言，`metrics.failure` 分支从未执行过，gauge 强引用兜底（2098d5f 专门修过）在 policy-server 侧无回归保护｜集成（MockMvc + MeterRegistry）｜**P0**
3. **PolicyApiExceptionHandler 完全无测试** — `web/PolicyApiExceptionHandler.java`（L23-51）；handlePolicy/handleUnreadable/handleUnexpected 三个 @ExceptionHandler 无任何引用（metadata 侧同构类有 9 个用例）；非法 JSON 请求体走进完全未测路径｜单元｜P1
4. **JdbcPolicyStore 失败路径与查询分支** — `store/JdbcPolicyStore.java`；updatePolicy 目标不存在（L154-157）、deletePolicy 不存在（L191-194）、replaceUdf 名匹配但 udf 不存在（L222-224）、findPolicy 空结果、loadUdfs 多 udf 排序（L348-368）、损坏 JSONB→CONFIG_ERROR 四处（L370-378 等）、keyOf 无生成键→ISE（L496-502）均未测；JDBC 版是生产默认存储，错误码/版本回滚语义只有内存版等价物被验证｜集成（内嵌 PG）｜P1
5. **EffectiveConfigCompiler 只在单表实例上被测过** — `compile/EffectiveConfigCompiler.java`（L95-102、L46-78）；多表时策略只命中其中一张、rowFilter 保持 null、bindings 按表分组、columns 大小写变体去重未测；多实例多表是常态部署形态｜单元｜P1
6. **policy-server 无 Spring 上下文装配测试，policyStore 双分支未在容器中验证** — `PolicyServerApplication.java`（L48-58 JdbcTemplate 有→Jdbc 无→InMemory、L60-67）；metadata 有 contextLoads，policy-server 没有对应物；JdbcPolicyStore 在容器里的注入与 @Transactional 代理生效从未验证（fat jar 启动 944d4a2 刚修过，靠手工冒烟）｜冒烟｜P1
7. **MetadataImportController 的失败/边界路径** — `web/MetadataImportController.java`（L54-59、L66-89）；metadataBaseUrl 缺失、fetcher 抛异常（8082 不可达/404）全链路、导入导致已启用策略引用被删列被 `ensureEnabledPoliciesResolve` 拒绝、快照 dialect 非法、IMPORT 审计事件与指标（AdminAuditTest 独缺 IMPORT）、javadoc 声称"重复导入仍推进 config_version"未断言版本号｜集成（已有 StubFetcher 基建）｜P1
8. **两个 API key 过滤器的 blank-key 分支（语义相反，均未测）** — `PolicyApiKeyFilter.java`（L74/L77/L80）blank=开放（fail-open）vs `mask-metadata/.../config/ApiKeyFilter.java` L31 blank=全拒（fail-closed）；两侧只测过 null，`""`/`"   "`（application.yml 默认正是空串）未测；metadata 侧用 `String.equals`（非常量时间）与 policy 侧 `MessageDigest.isEqual` 的差异也无测试锁定｜单元｜P1
9. **EffectivePullAudit 只覆盖 FAILURE 且 enabled=true** — `EffectiveConfigController.java#record`（L69-78）；成功拉取（200）的 EFFECTIVE_PULL SUCCESS 事件字段、`audit.effective-pull.enabled=false` 不记事件的分支未测｜集成｜P2
10. **PolicyValidator 残余分支** — `PolicyValidator.java`（L79-80 policy 分支、L201-220 filterExpr 引用未声明列、L337-343 策略整表被删路径）｜单元｜P2
11. **Controller 级 404/400 残余分支** — `UdfController.java#list`（L57-60 未知实例 404）、`PolicyAdminController.getPolicy`（L132-138 未知 policy 400）、`mask-metadata/.../MetadataDataController.version`（L46-50 未知实例 404）｜集成｜P2
12. **PolicyService 层残余** — `PolicyService.java`（L95-98 replaceUdf 名不匹配、L114-120 deleteUdf 不存在、L122-129 effective 的 configVersion 数值未断言）｜单元｜P2

### 4.7 mask-metadata（5 条）

1. **JdbcMetaStore 连接字段/null-connection 的 JDBC round-trip 无任何断言** — `store/JdbcMetaStore.java#createInstance`（L62-76）、`connectionOf`（L43-60）；`JdbcMetaStoreTest`（L68-103）只断言 metadataVersion 和 structure，从不读回 host/port/dbUser/passwordRef/sslmode/connectTimeoutSeconds/schemas/includeViews；`connection == null` 实例（YAML 导入场景）经 PG 持久化再读回是否还原为 null 完全未测；生产 8082 用 PG 存储，`?::jsonb` 绑定出 bug 测试全绿、import→collect 链路生产才炸｜集成（现有内嵌 PG 测试补断言）｜**P0**
2. **mask-metadata 管理面指标（sqlmask.admin.requests）无端点级断言** — `web/MetadataAdminController.java`（L54、L89、L104、L121）；grep 无 admin.requests/admin.duration 引用；policy-server 有 AdminMetricsEndpointTest+AdminMetricsTest，metadata 侧 AdminMetrics 类连单元测试都没有；spec §3.2 对两服务的对称要求｜单元+集成｜P1
3. **JdbcMetaStore 的 store 级防御性行为** — `JdbcMetaStore.java`（L96-111 未知 name 静默 no-op、L149-163 未知 name 抛 EmptyResultDataAccessException 而非业务码、L50-56 损坏 schemas JSON→ISE）；均未测｜集成｜P2
4. **metadata YAML 导入的残余组合** — `web/MetadataAdminController.java#doImportYaml`（L142-150）；带 connection 的导入、重名 409、`instances.create` 成功后 `structures.replace` 失败留下"有实例无结构"半成品（无事务）的失败序列未测也未在文档声明｜集成｜P2
5. **CollectController 成功路径审计事件与 CollectService 未知引擎** — `web/CollectController.java`（L36-45 成功 COLLECT 事件 detail）、`service/CollectService.java` L55 byEngine 对无内省器引擎的行为｜集成/单元｜P2

### 4.8 mask-sqlparser（5 条）

1. **insertOverwriteUnparsesBackToSameSyntax** — `SqlInsertOverwrite.java#unparse`（L34-46）、`#getOperator`（L26-28）；8 个 InsertOverwrite 用例只断言 instanceof/kind，唯一手写的 unparse（前缀拼写、列列表分支、换行缩进）零断言、round-trip 未测；输出格式错会直接产生非法 SQL｜单元｜**P0**
2. **extensionsRejectedWithPlainConformance** — `src/main/codegen/includes/maskParserImpls.ftl`（L15-19、L64-67）；`!(conformance instanceof SqlMaskConformance)` 守卫从未触发（现有测试全部经 `SqlMaskConformance.of` 构造）；下游误配裸 conformance 时守卫被重构破坏无告警｜单元｜P1
3. **topWithParenthesizedSubquery** — `maskParserImpls.ftl` L60 + `templates/Parser.jj` L1406-1410；`TOP (子查询)` 括号表达式分支无测试（只测过 `TOP 10`/`TOP (10)` 字面量）｜单元｜P1
4. **isOverwriteAccessorContract** — `SqlInsertOverwrite.java#isOverwrite`（L30-32）；公开常量方法无任何断言｜单元｜P2
5. **differentialCorpusCoversPostgresStatements** — `BabelEquivalenceTest.java#corpus`（L31-57）+ `parserPostgresImpls.ftl`（337 行 BEGIN/COMMIT/SET 产生式）；差分语料不含任何 postgres 事务/SET 语句，上游文件升级漂移不会被差分发现｜集成｜P2

### 4.9 mask-lite（10 条，整体风险因模块已边缘化而降级）

> mask-lite 不在根 pom 聚合内（`mvn test` 从不构建它），code review（`docs/2026-09-16-code-review.md` A1）已定性为与 mask-core 大面积复制且安全语义分叉、待废弃；以下用例若确认废弃可直接随模块清理。

1. **rowFilterKeyMustNotBeSilentlyDropped** — `config/YamlConfigLoader.java#loadTables`（L83-141）；lite 不读取任何 `rowFilter` 键也无未知键校验——把 mask-core 的 metadata 喂给 lite 以为有行过滤实则没有，是 lite 唯一安全级缺口（fail-open 分叉），无测试钉死｜单元｜P1
2. **cteWithTopLevelOrderByExpandsReferencesInOrderList** — `sql/CteExpander.java#expandOrderBy`（L110-118）；`WITH t AS (...) SELECT ... ORDER BY ...` 归一化分支无测试｜单元｜P1
3. **subQueryNestedInsideExpressionIsRejectedAsUntraceable** — `lineage/LineageAnalyzer.java#containsSubQuery`（L93-105）；嵌套在 RexCall 里的子查询（`(SELECT max(id) FROM t) + 1`）未测；递归展开 bug 会把子查询来源误判 NO_ORIGIN 放行（脱敏漏放路径）｜单元｜P1
4. **mainProcessExitCodesAndStdin** — `Main.java#main`（L22-42）；三个 catch 的退出码 0/1/2 契约、stdin 管道路径零覆盖；shade fat jar 主类配置错误也只有这类冒烟能抓｜冒烟｜P1
5. **unknownFunctionInAggregatePositionFails** — `dialect/UnknownFunctionTable.java`（L40-42）+ `PostgresqlFunctions`/`CaseInsensitiveOperatorTable`；三个类零测试触达：`my_agg(phone)` 聚合拒绝、零参函数返回 VARCHAR、CONCAT 大小写不敏感｜单元/集成｜P1
6. **bigDecimalAndUnsupportedPolicyArguments** — `rewrite/SqlRewriteService.java#toLiteral`（L124-144）；BigDecimal（YAML `2.50`）、Long、不支持类型→CONFIG_ERROR 未测｜单元｜P2
7. **reservedWordColumnIsQuotedInWrapper** — `dialect/PostgresqlIdentifierPolicy.java#render`（L33-39）；RESERVED 77 词与 PLAIN_IDENTIFIER 正则无逐词断言｜单元｜P2
8. **engineErrorCarriesStatementOrdinal** — `rewrite/RewriteEngine.java`（L61-68）；"消息不带 statement 前缀→补前缀"分支触发了但未断言 `statement 1:`｜单元｜P2
9. **validationShapeMismatchFailsExplicitly（死分支组）** — `AbstractCalciteDialect.java`（L130-133、L163-167）、`ColumnOrigin#from`（L43-47）、`YamlConfigLoader.java`（L149-152、L133-136）、`PostgresqlTypeResolver.java`（L35-37）；均为防御性/不可达分支，建议补 1-2 个守卫测试或随废弃清理｜单元｜P2
10. **statementRewriteUnchangedFlag** — `RewriteEngine.java#StatementRewrite.unchanged`（L35-37）；公开方法无断言｜单元｜P2

---

## 五、P0 用例速览（建议最优先补齐）

| # | 用例 | 位置 | 类型 |
|---|---|---|---|
| 1 | 写入语句「脱敏+行过滤」组合 | `RewriteEngine#rewriteOne` L226-229 | 集成 |
| 2 | 聚合输出列命中文本策略的包装形态（不插 CAST） | `SqlRewriteService#buildWrapper` L67-82 | 集成 |
| 3 | CLI `--strict` 拦截降级导出（STRICT_DEGRADED） | `SqlMaskApplication#executePullMetadata` L258-263 | 单元/集成 |
| 4 | 轮询失败的异常传播与 stale 缓存保全 | `PolicyServiceConfigSource#refresh` L87-97 | 单元 |
| 5 | 实例模式策略服务故障的错误码映射（404/401/500） | `RewriteController#rewrite` L93-96 | 集成 |
| 6 | 审计 flush-interval 定时刷新小批次 | `EsAuditRecorder#loop` L129 | 集成 |
| 7 | `/api/audit` 门禁注册盲区 + 上下文级 401 验证 | `PolicyServerApplication` L70-77 | 集成 |
| 8 | effective 数据面指标三分支 | `EffectiveConfigController` L52-60 | 集成 |
| 9 | JdbcMetaStore connection 字段 round-trip | `JdbcMetaStore#createInstance/connectionOf` | 集成 |
| 10 | SqlInsertOverwrite.unparse round-trip | `SqlInsertOverwrite#unparse` L34-46 | 单元 |

## 六、测试基建与流程建议

1. **`PgMetadataIntrospectorRealTest` 无任何环境守卫**（无 `@Tag`/`@EnabledIf`/surefire 排除）：每次构建无条件启动 zonky 内嵌 PG，离线/受限 CI 会成为构建失败点。建议加开关并在 README 标注，或确认"默认必跑"为有意行为。
2. **mask-lite 是构建孤儿**：根 pom 不聚合、`mvn test` 不覆盖它，其测试结果不反映在 CI 里。建议要么显式排除并声明废弃，要么纳入聚合；其 P1 缺口（尤其 rowFilter 静默丢弃）若模块保留应尽快补齐。
3. **两个 API key 过滤器 blank 语义相反**（policy-server fail-open vs metadata fail-closed）且 metadata 侧用非常量时间比较：建议统一语义 + 统一 `MessageDigest.isEqual`，并各加测试锁定。
4. **policy-server 缺 Spring 上下文冒烟**：建议补 `contextLoads` + JDBC profile 上下文测试（metadata 侧已有对应物），避免 fat jar 装配问题只有手工冒烟兜底（944d4a2 刚修过启动缺陷）。
5. **死代码清理**：`DialectCapabilities.describe()` 主代码与测试零调用，建议删除而非测试。
6. P2 中大量"错误消息契约"用例（方言名、支持类型清单、CONFIG_ERROR 文案）成本极低（断言字符串），可随各自模块的 P0/P1 用例顺手补上。
