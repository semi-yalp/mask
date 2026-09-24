# sql-mask 项目问题分析报告

- 日期:2026-09-24
- 范围:12 个 Maven 模块 + frontend(2141 个 Java 文件,305 个提交,分支 feature/instruct)
- 方法:5 路并行只读代码审查(核心引擎 / 服务层 / 支撑层 / 工程化 / 前端),高危结论均经二次人工核实
- 行号以当前 HEAD(e68a40f)为准

---

## 一、高危问题(6 项,建议优先处理)

### H1. BearerFilter 禁用态修复未扩散,两个服务保留"空注册崩溃"代码
提交 e68a40f 修复了"空 FilterRegistrationBean + setEnabled(false) 在真机 Tomcat 上崩溃(addFilter(getFilter()) 先于 setEnabled 执行)"的问题,但修复**只落在 mask-core**:

- 已修复:`mask-core/src/main/java/io/sqlmask/server/SqlMaskServiceApplication.java:142-144`(注册透明实例再禁用)
- 未修复:`mask-policy-server/.../PolicyServerApplication.java:149-152` 和 `mask-query/.../QueryServerApplication.java:89-92` 仍是崩溃写法

而"未配置 `MASK_AUTH_SECRET`"的默认配置下三者都会走到禁用分支——policy-server 与 query-server 在默认部署下存在启动崩溃风险(LDAP 未启用即默认路径)。

### H2. policy-server 管理面存在鉴权绕过(URL pattern 缝隙)
`PolicyServerApplication.java:88`:`addUrlPatterns("/api/instances/*", "/api/effective/*")` 是 Servlet 路径映射,`/api/instances/*` **不匹配精确路径 `/api/instances`**。因此 `POST /api/instances`(创建实例)与 `GET /api/instances`(列出全部实例及表结构)在任何部署模式下都不过 ApiKeyFilter;LDAP 关闭时连 BearerAuthFilter 也不生效,管理面精确路径可完全绕过。对比 mask-metadata 用 `/api/*` 覆盖,无此缝隙。

### H3. 风控规则"启用"开关恒真失效
`mask-risk-server/.../web/RuleController.java:77`:

```java
RiskSeverity.parse(request.severity()), request.enabled() || true,
```

`request.enabled() || true` 恒为 true,控制台勾选"启用"被忽略,新建自定义规则永远直接启用。没有任何测试覆盖 HTTP 层 `enabled=false` 语义(现有测试都绕过 Controller 直接构造 RiskRule),故未被测试捕获。

### H4. 风控 webhook 为同步投递,阻塞整条摄取管线
`mask-risk-server/.../notify/NotificationSink.java:67-88`:webhook 用 `http.send(...)` 同步发送(connect 2s + request 3s 超时),调用链位于 `RiskEngine.ingest()(synchronized)` 的请求线程内(AlertManager.java:63-65 → RiskEngine.java:120)。与类注释 "Delivery never blocks or fails the ingest path"(26-28 行)直接矛盾:webhook 慢/不可达时,每次告警摄取被阻塞最长约 5 秒。

### H5. 前端与 risk-server 鉴权契约断裂:LDAP 用户看一次风控页就被踢下线
`frontend/src/api/http.ts:20-26` 登录态只带 `Authorization: Bearer`,从不带 `X-Api-Key`;而 mask-risk-server 是**唯一没有注册 BearerAuthFilter 的服务**,只校验 `X-Api-Key`(RiskApiFilter.java:46-58, RiskServerApplication.java:103-107)。后果:配置了 `RISK_API_KEY` 的部署中,LDAP 用户打开任意风控页 → 401 → `http.ts:39-44` 判定"令牌被拒"→ `sessionExpired()` 清会话跳登录页。且 `settings.ts` 的三把 Key 模型没有 RISK_API_KEY 槽位,API Key 模式同样无法覆盖 8084。

### H6. Docker 部署下风险服务不可达(nginx 指向容器自身)
`frontend/nginx.conf.docker:81` risk upstream 用 `proxy_pass http://127.0.0.1:8084`,而同文件其余全部用 `host.docker.internal`(39-40、47-48、54、63、72、90 行),且 `docker/nginx.Dockerfile` 注释明确"容器内 127.0.0.1 指向容器自身"。Docker 部署下 `/api/risk/*` 全部 502。

---

## 二、中危问题

### 安全与鉴权
| # | 问题 | 位置 |
|---|---|---|
| M1 | 未配置 `SQLMASK_ADMIN/ DATA_API_KEY` 时管理面/数据面 fail-open,仅打一条 warn("OPEN to anyone"),生产默认部署即开放 | PolicyServerApplication.java:71-91 |
| M2 | API-key 模式下 `user`/`groups` 完全由调用方自报,持 data key 可伪装任意主体拉取其未脱敏配置 | EffectiveConfigController.java:49-53;QueryController.java:46-49 |
| M3 | query/metadata 默认 `sslmode=disable`,MySQL 分支硬编码 `allowPublicKeyRetrieval=true`,口令明文过网 | QueryEngine.java:49-66 |
| M4 | **JWT 密钥静默降级**:`MASK_AUTH_SECRET` 配置了但 <32 字节时 `tokenEnabled()` 静默返回 false 且无任何 warning,拼写/配置错误导致安全能力无声变 open | AuthConfig.java:134-136 |
| M5 | `/api/auth/login` 无速率限制/失败锁定,LDAP bind 在线爆破面;401/503 区分可探测 LDAP 可用性 | AuthLoginController.java:66-78 |
| M6 | risk-server:`risk.api-key` 默认空 = 控制台+ingest+block 端点默认全开放;`risk.block.api-key` 默认硬编码众所周知的 `local-admin-key` | RiskProperties.java:10-11;application.yml:9,19-20 |
| M7 | `/admin/cache/refresh` 无任何认证(过滤器只覆盖 `/api/audit/*` 与 `/api/rewrite/instances/*`),任何可访问者都能清空策略缓存 | CacheRefreshController.java:26-29 |
| M8 | 三个服务的异常处理器把 `e.getMessage()` 原样回显(泄露 JDBC URL、上游 URI、SQLState);actuator health/prometheus 未鉴权 | PolicyApiExceptionHandler.java:21-47;ApiExceptionHandler.java:19-33;MetadataApiExceptionHandler.java:18-32 |
| M9 | 前端 JWT 与三把长期 API Key 明文存 localStorage(XSS 即窃取);登出无服务端吊销,Bearer 在 TTL 内仍有效 | frontend/src/stores/auth.ts:5,46-51;settings.ts:33-35 |

### 数据正确性
| # | 问题 | 位置 |
|---|---|---|
| M10 | `PUT /api/instances/{name}` 空 body(或 connection 缺省)时静默把该实例连接配置整体置 NULL——易触发的数据丢失型默认行为 | MetadataAdminController.java:92-99;JdbcMetaStore.java:98-113 |
| M11 | 所有 `SqlMaskException` 一律映射 400,`POLICY_SERVICE_UNAVAILABLE`/`INTROSPECT_ERROR` 等服务端故障无法与请求错误区分(502 的 AuditSearchUnavailableException 已示范正确做法) | ApiExceptionHandler.java:19-22;RewriteController.java:120-125 |
| M12 | RiskEventController 按 user 过滤用 `equalsIgnoreCase` 实为 `contains`(filter=alice 会误中 malice);IngestParser 时间戳解析失败静默回退 `Instant.now()` 扭曲滑动窗口基准 | RiskEventController.java:95-98;IngestParser.java:99 |

### 并发与资源
| # | 问题 | 位置 |
|---|---|---|
| M13 | PolicyServiceConfigSource 在整个实例上加 synchronized,10s 超时的 HTTP 调用在锁内执行,并发请求被串行化、refresh 轮询阻塞线上请求 | PolicyServiceConfigSource.java:71-98 |
| M14 | QueryService 的 permits 按实例名永久累积从不清理;测试钩子 `holdPermitForTest/releasePermitForTest` 残留在生产主代码 | QueryService.java:69-75,161-177 |
| M15 | MemoryRiskStore 的 alerts Map 无容量上限/过期淘汰(events 有滚动,alerts 没有),且 `findActiveGroupedAlert` 每次 O(N) 全表扫描;PersistingRiskStore 每 3s 将整个工作集全量序列化落盘 | MemoryRiskStore.java:137-171;PersistingRiskStore.java:141-170 |

### 测试质量
| # | 问题 | 位置 |
|---|---|---|
| M16 | **RiskForwarderTest 的根本竞态未消除**:f3de5cf 把 flush 间隔 100ms→10s 只排除了"定时器抢在批满前刷半批"这一触发源;但 worker 的 flush 由"poll 到事件后立即 drainTo+flush"驱动,测试线程两次 record 之间 worker 抢先 drain 到半批时 `batch.size()==2` 断言仍会失败。另外 `batchSize` 从不作为批满触发条件,稀疏流量下每个事件单独成批 POST,批聚合配置形同虚设 | RiskForwarder.java:99-119;RiskForwarderTest.java:63-77 |
| M17 | policy-server 管理面/数据面的 API-key 门禁**完全未被测试**(所有测试不设 key 不发 X-Api-Key),H2 的缝隙因此无法被发现;query 测试全部 `addFilters=false` 跳过过滤器 | PolicyAdminEndpointTest / EffectiveConfigEndpointTest;QueryControllerTest.java:25 |
| M18 | CI 中 mask-auth、mask-sqlparser 的测试**静默 0 执行**:两模块 pom 无 surefire 声明,回落 super-POM 2.12.4 无法运行 JUnit 5(其余 8 模块显式 3.2.5)。CI job 自称 "10 modules" 实际 12 | mask-auth/pom.xml;mask-sqlparser/pom.xml;ci.yml:6 |
| M19 | EsAuditRecorderTest 的 `overflowIsDroppedAndCountedNotBlocking` 只断言耗时 <1s,对"丢弃计数"零断言(注释自认 "drop or offer");`closeDrainsAtMostFiveSeconds` 依赖人工 2s 延迟与宽窗口,属脆测试 | EsAuditRecorderTest.java:79-89,233-253 |

### 配置与文档
| # | 问题 | 位置 |
|---|---|---|
| M20 | `AUDIT_ENABLED` 默认值三模块互相矛盾:query 为 `${AUDIT_ENABLED:false}`、metadata 为 `${AUDIT_ENABLED:true}`、policy-server 继承 AuditProperties 的 `enabled=true`;audit 默认 ES 指向 `http://127.0.0.1:9200` 无 key,裸部署行为不可预期 | mask-query/application.yml:11;mask-metadata/application.yml:20 |
| M21 | `docs/功能清单.md:169-173` 整节推销已退役的 mask-lite CLI(`io.masklite` 全仓库引用为 0,README:26 已声明退役);mask-risk-server 在 README/使用手册/功能清单中零提及 | docs/功能清单.md |
| M22 | 凭据硬编码进 git 历史:`docker/auth/docker-compose.auth.yml` 的 `LDAP_ADMIN_PASSWORD: "admin-secret"` + bootstrap.ldif 明文 userPassword;compose 中 mysql/starrocks/postgres 弱口令(密码即服务名)且 `3306/5432/9030/8030` 绑 0.0.0.0(ES 却特意绑了 127.0.0.1,内部实践不一致);`deploy/native-deploy.sh:9-33` heredoc 硬写 `PgTest2026`、`local-*-key`、`SQLMASK_DS_CRM_PASSWORD` | docker/ 目录;deploy/native-deploy.sh |
| M23 | deploy 脚本 `set -e` 但所有 curl 只 `-s` 不 `--fail`,HTTP 400/500 时退出码仍为 0,静默吞错"成功"退出 | deploy/e2e-setup.sh:10-12;deploy/e2e-verify.sh:11-13 |

### 前端
| # | 问题 | 位置 |
|---|---|---|
| M24 | `Playground.vue:23` `<el-select ... loading>` 静态属性恒为 true,实例选择器永久显示 spinner | frontend/src/views/playground/Playground.vue:23 |
| M25 | MetadataManager 列表 N+1(每个实例再发一次 getMetaInstance);5 个页面挂载时并发调 `instances.load()` 无去重/竞态守卫;AuditView/RiskEventsView/AlertsView 翻页无序号守卫,慢响应可覆盖新结果 | MetadataManager.vue:238-241;ConsoleLayout/Dashboard/AccessManager/Playground/PolicyManager 挂载逻辑 |
| M26 | 路由守卫是软校验:ldapMode 探测失败即按 API Key 模式放行,且从不调用后端已提供的 `GET /api/auth/me` 验证 token 真实性,刷新后凭 localStorage 残留 token 直接进入 | frontend/src/router/index.ts:44-56 |

---

## 三、低危问题(数量较多,择要列出)

**重复代码(高优先重构候选):**
- `checkCreateTableVariant()` 在 Mysql/Trino/Postgresql/Hive/SparkSql 五个 DialectAdapter 中逐字相同(仅 hive/sparksql 错误消息多后缀),应上提父类
- Mysql/Hive/SparkSql/Trino 四个 UnparseDialect 类 86-88 行完全重复(仅父类不同);Mysql/Hive/SparkSql 三个 IdentifierPolicy 逐字相同;HiveTypeResolver 与 SparkSqlTypeResolver switch 逻辑完全相同仅错误消息不同
- AdminMetrics 在 policy-server 与 metadata 中逐行复制(注释自认 "Duplicate of the mask-core class on purpose")
- 三个服务的 BearerAuthFilter 禁用注册样板 + 中文/英文混杂的文案(`MysqlMetadataIntrospector` 等警告语为中文,其余全英文)多处重复

**死代码:**
- `SqlInsertOverwrite.isOverwrite()`(仅测试用)、`DialectCapabilities.describe()`、`LineageAnalyzer.analyze(RelNode, RelDataType)` 双参重载、`InlineYamlConfigSource`(三个模块 main 无引用)、`LoadedConfig.normalizePart()`、`ConfigSource.load()` 无参接口、`SqlMaskServiceApplication` 类上两个连续 javadoc(第一个残留)、`CliOptions` 5 参/8 参遗留构造器

**性能:**
- `PostgresqlIdentifierPolicy.java:34` / `TrinoIdentifierPolicy.java:35` / `MetadataYamlGenerator.java:46` 每语句/每值重编译正则,应预编译 static final Pattern
- SqlValidatorFactory 每次语句重建方言操作符表(数千操作符枚举成 Set);RewriteEngine:224-227 对同一棵树重复全量 unparse 两次;RowFilterRewriter:512-521 同一派生表多次重复 parse

**健壮性:**
- QueryEngine 无 socketTimeout,半开 TCP 上 permit 一直被占用;JDBC URL 硬编码 `socketTimeout=60` 无配置入口(ConnectionSpec.java:55)
- `SqlMaskApplication.java:244-248` 即使 engine=mysql/trino 也强制要求 `--password`/PGPASSWORD 且文案指名 PGPASSWORD,与 Trino 免密场景不符
- `MetadataController.java:54-59` HTTP 端点硬编码 host=127.0.0.1:5432/sslmode=disable,API 面覆盖不了非默认端口部署
- `RewriteEngine.java:145-147` 用 `e.getMessage().startsWith("statement ")` 文本前缀判断错误类型,脆弱魔法字符串
- EsAuditRecorder 关闭时队列中未发送事件全部丢弃(与注释 only-drain-in-flight 勉强自洽但值得记录);auditElasticsearchClient bean 未声明 destroyMethod 存在双关风险

**工程与仓库卫生:**
- `mask-lite/` 目录是退役残留(只剩 dependency-reduced-pom.xml + 29MB shaded jar,根 pom 已不含,pom 内 groupId 是独立的 io.masklite)
- 磁盘残留:.tools/ 176M(其中 ps-head 94M 是全项目拷贝)、bench/ 82M、mask-lite fat jar 29M、根目录 25 个 t*.log/full-merge-test*.log(纯噪音)
- 分支卫生:16 个本地分支已完全并入 main(feature/audit-log-es、fix/audit-es-hardening、main-2、feat/ldap-auth、feature/instruct 等),另有 5 个实质已合并但哈希不同(feature/audit-es-impl、impl2、yhh-main、fix/e2e-report-p1-p3、test/masked-row-filter-combined);**new-main 需先人工核对其 3 个 policy-server 提交(127b617/e2be846/9da5c18)确实已入 main 再删**;15 个 worktree 大量陈旧
- `.gitignore` 前 40 行 CRLF 后 10 行 LF 混合,git 的 check-ignore 解析汇报不可信(实测 bench/ 等命中空行第 38 行,忽略机制其实是"碰巧");`.gitattributes:1` 与 `:3` 是重复规则
- mask-query 的 hive-jdbc 3.1.3 无版本托管、靠长串 excludes 压传递依赖,与 calcite 的 guava 33.x 存在 nearest-wins 冲突隐患;junit-jupiter 在 10 个模块重复显式声明(纯冗余);内部依赖有的写死 `0.1.0-SNAPSHOT` 有的用 `${project.version}` 不统一
- 前端 `deploy.sh:45,50` 用 `npm install`(非 `npm ci`),远端构建不生效 lock;ci.yml 注释 "package-lock.json 未入库" 与事实相反(已在库);nginx 仅 listen 80 明文;Settings.vue 服务拓扑页遗漏 8084
- docs/端到端使用指南.md 硬编码实战环境 IP `47.100.166.158`;refactor-plan.md 模块拓扑仍缺 mask-auth/mask-risk-server

---

## 四、做得好的地方(避免过度修复的依据)

- **JWT 实现扎实**:alg 钉死 HS256 拒绝算法混淆、常量时间比较、构造器强制密钥 ≥32 字节、exp 严格校验——认证核心没有典型漏洞
- **审计模块主体设计正确**:EsAuditRecorder 单 worker + 有界 ArrayBlockingQueue(满则计数丢弃不阻塞 ingest)、失败降级 Noop 符合 spec、索引模板与事件字段一一对应、跨模块 key 名一致
- **数据库访问安全**:全仓库 JDBC 参数化、无 SQL 拼接;policy 的 YAML 加载用 SafeConstructor + 禁重复 key,无 YAML 别名攻击面
- **测试基础设施优秀**:JDBC 测试用真实 EmbeddedPostgres、LDAP 测试用 UnboundID InMemoryDirectoryServer、HTTP 用 127.0.0.1 随机端口 stub,无外网依赖、无 @Disabled、无空跑断言;前端 10 个 spec 全部有实质断言
- **前端契约扎实**:src/api 路由、参数名、校验规则与后端 controller 逐一对得上;vite proxy 与 nginx 路由表同构
- 端口规划(8080-8084)无冲突;shade 配置的 SpringFactoriesTransformer 真实存在且被正确使用

---

## 五、建议修复顺序

**第一批(安全/稳定性,1-2 天):**
1. H2:policy-server 过滤器 pattern 改为 `/api/instances` 与 `/api/instances/*` 都注册(或统一 `/api/*`)
2. H1:把 mask-core 的透明实例修复模式复制到 policy-server 与 query-server
3. H3:删掉 `|| true`;H4:webhook 改为队列异步(参考 EsAuditRecorder 的 worker 模式);H6:nginx.conf.docker 改 host.docker.internal
4. H5:risk-server 注册 BearerAuthFilter 或前端对 risk 域改用 X-Api-Key
5. M10:PUT 空 body 不清空保护;M7:/admin/cache/refresh 加认证

**第二批(正确性,2-3 天):**
6. M16:RiskForwarder 改为"批满或超时才 flush",并重写测试为确定性同步测试(直接调 flush 或注入队列)
7. M13:PolicyServiceConfigSource 锁细化;M8:异常消息脱敏
8. M18:根 pom 加 pluginManagement 统管 surefire,CI 改名 12 modules
9. M20:统一 AUDIT_ENABLED 默认值与 ES 地址来源

**第三批(债务清理,择机):**
10. 方言层 5 处 ×4 类重复合并上提;死代码清理
11. 分支清理(先核 new-main)、worktree 收编、mask-lite/.tools/bench/日志清理、.gitignore 统一 LF
12. 文档同步(功能清单去 mask-lite、补 risk-server、端到端指南去 IP)
13. M22/M23:凭据改为注入式,curl 统一 `--fail`

---

## 六、修复记录(2026-09-24)

第一批(安全/稳定性)全部完成,第二批完成 5/6 项。以下每项均随对应模块测试验证通过(除注明外)。

### 已修复

| 编号 | 修复内容 | 涉及文件 |
|---|---|---|
| H2 | ApiKeyFilter 补精确路径 `/api/instances`、`/api/effective`(servlet 路径映射只认通配不认精确);过滤器改为经 Spring Environment 读 Key,可直接用测试属性注入 | PolicyServerApplication.java |
| H1 | policy-server / query-server 的禁用态 Bearer 注册改为"透明实例 + setEnabled(false)"(与 mask-core e68a40f 修复一致),消除默认配置真机启动崩溃 | PolicyServerApplication.java、QueryServerApplication.java |
| M7 | `/admin/cache/refresh` 加 fail-closed 门禁:`X-Api-Key`=`SQLMASK_ADMIN_API_KEY`,未配置默认拒绝;README/使用手册/功能清单同步 | SqlMaskServiceApplication.java、AdminCacheRefreshGateTest.java、三份文档 |
| H3 | 删除 `request.enabled() \|\| true`;新增 HTTP 层测试覆盖 enabled=false/true | RuleController.java、RuleCreateWebTest.java |
| H4 | NotificationSink webhook 改异步:有界队列 + 单 worker 线程,notify() 永不同步等待投递;新增"慢 webhook 不阻塞 ingest"回归测试 | NotificationSink.java、NotificationSinkTest.java |
| H5 | risk-server 接入 BearerAuthFilter(新增 mask-auth 依赖、规则 /api/risk=USER、顺序 HIGHEST_PRECEDENCE);RiskApiFilter 放行已过 Bearer 的请求;新增 RiskBearerGateTest | RiskServerApplication.java、RiskApiFilter.java、pom.xml、RiskBearerGateTest.java |
| H6 | nginx.conf.docker 的 risk upstream 127.0.0.1:8084 → host.docker.internal:8084 | nginx.conf.docker |
| M10 | `PUT /api/instances/{name}` 空/无 connection body 时不再清空既有连接配置(原测试改为断言保留,服务层 null=清空语义不变) | MetadataAdminController.java、MetadataAdminControllerTest.java |
| M8 | 异常处理器不再回显 `e.getMessage()`:未预期异常与畸形 JSON 统一返回固定文案,详情只进服务端日志;同步更新断言泄露的测试 | BaseApiExceptionHandler.java、MetadataApiExceptionHandlerTest.java |
| M16 | RiskForwarder 改为"批满或首个事件后最长等待 flushIntervalMs"触发;消除 worker 抢先刷半批的 flake 根因;新增稀疏流超时 flush 测试 | RiskForwarder.java、RiskForwarderTest.java |
| M13 | PolicyServiceConfigSource 网络调用移出全局锁:load 按缓存锁 + 锁外拉取,refresh 快照键 + 锁外拉取 + 锁内带版本比对更新(修复 put 原地改写 entry 导致版本比较失效的初版 bug) | PolicyServiceConfigSource.java |
| M20 | `AUDIT_ENABLED` 全模块统一默认 false(core/metadata/policy-server 显式配置);AuditProperties Java 默认 false;自动配置条件去掉 matchIfMissing,让 Java 默认成为唯一默认源 | AuditProperties.java、AuditAutoConfiguration.java、三个 application.yml、AuditAutoConfigurationTest、AuditPropertiesTest、RiskForwarderTest、MetricsEndpointSmokeTest |
| M18 | 根 pom 加 pluginManagement 统管 surefire 3.2.5(mask-auth/mask-sqlparser 此前回落旧版导致测试静默 0 执行);ci.yml 模块数 10→12、改用 npm ci | pom.xml、ci.yml |
| M17 | 新增 PolicyApiKeyGateTest:精确路径/通配路径无 Key 401、正确 Key 200、跨 surface Key 互斥 | PolicyApiKeyGateTest.java |

### 验证结果(2026-09-24)

- 全量 `mvn -B verify`(从源码构建):mask-build-tools / sqlparser / policy / engine / audit / common / auth / core / query / metadata / policy-server(149 测试)全部 SUCCESS;risk-server 49 测试在源码依赖下 SUCCESS(BUILD SUCCESS)。
- 前端 `npm test`:10 个 spec 52 项全部通过。
- 失败仅剩两处,均为本机环境因素而非代码问题:
  1. policy-server/risk-server 的 fat-jar repackage 被**正在运行的本机实例**锁住(`java -jar mask-policy-server/target/...jar` 对 Windows rename 持有排他句柄)——停止本地实例后 `mvn verify` 即可完成打包(CI 无此问题);
  2. 用 `-pl <module>` 单模块测试且不带 `-am` 时会解析到本地仓库的陈旧构件(凌晨 01:44 前安装,早于 LDAP 合并),导致 QueryLdapBearerTest 假失败——`mvn install` 刷新仓库或带 `-am` 即可。

### 未做(属后续批次)
- 第二批 M6(登录限流/risk api-key 默认开放)中 api-key 默认值策略、M22/M23 凭据注入化、第三批全部(方言重复合并、死代码、分支清理、.gitignore 行尾等)。

### 重新部署验证补记(2026-09-24 中午,本地全栈 + 真机 Tomcat)

重部署时又暴露并修复了两个部署级问题:

| 编号 | 问题 | 修复 |
|---|---|---|
| D1 | **mask-metadata 也有同样的空 FilterRegistrationBean 崩溃点**(此前报告漏了第三个服务):默认配置(无 MASK_AUTH_SECRET)下真机 Tomcat 启动即崩 `Filter must not be null`。policy/query 修完后还剩它 | MetadataServerConfig.java:57-59 改为透明实例注册,重启后 Tomcat 正常启动 |
| D2 | **mask-query fat jar 启动即崩**:hive-jdbc → hive-common 传递引入 2007 年 Tomcat 5.5 时代 `tomcat:jasper-*` 与 `org.glassfish.web:javax.servlet.jsp:2.3.2`(内嵌 org/apache/jasper 实现类),Spring Boot 把它当 JSP servlet 装配,与 tomcat-embed 10 冲突(`JspServlet is not a Servlet`,ClassCastException)。此前该服务从未在本地/CI 以 fat jar 启动过 | mask-query/pom.xml 的 hive-jdbc 排除清单补 `tomcat:jasper-compiler/runtime`、`org.glassfish.web:javax.servlet.jsp`、`javax.servlet.jsp:jsp-api`;重建后 7.7s 启动成功 |

**完整端到端验证结果(全部真实服务、真实 PG,PG 为本地 5432 便携实例,补建 mask_policy/mask_metadata/crm 三库 + UDF + 50 行 crm 种子):**
- 元数据:实例创建 + collect 拉了 4 表 21 列 ✓
- 策略:import-metadata、4 个 UDF、5 条策略(4 脱敏 + 1 行过滤)✓
- 生效配置:alice(devs) 4 列脱敏无行过滤;bob(analysts) 同脱敏 + orders 行过滤 ✓
- 改写:core instance 模式输出 `mask_name/mask_phone/mask_email/mask_idcard` 包装 SQL ✓
- 真库查询:alice 返回 3 行全脱敏(`138****4517`/`u***@test.org`/`330102********0011` 等);bob 联表 rowFiltered=true 仅 north 26 行 ✓
- **运行时安全门禁**(新修复的真机验证):`POST /admin/cache/refresh` 无 Key → 401(M7);`POST /api/instances` 精确路径无 Key → 401、带 Key → 200(H2)✓
- 风控:审计事件形状摄取 accepted + SQLI 命中 + 生成告警 ✓
- policy/metadata 的 `actuator/health` 显示 503 仅为 ES 健康指示器(本机无 ES 9200),服务本身正常;启动时应带 `MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED=false`(start-local.sh 本就带)。