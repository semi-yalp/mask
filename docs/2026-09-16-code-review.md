# 代码评审报告：现存问题清单（2026-09-16）

> **修复记录（同日更新）**：高危项 H1–H7 已全部修复并带回归测试，见文末「八、修复记录」；其余中低危项仍未处理。

范围：主工作树全部四个模块（mask-core / mask-lite / mask-metadata / mask-policy）的主代码与测试。
基线：`mvn test` 全量通过（exit 0），下列问题均存在于绿色基线之上，未被现有测试覆盖。
方法：逐文件通读 + 关键结论抽查验证（PolicyApiKeyFilter、RowFilterRewriter、LineageAnalyzer、ConnectionSpec、MysqlSchemaPathPinningTest 均已复核原文）。

---

## 一、结论摘要

当前项目功能面已经相当完整，但存在一批**静默 fail-open**（配置了脱敏/行过滤却被绕过且不报错）与**鉴权/加密实现缺陷**，这类问题对脱敏工具是致命的：

| 级别 | 数量 | 代表问题 |
|---|---|---|
| 高危 | 7 | MySQL 两段名/大小写变体绕过行过滤、鉴权 Filter 可被 URL 编码绕过、Trino `sslmode=require` 实际不启用 TLS、主体选择器归一化不对称导致掩码静默失效 |
| 中危 | 14 | 包装层 `EXPR$N` 派生列名生成必然失败的 SQL、策略服务写路径竞态、持锁做同步 HTTP、数据面非原子读、SSRF/环境变量外带、`--password` 明文进 argv |
| 低危 | 若干 | 异常面、性能、代码卫生、方言层复制粘贴 |
| 架构 | 4 类 | mask-lite 与 mask-core 大面积复制且行为已分裂、策略数据面链路未接线、mask-policy 引入 Web 栈 |

**最优先修复**：H1 + H2（同一修复面：行过滤表的方言感知解析）、H4（鉴权 Filter 口径）、H5（Trino SSL）、H7（主体归一化）。

---

## 二、高危（H1–H7）

### H1. MySQL 两段表名 `db.table` 完全绕过行过滤注入
- 位置：`mask-core/src/main/java/io/sqlmask/rowfilter/RowFilterRewriter.java:429-431`
- 现状：`resolveTableReference` 只处理 1 段与 3 段名，两段名直接原样返回、不注入过滤条件。代码注释的前提（"留给校验器报错"）已失效——`AbstractCalciteDialectAdapter.java:264-276` 专为 MySQL 生成裸 `[catalog]` 搜索路径，**让两段名能通过校验**，且被 `MysqlSchemaPathPinningTest.twoPartNameResolvesUnderDeclaredCatalog` 钉定为预期行为。
- 后果：`SELECT * FROM app.customer`（MySQL 最自然的写法）在配置了行过滤的表上：校验通过、列脱敏正常，但**行级过滤被静默跳过**，越权行照常返回。
- 修复：对 `caseSensitiveNameMatching=false` 的方言在 `resolveTableReference` 解析两段名（`schema.table` 遍历声明表匹配），解析不确定时 fail-closed 拒绝语句。

### H2. MySQL 大小写变体表引用绕过行过滤注入
- 位置：`mask-core/src/main/java/io/sqlmask/rowfilter/RowFilterRewriter.java:456-458`（`nameMatches` 精确大小写比较），`subtreeMentionsTable`（306-336 行）同源
- 现状：MySQL 方言是 `Casing.UNCHANGED + caseSensitive(false)`（`MysqlDialectAdapter.java:35-37`）。声明 `name: customer`、查询 `FROM Customer` 时 `nameMatches("Customer","customer")` 为 false → 不注入；而校验器大小写不敏感，语句照样通过。掩码路径不受影响（走校验器），漏的是行过滤。
- 修复：`nameMatches` 感知方言的 `caseSensitiveNameMatching`；或把大小写不一致的命中归入"不确定"而拒绝。

### H3. 血缘子查询守卫只看根投影，集合操作分支中的标量子查询按"无来源"放行
- 位置：`mask-core/src/main/java/io/sqlmask/lineage/LineageAnalyzer.java:53-57, 77-91`
- 现状：`containsSubQuery` 守卫只在 `findRootProject` 能沿 `Sort → Filter → Project` 链找到根投影时生效，遇到 `Union/Aggregate/Join/TableScan` 即跳过。Calcite 元数据层（`RelMdColumnOrigins`）对 `RexSubQuery` 内的关系子树不访问、返回**空集合而非 null**，空集合被归类为 `NO_ORIGIN` → 原样透传。类注释（32-35 行）已自认此风险，但守卫没覆盖住。
- 后果：`SELECT (SELECT max(ssn) FROM secure) FROM t1 UNION SELECT a FROM t2` —— 子查询藏在 UNION 分支投影里，该输出列**不经任何脱敏原样返回**；若另一分支有真实来源，还会以另一分支的策略伪装成"已脱敏"。
- 修复：对整棵 rel 树做 `RexSubQuery` 检测（扫所有 Project 节点），或在 `NO_ORIGIN` 判定前要求全树无子查询。

### H4. PolicyApiKeyFilter 路径匹配可被 URL 编码绕过，context-path 部署下鉴权整体失效
- 位置：`mask-core/src/main/java/io/sqlmask/server/PolicyApiKeyFilter.java:34, 50-58`
- 现状：用 `request.getRequestURI()`（**未解码、含 context path**）做 `startsWith` 前缀判断，而容器的 Filter URL pattern 按解码后路径匹配，两边口径不一致：
  - 绕过 A：请求 `/api/%69nstances/pg_prod`（`%69` = `i`）→ 容器命中 filter 映射并调用它，但原始 URI 不满足 `startsWith("/api/instances")` → 放行；Spring MVC 按解码路径正常路由到 `PolicyAdminController`，**无 key 即可增删策略**。
  - 绕过 B：部署在 `server.servlet.context-path=/app` 下时，`getRequestURI()` 返回 `/app/api/instances/...`，前缀永假 → 该面 key 校验全部失效。
- 修复：改用 `request.getServletPath()`（已去 context path、已解码），做段级匹配（`equals("/api/instances") || startsWith("/api/instances/")`）。

### H5. Trino `sslmode=require` 实际不启用 TLS（安全开关静默失效）
- 位置：`mask-core/src/main/java/io/sqlmask/introspect/ConnectionSpec.java:71-79`
- 现状：`require` 分支生成的 URL 不追加任何参数（非 require 才加 `?SSL=false`），而 trino-jdbc 446 的 `SSL` 属性**默认 false**——"要求加密"与"disable"生成的 URL 对驱动完全等价。`ConnectionSpecTest.java:75-77` 还把这个错误期望断言成了契约。README 声称"Trino 真实启用密码认证时须加 `--sslmode require`（经 TLS 发送）"与实现不符：require 模式附带的密码实际走明文 HTTP。
- 修复：require 分支生成 `?SSL=true`（需要校验再加 `SSLVerification=CA` 等），同步修正测试。

### H6. `/api/metadata/pull` 完全无鉴权，构成 SSRF / 内网探测 / 任意凭证连库面
- 位置：`mask-core/src/main/java/io/sqlmask/server/MetadataController.java:32-64`；`SqlMaskServiceApplication.java:92-98`（Filter 只注册了 `/api/instances/*` 与 `/api/effective/*`）
- 现状：该端点接受任意 host/port/database/user/password 并从服务端发起真实 JDBC 连接，无 Spring Security、无网段限制。能访问该服务的任何人都可以探测内网端口、以提供的凭证连接任意数据库。`/api/rewrite`、`/api/config/parse` 同样裸奔。
- 修复：把 `/api/metadata/**` 纳入 admin key 保护；对 host 做私网/链路本地地址段拦截。

### H7. 主体选择器与 Subject 归一化不对称，掩码策略可静默失效
- 位置：`mask-policy/src/main/java/io/sqlmask/policy/model/SubjectSelector.java:23-38`、`mask-policy/src/main/java/io/sqlmask/policy/store/PolicyYamlLoader.java:192-207`、`mask-core/src/main/java/io/sqlmask/server/PolicyAdminController.java:145-146`，对照 `Subject.java:20-28`
- 现状：`Subject.of` 做 trim，而 selector 三个入口（YAML `stringSet`、Admin DTO、JdbcPolicyStore 反序列化）都**原样**存入。YAML 写 `users: ["alice "]`（尾空格，能通过 non-blank 校验）时 `users.contains("alice")` 永远 false，脱敏策略静默不生效。主体比较全程大小写敏感，与资源名 fold-lower 约定不一致，`Alice`/`alice` 同样静默失配。资源侧有 `PolicyNames.normalize` + 悬空资源拒绝，主体侧没有任何加载期校验。
- 修复：selector 构造期统一 trim + 归一化大小写（或显式拒绝含前后空白的主体名），与 `Subject.of` 规则对称。

---

## 三、中危（M1–M14）

### M1. 包装层用 Calcite 派生列名（`EXPR$N`）引用内层查询，混入未命名计算列即产出必然失败的 SQL
- 位置：`mask-core/src/main/java/io/sqlmask/rewrite/SqlRewriteService.java:67-82`
- `outputName` 取自校验行类型，Calcite 对未命名表达式派生 `EXPR$0` 风格名字；内层查询却是用户原文，目标引擎给这列的实际名字是 PG `?column?`、Trino `_col0`。只要语句混有任何一个未命名计算列（`SELECT phone, 1+1 FROM customer` 命中脱敏需要包装），输出 `r."EXPR$1"` 在真实引擎必然"列不存在"。现有测试与 golden 全部用了别名，该路径零覆盖。不泄漏数据（执行报错），但静默产出非法 SQL。修复：包装时重建内层投影强制别名，或对派生名 fail-closed 拒绝。

### M2. `composeWriteStatement` 丢弃 INSERT 的 UPSERT/OVERWRITE 关键字，静默改变写语义
- 位置：`mask-core/src/main/java/io/sqlmask/dialect/AbstractCalciteDialectAdapter.java:193-219`
- Calcite 1.42 的 `SqlInsert` 带 `keywords` 字段（`isUpsert()`、`OVERWRITE`），重组时硬编码 `"INSERT INTO"` —— UPSERT 被降级为 INSERT，行该更新的变成插入。CTAS 侧有 `checkCreateTableVariant` 防同类问题，INSERT 侧没有。修复：`insert.getKeywords()` 非空即拒绝或忠实渲染。

### M3. 策略服务写路径 check-then-act 竞态，overlap/dangling 守卫可被并发绕过
- 位置：`mask-core/src/main/java/io/sqlmask/policyserver/PolicyService.java:64-102`
- `InMemoryPolicyStore` 每方法各自 `synchronized`，但 service 层"读状态验证"与"提交写入"是两个独立锁窗口：并发 `createPolicy` 各自验证时都看不到对方 → 存入互相 overlap 的同表同列策略，运行期触发 `EffectiveConfigCompiler` 的防御异常（500）或同列歧义掩码。`replaceUdf`/`updateInstanceTables` 同理。修复：service 层对写路径整体加锁，或验证下沉进 store 的同一同步块。

### M4. PolicyServiceConfigSource 持锁做同步 HTTP + refresh 单点失败 + 整条链路未接线
- 位置：`mask-core/src/main/java/io/sqlmask/config/source/PolicyServiceConfigSource.java:57-136`
- 三个问题：(a) `load()/refresh()` 全 `synchronized`，锁内执行同步 HTTP（connect 5s + request 10s），策略服务变慢时所有主体加载串行排队，`refresh` 最坏持锁 256×10s；(b) `refresh()` 循环中任一 subject 拉取失败异常直接冒泡，剩余主体全部不刷新，与类注释宣称的 stale-but-available 契约相悖；(c) 该类与 `ConfigSource` 接口在 main 无任何生产消费者，`refresh()` 无调用者——一旦接线，缓存版本永不过期，需补轮询调度。修复：锁内只查/写缓存、HTTP 移到锁外（per-key 去重）；refresh 逐 subject 容错；接线时补调度。

### M5. JdbcPolicyStore 没有任何生产装配路径，服务重启即丢全部策略
- 位置：`mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java:36-37, 71-74`
- 唯一生产 `PolicyStore` bean 是 `InMemoryPolicyStore`，且 `DataSourceAutoConfiguration` 被排除；类注释声称 PostgreSQL-backed，实际没有任何 profile/配置能启用它（仅 IT 引用）。修复：`@ConditionalOnProperty` 条件装配 + 自建 DataSource，或文档明确当前仅内存实现。

### M6. 异常处理器泄漏内部信息、状态码一刀切 400、完全不打日志
- 位置：`mask-core/src/main/java/io/sqlmask/server/ApiExceptionHandler.java:20-41`；`mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataApiExceptionHandler.java:36-46`
- (a) 500 分支把 `e.getMessage()` 原样返回（JDBC 异常含 SQL 语句、主机、约束细节），Jackson 分支回显请求体片段；(b) 三个 handler 均无日志，服务端不留堆栈，排障全靠猜；(c) core 侧所有 `SqlMaskException` 一律 400——`*_SERVICE_UNAVAILABLE`（应 503）、`*_NOT_FOUND`（应 404）无法区分，而 mask-metadata 侧已做 per-code 分派，两套标准并存。修复：500 记日志返回通用文案；按 code 分派状态码（对齐 mask-metadata）。

### M7. mask-metadata 数据面 version 与表结构非原子读
- 位置：`mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataDataController.java:40-42`
- `instances.get(name)` 与 `structures.load(name)` 两次独立查询无事务包裹，中间发生 collect/import 提交时客户端拿到旧 `metadataVersion` 配新表结构（或相反）。策略服务正是靠 version 判断缓存失效，错配会长期使用与版本号不符的结构。`MetadataAdminController.detail`（107-110 行）同样两段式。修复：store 层提供单事务/单 SQL 的合并读。

### M8. mask-metadata importYaml 非原子，失败残留"空壳实例"
- 位置：`mask-metadata/src/main/java/io/sqlmask/metaserver/web/MetadataAdminController.java:93-95`
- 先 `instances.create` 再 `structures.replace`，后者类型校验或 SQL 失败时实例已入库但无结构；重试 import 得到 409 `METADATA_INSTANCE_EXISTS`，必须先手动 DELETE。修复：组合进同一事务或失败补偿删除。

### M9. mask-metadata 采集端点 = SSRF + 环境变量凭据外带通道；管理面与数据面共用同一把 key
- 位置：`mask-metadata/src/main/java/io/sqlmask/metaserver/service/CollectService.java:43-47`、`EnvCredentialResolver.java:16`、`application.yml:16-17`
- host/port 完全由请求方控制、无网段黑名单（可指向 `169.254.169.254`、内网任意 IP:port 做端口扫描/指纹）；`passwordRef` 可引用**任意**环境变量（如 `AWS_SECRET_ACCESS_KEY`），解析出的真实密码随认证握手发往请求方指定的"数据库"，可被伪数据库离线破解。且全服务只有一个 `metadata.api-key`，数据面消费者（部署面更广）持有的 key 等于管理 key，可直接触发上述采集。修复：私网/链路本地地址段黑名单；`passwordRef` 限定 `SQLMASK_` 前缀白名单；admin/data 分 key（按 path 前缀分派校验）。

### M10. mask-metadata 竞态与错误码不一致（并发重复创建 → 500 而非 409）
- 位置：`mask-metadata/src/main/java/io/sqlmask/metaserver/service/MetadataService.java:26-30`、`store/JdbcMetaStore.java:121,149`
- check-then-act 竞态下 UNIQUE 约束兜底抛 `DuplicateKeyException` 落入 `handleUnexpected` → 500 + SQL 泄漏（单线程同场景是 409）；`queryForObject` 把并发 DELETE 后的 TOCTOU 变成 500；测试用 `InMemoryMetaStore` 静默返回空结构，掩盖了与 JDBC 实现的行为差异。修复：捕获 `DuplicateKeyException` 转译 409；`query` 判空抛 `METADATA_INSTANCE_NOT_FOUND`。

### M11. `--password` 明文进入 argv
- 位置：`mask-core/src/main/java/io/sqlmask/cli/SqlMaskApplication.java:79-81, 224-228`
- 命令行参数对同机所有用户可见（进程列表）且进入 shell history。附带：Trino + `sslmode=disable` 时驱动根本不发送密码，却仍强制要求非空（用户被迫编造假密码）；`PGPASSWORD` 变量名对 mysql/trino 场景误导。修复：只保留环境变量（改语义中性的 `SQLMASK_PASSWORD`）/密码文件/stdin；Trino+disable 跳过密码要求。

### M12. Trino 拉取无任何超时；MySQL/PG 有、Trino 缺
- 位置：`mask-core/src/main/java/io/sqlmask/introspect/ConnectionSpec.java:71-79`
- trino-jdbc 446 无 connectTimeout 属性，于是 connect/socket 超时双双缺失，CLI 或 Web 调用可无限挂起。修复：`DriverManager.setLoginTimeout` 或整体超时包装。

### M13. HTTP 客户端无重试、instance 名未 URL 编码、apiKey 缺失时发空串头
- 位置：`mask-core/src/main/java/io/sqlmask/metadataclient/MetadataClient.java:37-84`、`config/source/PolicyServiceConfigSource.java:99-147`
- 单次 `IOException` 即抛 `*_SERVICE_UNAVAILABLE`（方向正确但无退避重试，轮询场景对瞬时抖动过敏）；`user/groups` 做了 `URLEncoder.encode` 唯独路径段 `instanceName` 直拼，含空格/中文时抛**未映射的** `IllegalArgumentException` → 500；apiKey 未配置时发送空字符串头让服务端 401 而非本地报配置错误；`HttpMetadataStructureFetcher` 每次调用 new 一个 `HttpClient`（连接池不复用）。修复：幂等 GET 加 1-2 次指数退避；统一编码 + 构造期校验 instance 名；本地 fail-fast。

### M14. Web 拉取硬编码 `sslmode="disable"`，请求体无 sslmode 字段
- 位置：`mask-core/src/main/java/io/sqlmask/server/MetadataController.java:59`
- Web 用户永远只能明文连库（密码明文走 JDBC 通道），与 CLI 的 `--sslmode` 能力不一致；MySQL 分支还因此附带 `allowPublicKeyRetrieval=true`。README 已自认该限制（"Web 请求体暂无 sslmode 字段"），属于已知待补项。修复：请求增加 `sslmode` 字段，至少允许 require。

---

## 四、低危与代码卫生

**正确性/可用性**
- `SqlStatementSplitter.java:15-42` 只实现 PostgreSQL 词法却被三方言共用：MySQL 反引号标识符内的 `;` 会错切、`#` 行注释同理、`'a\';...'` 的 `\'` 被当作串终止；反向地 PG 专有 dollar-quote 对 MySQL 生效。目前都走向 fail-closed 的解析失败（拒绝合法 SQL），建议按方言注入词法规则。
- `CteExpander.java:279-292` 嵌套作用域中指向外层同名 CTE 的合法引用被误判为递归（PG 语义应绑外层已完成 CTE），fail-closed 但误拒。
- `RowFilterRegistry.java:51-59` 白名单拒绝一元负号，`amount > -1` 无法配置。
- `RowFilterRewriter.java:469-482` 行过滤模板经 unparse→文本→re-parse 往返：同一表 N 个引用位置 = N 次完整解析；`unparse` 以 `quoteAllIdentifiers=false` 渲染，含保留字/需引号标识符的条件 re-parse 失败且报错语义模糊。建议克隆 AST 模板替代文本往返。
- `PolicyValidator.java:362-372` 主体相交判断过于保守（A 有任何 user 且 B 有任何 group 即判可能重叠），大量误拒合理的分主体策略；`:176-195` 异常面窄（Calcite 的 `RuntimeException` 以 500 冒出而非 400）且每次写操作全实例所有列重复 parse；`:105-110` ROW_FILTER 策略的 columns 未强制为空、静默忽略。
- `EffectiveConfigCompiler.java:36-40` `disabled` 计数混入"对当前主体不适用"的数量，语义混杂。
- mask-metadata：`JdbcMetaStore.java:150-159` loadStructure N+1 查询且 `meta_column(table_id)`/`meta_table(instance_id)` 无索引（数据面每次轮询全量加载）；`StructureService.java:49-50` replace 后另发一次查询读版本，并发下返回的版本号可能不是本次操作的；实例名无长度/字符校验（超长 → 500、控制字符进日志）；YAML 导入无大小/表列数量上限。
- CLI 参数校验类错误退出码不一致：`--port 0` 与非法 `--sslmode` 落到 `catch (Exception)` → exit 1 而非用法错误的 exit 2（`SqlMaskApplication.java:229` 在 try 之外构造 `ConnectionSpec`）；`call()` 吞堆栈（154-157 行），无 `--debug` 开关。
- `docker-compose.metadata.yml`：PG 弱凭据且 `5432:5432` 绑定所有接口（库中存有各实例 host/user/passwordRef）；metadata 服务无 healthcheck/restart，PG 未就绪即启动失败。
- 两个服务未配置 API key 时的行为不对称：mask-core fail-open（放行，仅注释说明）、mask-metadata fail-closed（全 401）但**都无启动告警**；key 均只在启动时读一次环境变量，无法轮换；比较均用 `String.equals`（非常量时间，应 `MessageDigest.isEqual`）。
- YAML loader（core 与 lite 两份）静默忽略未知键，拼写错误（`rowfilter`、`colum`）无警告。
- record 自动 `toString()` 含明文密码：`ConnectionSpec.java:14-16`（当前无打印点，但一行 `log.debug(spec)` 即泄漏，建议覆写脱敏）。

**性能**
- `PolicyEngine.java:31-55` `maskFor` 每输出列 × 每 origin 线性扫全部策略且每次重复 normalize，`PolicyIndex` 名为 Index 实为 List；大策略集是热路径。
- `PolicyAdminController.java:100-106` getPolicy 全量拉取再过滤，`PolicyStore.findPolicy` 原生支持单查。

**代码卫生**
- `ValidatedSql.java:26-31` 三个访问器无调用方；`DialectCapabilities.canWrapDuplicateOutputNames` 三方言全 false，`SqlRewriteService.java:53` 早退分支恒不触发；`YamlConfigLoader.java:14` 未用 import；`LoadedConfig.normalizePart` 死代码。
- `SqlMaskServiceApplication.java:19-35` 连续两个类级 Javadoc 块（第一个残留）；`SqlMaskServiceApplication.java:40-42` `looksLikeCliInvocation` 的 CLI 参数清单缺 `--user`/`--host`/`--port`/`--engine` 等，只带这些参数会被误判进 Spring Boot 模式。
- `RewriteEngine.java:137-139` 用 `e.getMessage().startsWith("statement ")` 字符串嗅探避免序号重复，应改为异常携带结构化 ordinal。
- `ResourceSelector/TableDef/EngineInstance/UdfDefinition` 的紧凑构造器 `List.copyOf(null)` 裸 NPE（对照 `PolicyEntity` 有 null 防护），非 DTO 入口传 null 得到无消息 500。
- `JdbcPolicyStore`：`subjectFrom` 反序列化失败抛 `PolicyException` 而非 `SqlMaskException`（走 500，与同类错误处理不一致）；`createUdf` 把重复签名误报为 "already exists"；`loadUdfs` 的 RowMapper 返回 null 靠副作用收集（hack）；`schema.sql:37-38` 手工迁移备忘注释，无正式迁移工具（Flyway/Liquibase）。
- 三个 introspector 的 `sanitize` 三份近似重复（PG 版只匹配 `"jdbc:postgresql"`，另两个匹配 `"jdbc:"`）；空表警告中文与全英文诊断混排；`--strict` 判定依赖 `"degraded to varchar"` 文案后缀（文案一改即失效，应结构化降级标志）。

---

## 五、架构与模块化

### A1. mask-lite 与 mask-core 大面积复制，且已发生安全语义分叉
量化（lite 主代码 30 文件 / 约 2400 行）：12 个类逐字相同（包名替换后 0 diff，如 `SqlStatementSplitter`、`CteExpander`、`ColumnKey`、函数表、lineage 三件套、`MaskingConfig/Policy`）；8 个近似副本（<10 行差异）；显著漂移集中在 `YamlConfigLoader`、`TableMetadata`、`SqlRewriteService`、`RewriteEngine`。已固化的行为对立：
- **重复输出列名**：lite 重命名 `phone_2` 继续脱敏（`RewriteEdgeCasesTest.java:79-97` 固化），core STRICT 方言直接 `REWRITE_ERROR` 拒绝——同一条 SQL 两模块行为相反，两边测试各自钉死，合并必破其一；
- **rowFilter 在 lite 静默失效**：lite 的 `YamlConfigLoader.loadTables`（71-129 行）根本不读 `rowFilter` 键，无告警丢弃行级过滤——对脱敏工具是 fail-open；
- lite 拒绝写语句（INSERT/CTAS 一律 `UNSUPPORTED_STATEMENT`）、错误码集合少 7 个、`EXPR$N` 改名 `mask_col_N`。
建议：纯函数类抽共享模块；对重复列名行为做单一裁决两边对齐；lite 对无法理解的键显式报错。

### A2. 方言层逐字复制是 H1/H2 类 bug 的温床
`MysqlDialectAdapter.java:51-67`、`PostgresqlDialectAdapter.java:48-64`、`TrinoDialectAdapter.java:49-65` 三份完全相同的 `checkCreateTableVariant`（连注释一致）；`MysqlUnparseDialect.java:55-85` 与 `TrinoUnparseDialect.java:56-86` 约 30 行逐字重复；`PostgresqlIdentifierPolicy` 与 `TrinoIdentifierPolicy` 同构。复制粘贴方言代码正是"PG 假设泄漏到方言无关层"（如 `nameMatches` 精确大小写）的结构性原因。

### A3. mask-policy 纯库模块引入 spring-boot-starter-web
`mask-policy/pom.xml:27` 为一个 `/api/policies/parse` 端点（`PolicyController`）引入整个 Web 栈，模块边界被打破，且会被 combined jar 的 `scanBasePackages="io.sqlmask"` 扫入。建议 controller 上移 mask-core 或抽共享契约模块。

### A4. 半成品/未接线链路
`PolicyServiceConfigSource` + `ConfigSource` 接口在 main 无生产消费者（见 M4）；`JdbcPolicyStore` 无装配路径（见 M5）；`refresh()` 无调用者。"策略服务数据面拉取 + 主体缓存"整条链路尚未接入生产，接线时需补轮询调度与失效策略。

---

## 六、已核实无问题的方面

- **SQL 注入**：`JdbcPolicyStore`、`JdbcMetaStore` 全程 `?` 参数化（`?::jsonb` 也是参数占位）；改写侧 `renderLiteral` 全部经 `SqlLiteral` 渲染、字符串靠引号双写，无裸拼用户输入。
- **JDBC 资源**：introspector 的 Connection/Statement/ResultSet 全部 try-with-resources，无泄漏。
- **凭据存储设计**：mask-metadata 只存 `passwordRef`，密码走 JDBC Properties 不进 URL，驱动错误消息经 `sanitize` 剥离 URL。
- **YAML 安全**：SnakeYAML 2.2 + `SafeConstructor`（PolicyYamlLoader 禁重复 key），每次 `new Yaml()` 避免线程问题。
- **fail-closed 意识**：行过滤白名单、未知 FROM 形态、递归 CTE、悬空资源等大量路径有意识地 fail-closed（问题在于个别口径被方言差异打破，见 H1/H2/H3）。
- **缓存本身**：主体 LRU（accessOrder + 容量 256 + 逐出重拉）实现正确。

---

## 七、修复优先级建议

1. **H1 + H2**（同一修复面：`resolveTableReference`/`nameMatches` 的方言感知解析）——行过滤静默绕过。
2. **H4**（Filter 改 `getServletPath` + 段级匹配 + `MessageDigest.isEqual`）——鉴权绕过，改动小收益大。
3. **H5**（Trino SSL=true 一行修复 + 修测试）——安全开关失效。
4. **H7**（主体 selector 归一化对称）——掩码静默失效。
5. **H3**（血缘守卫全树下钻 `RexSubQuery`）——脱敏漏放。
6. **H6 + M9**（pull/collect 端点纳入鉴权 + 网段黑名单 + passwordRef 前缀白名单）。
7. **M1 + M2**（包装层派生列名、UPSERT 关键字——生成 SQL 正确性）。
8. **M3 + M4 + M5**（策略服务一致性三件套：写竞态、持锁 IO、持久化装配）。
9. **A1**（lite/core 收敛，至少先修 lite 丢 rowFilter 的 fail-open）。
10. 其余按维护节奏：异常处理器（M6）、状态码分派、退出码统一、索引/N+1、代码卫生批量清理。

---

## 八、修复记录（2026-09-16，同日完成）

高危项 H1–H7 已全部修复，每项带回归测试；全量 `mvn test`（policy/core/metadata 三模块）与 mask-lite 单独构建均通过。

### H1 + H2 — RowFilterRewriter 方言感知（`RowFilterRewriter.java`）
- 构造时从 `dialect.profile()` 读取 `caseSensitiveNameMatching` 与 `schemaPathStyle`；
- `nameMatches` 按方言选择精确/忽略大小写比较（MySQL 校验器大小写不敏感，比较口径必须一致，否则大小写变体引用绕过注入）；`isVisibleCte`、`subtreeMentionsTable`、`hasQualifiedColumnReference` 同步改为实例方法共用该口径；
- 两段名 `schema.table` 仅在 `CATALOG_SCHEMA_AND_SCHEMA`（MySQL）方言下解析注入：匹配 schema+name，多 catalog 歧义显式 `VALIDATION_ERROR`（与一段名歧义策略一致），PG/Trino 维持校验器拒绝；
- 新增测试：`mysqlCaseVariantReferenceStillInjects`、`mysqlTwoPartReferenceInjects`、`mysqlCaseVariantCteScopeShadowsBaseTable`、`mysqlAmbiguousTwoPartNameFailsExplicitly`、`pgTwoPartNameIsStillLeftToTheValidator`。
- **附带发现并修正文档错误**：README 方言表原写「Trino 支持两段名」，实测（探针验证）Trino 校验器直接拒绝两段名（`Object 'public' not found`），行过滤绕过只在 MySQL 存在。README 方言表与行过滤章节已同步修正。

### H4 — PolicyApiKeyFilter（`PolicyApiKeyFilter.java`）
- 路径判断从 `getRequestURI()`（未解码、含 context path）改为 `getServletPath()`（已解码、已去 context path），与容器的 filter 映射口径一致，封死 URL 编码绕过（`/api/%69nstances`）与 context-path 部署失效两种路径；
- 前缀判断改段级匹配（`equals` 或 `startsWith("/api/instances/")`），防止 `/api/instances-evil` 类兄弟路径误伤；
- API key 比较改 `MessageDigest.isEqual`（常量时间）；
- 新增测试：`encodedUriCannotBypassTheGate`、`contextPathDeploymentIsStillGuarded`、`segmentBoundaryIsEnforced`。

### H5 — Trino sslmode=require（`ConnectionSpec.java`）
- require 分支生成 `?SSL=true`（trino-jdbc 默认 `SSL=false`，原来与 disable 生成的 URL 等价，加密语义静默失效）；
- `ConnectionSpecTest.trinoUrl` 的错误期望已修正为 `?SSL=true`。

### H7 — SubjectSelector 归一化（`SubjectSelector.java`）
- 紧凑构造器统一对 users/groups 做 trim（与 `Subject.of` 对称），空白项直接抛 `PolicyException`（fail-fast，不静默收窄策略覆盖面）；YAML 装载、Jackson 反序列化、Admin DTO 三条构造路径一并覆盖；
- 新增测试：`selectorNamesAreTrimmedSymmetricallyWithSubject`、`blankSelectorNameIsRejected`。

### H3 — LineageAnalyzer 全树子查询守卫（`LineageAnalyzer.java`）
- 原守卫只覆盖根投影（沿 Sort→Filter→Project 下钻），UNION 分支/CTE 体投影里的标量子查询被 Calcite 元数据层判为空来源（`NO_ORIGIN`）原样透传；现改为分析前扫描整棵 rel 树所有 Project 表达式（不深入 `RexSubQuery` 内部子树），命中即整条语句所有输出列判 `UNKNOWN` → `RewritePlan` 抛 `LINEAGE_UNKNOWN`（fail-closed）；
- WHERE/HAVING/JOIN 条件位置的子查询不产出输出值，仍然放行；
- 新增测试：`subQueryInSetOpBranchIsNotPassedThroughAsNoOrigin`、`subQueryInCteBodyProjectionIsNotPassedThrough`、`subQueryInWhereClauseKeepsResolvableOutput`（守卫不影响常规路径）。

### 验证与工作区状态
- `mvn test`：mask-policy（65+）、mask-core（全量）、mask-metadata（84）全部通过，BUILD SUCCESS；mask-lite 单独 `mvn test` 111–112 个测试通过；
- **新发现**：mask-lite 未列入父 pom `<modules>`（`mvn test` 从不构建它，属构建孤儿），建议决定收编或移除；
- **工作区提示**：本次修复期间检测到并行会话对同一工作区的其他改动（两个模块 pom、`CteExpander`/`SqlStatementSplitter`、mask-lite 若干文件、两个 IT 测试被删除）；上述全量验证是在包含这些改动的合并状态下通过的，未做回退。
