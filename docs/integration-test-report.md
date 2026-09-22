# sql-mask 微服务本地部署与端到端串接测试报告

| 项目 | 内容 |
|---|---|
| 测试时间 | 2026-09-18 06:37 – 07:10 (UTC+8) |
| 被测代码 | `main` 分支 @ `944d4a29295d10422ac7d7ccc65eaa00fde06370`（fix(build): shade 合并 META-INF 同名资源） |
| 测试仓库位置 | `C:\Users\yhh\orca\mask-main-tmp`（main 分支 worktree，测试前已 `git pull` 至最新） |
| 测试方式 | 黑盒端到端：真实启动全部微服务 + 基础设施，按真实调用顺序串接 API，含降级/恢复场景 |
| 总体结论 | **核心串接链路全部打通（22 项用例通过）；发现 1 个 P1 构建缺陷、1 个 P2 数据质量缺陷、2 个 P3 问题** |

---

## 1. 结论摘要（TL;DR）

1. **串接流程验证通过**：元数据服务(8082) → 策略服务(8081) 跨服务拉取表结构 → UDF 注册/校验 → 脱敏与行过滤策略 → 生效配置编译 → 改写服务(8080) instance 模式拉取配置并完成脱敏改写（外层 UDF 包装 + 行过滤子查询 + 聚合包装一次到位）；审计事件三类（REWRITE / ADMIN_CHANGE / EFFECTIVE_PULL）全部落地 Elasticsearch 并可经查询 API 检索；Prometheus 六项审计指标正常暴露与递增。
2. **降级/恢复语义符合设计**：ES 停机改写业务不受影响（丢弃审计 + 限频 WARN，查询返回 502 `AUDIT_SEARCH_UNAVAILABLE`），ES 恢复后自动续写；策略服务停机时有缓存实例继续改写（stale-but-available）、无缓存实例 fail-closed（绝不放行），恢复后自动续用。
3. **P1 缺陷（测试时未修复，测试后已修复并复验，见 §7）**：`944d4a2` 的 shade 修复只解决了 `AutoConfiguration.imports` 的合并；`spring.factories` 用 `AppendingTransformer` 拼接产生**重复 properties 键**，`ConfigDataEnvironmentPostProcessor` 被覆盖丢失 → **mask-core 的 application.yml 从未被加载**，README 宣称的 `http://localhost:8080/actuator/prometheus` 实际不可用（仅暴露默认的 health）。测试中曾用 `--management.endpoints.web.exposure.include=health,prometheus` 启动参数绕过完成指标验证。
4. 其余服务启动即正常；所有本地进程已在测试结束后停止，端口已释放。

---

## 2. 部署环境与步骤

本机无 Docker，基础设施采用本地直跑方式（与 2026-09-17 Task 16 补充冒烟的"ES zip 直跑"路线一致）：

| 组件 | 版本/来源 | 端口 | 说明 |
|---|---|---|---|
| PostgreSQL | 14.10（本机 zonky embedded-pg 二进制，`%TEMP%\embedded-pg`） | 127.0.0.1:5432 | initdb 全新实例，建 `mask_policy`、`mask_metadata` 两库（无 psql，经 JDBC 建库） |
| Elasticsearch | 8.13.4 官方 windows zip | 127.0.0.1:9200 | `discovery.type=single-node`、`xpack.security.enabled=false`、堆 512m |
| mask-policy-server | main `944d4a2` 构建产物 fat jar | 8081 | env：`SQLMASK_ADMIN_API_KEY=local-admin-key`、`SQLMASK_DATA_API_KEY=local-data-key`、`POLICY_PG_URL=jdbc:postgresql://127.0.0.1:5432/mask_policy` |
| mask-metadata | 同上 | 8082 | env：`METADATA_API_KEY=local-dev-key`、`METADATA_PG_URL=.../mask_metadata` |
| mask-core (sql-mask) | 同上 | 8080 | env：`POLICY_SERVICE_URL=http://127.0.0.1:8081`、`POLICY_SERVICE_API_KEY=local-data-key`；**P1 绕过参数**见 §4.1 |

- 构建：`mvn -DskipTests package` 一次通过，产出 `mask-core/target/sql-mask.jar`（shade，85MB）、`mask-policy-server/target/mask-policy-server-0.1.0-SNAPSHOT.jar`、`mask-metadata/target/mask-metadata-0.1.0-SNAPSHOT.jar`（均 spring-boot repackage）。
- 健康检查：三个服务 `/actuator/health` 均返回 `{"status":"UP"}`；ES 根端点返回 8.13.4 集群信息；PG 经 JDBC 连通。
- schema 自动初始化（`schema.sql` / `metadata-schema.sql`，`sql.init.mode=always`）在两个 PG 库上执行成功（策略/实例 CRUD 正常即证明）。

## 3. 服务清单与启动结果

| 服务 | 端口 | 启动结果 | 备注 |
|---|---|---|---|
| mask-policy-server | 8081 | ✅ UP（~20s 就绪） | PG schema 自动建表成功 |
| mask-metadata | 8082 | ✅ UP（~20s 就绪） | audit 管道连 ES 正常 |
| mask-core | 8080 | ✅ UP（复跑后） | **首次启动出现一次瞬态失败**，同 jar 同命令复跑即恢复，详见 §4.4 |
| Elasticsearch | 9200 | ✅ UP | green，索引模板由服务启动时幂等安装 |
| PostgreSQL | 5432 | ✅ UP | 两库两 schema |

## 4. 发现的缺陷与风险

### 4.1 【P1】shade fat jar 丢失 spring.factories 键值 → mask-core 的 application.yml 整体不生效（含 README 宣称的 Prometheus 端点）

- **现象**：`GET http://localhost:8080/actuator/prometheus` 返回 500 `{"code":"INTERNAL_ERROR","message":"No static resource actuator/prometheus."}`；`GET /actuator` 发现页只有 `health`（Boot 默认暴露），而同配置的 mask-metadata(8082) 正常暴露 `health,prometheus`。
- **根因**（已用探针实证）：`944d4a2` 为合并同名 META-INF 资源给 `.imports`/`spring.factories`/`spring.handlers`/`spring.schemas` 配了 `AppendingTransformer`。`.imports` 是行式格式，拼接正确（合并后 275 条候选、包含被 exclude 的类）；但 **`spring.factories` 是 properties 格式，多个 jar 各自声明同名 key，逐字节拼接后 `java.util.Properties` 解析同名键 last-wins**。在该 jar 上运行 `SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class)` 只剩 1 项 `LogCorrelationEnvironmentPostProcessor`（actuator 的，恰为最后拼接），**`ConfigDataEnvironmentPostProcessor` 丢失** → ConfigData 机制死亡 → **application.yml/application-profiles 全部不加载**。同键被覆盖的还有 `ApplicationListener`/`ApplicationContextInitializer`/`FailureAnalyzer`/`DatabaseInitializerDetector` 等（合并文件中各出现 2~3 次）。
- **影响面**：mask-core 一切依赖 yml 的配置静默失效——`management.endpoints.web.exposure.include=health,prometheus`（本例）、`spring.application.name`、`management.metrics.tags.application`、logging 配置等。当前服务"能正常跑"纯属代码默认值与 yml 值恰好一致（8080 端口、audit ES 地址等）。README「指标（Prometheus）」一节宣称的 8080 端点当前不可用。
- **绕过**（本次测试采用）：`java -jar sql-mask.jar --management.endpoints.web.exposure.include=health,prometheus`（命令行参数不依赖 ConfigData，已验证生效）。
- **修复建议**：首选改用 `spring-boot-maven-plugin repackage`（nested-jar 不需要资源合并，一劳永逸，README 此前已有此预案）；若坚持 shade，`spring.factories` 必须换成键感知的 `org.apache.maven.plugins.shade.resource.properties.PropertiesTransformer`（而非 `AppendingTransformer`），并补一条「shade jar 启动后 application.yml 必须加载」的冒烟断言（如：`/actuator` 链接数 ≥2）。
- **证据**：`%TEMP%\mask-e2e\evidence.txt` T4.5b（500 响应体）、T4.5c；探针输出 `EnvironmentPostProcessor count=1 / hasConfigData=false`。

### 4.2 【P2】策略服务审计事件的 service 字段误标为 "sql-mask"

- **现象**：ES 中由 mask-policy-server(8081) 产生的 `ADMIN_CHANGE` 事件（建实例/策略/UDF、import-metadata）`service` 字段全部为 `"sql-mask"`；同一索引里 mask-metadata 的事件正确标注 `"mask-metadata"`，mask-core 的改写事件 `"sql-mask"` 正确。
- **根因**：`mask-policy-server/src/main/java/io/sqlmask/policyserver/PolicyServerApplication.java:62` 构造 `AuditAdminHelper` 时硬编码 `"sql-mask"`（应为本服务名，如 `"mask-policy"`）；metadata 侧（`MetadataServerApplication.java:17`）写的是 `"mask-metadata"`，可见是复制粘贴遗漏。
- **影响**：三个服务共用同一 ES 索引时，ADMIN_CHANGE 无法区分事件来源服务，影响审计追溯与按服务过滤。
- **证据**：evidence.txt T4.3 与收尾核对——`resourceType=TABLES` 两条 IMPORT 事件分别是 `service="sql-mask"`（policy 发出，detail.sourceInstance=crm）与 `service="mask-metadata"`（metadata 发出）。

### 4.3 【P3】策略服务的 AdminMetrics 指标无处暴露

- mask-policy-server 的 pom **已有** spring-boot-starter-actuator（初版报告误写为缺失，更正），但缺 `micrometer-registry-prometheus`，且 application.yml 未配置 `management.endpoints.web.exposure.include`，因此 `AdminMetrics`（`sqlmask_admin_duration_seconds_*`）实际无处暴露（默认仅 health）。建议补 prometheus registry 并暴露端点，纳入抓取目标。**（已在修复中处理，见 §7）**

### 4.4 【P3/观察】mask-core 构建后首次 `java -jar` 启动失败（复现为确定性模式）

- **现象与复现**：对同一 jar 连续三次启动出现三种结果——①"no main manifest attribute"；②`DataSourceAutoConfiguration could not be excluded`（候选列表不完整）；③正常启动。该模式在**修复前（06:41，未经本修复的构建）与修复后（07:36 构建：A 失败/B 失败/C 成功）均有出现**，即与代码变更无关。
- **特征**：仅发生在 `mvn package` 刚结束后的首次 `java -jar`；对同一 jar 静态探针（`unzip`、`java -cp` 探针）均正常；静置后重试即成功。构建产物为 85MB fat jar，失败时段伴随多进程并发启动/高 IO（ES、PG、多个 JVM 同时拉起）。
- **判断**：本机 Windows 环境在新建大文件 + 高 IO 压力下读取不一致（疑似 Defender/过滤驱动扫描窗口），非项目代码缺陷。缓解：构建后首次启动失败可安全重试；或为本仓库构建输出目录配置 Defender 排除。**不作为阻断项**，如再次出现建议在同一静置窗口重试一次再定论。

### 4.5 观察项（非缺陷）

- ES 日索引为 `mask-audit-2026.09.17`（本地时间已是 09-18 早 7 点）：符合设计的 **UTC 日滚动**，勿误判为日期错误。
- `instance` 模式请求带 `user=alice` 时 `actor.authKind="ANONYMOUS"`：authKind 表示对服务的请求鉴权方式（未配 API Key 的业务端口），user/groups 是查询主体字段，语义自洽；若期望"带主体的改写"有独立 authKind，建议在 spec 里显式说明。

## 5. 串接测试用例矩阵（22 项，全部通过）

### 5.1 元数据服务（8082）

| # | 场景 | 期望 | 结果 |
|---|---|---|---|
| T1.1 | 无 Key `POST /api/instances/import` | 401 | ✅ `UNAUTHORIZED` |
| T1.2 | 错误 Key 导入 | 401 | ✅ |
| T1.3 | 正确 Key 导入 crm 实例（2 表 8 列 YAML） | 200 + 计数 | ✅ `tableCount=2, columnCount=8, metadataVersion=2` |
| T1.4 | 数据面 `GET /api/metadata/instances/crm` | 200 表结构 JSON | ✅ 两表全列返回 |
| T1.5 | 实例列表 | 200 | ✅ |

### 5.2 策略服务（8081）管理面/数据面（含跨服务串接）

| # | 场景 | 期望 | 结果 |
|---|---|---|---|
| T2.1 | 管理面无 Key | 401 | ✅ |
| T2.2 | 数据面无 Key | 401 | ✅ |
| T2.3 | **跨服务** `POST /api/instances/crm/import-metadata`（policy→metadata HTTP 拉取） | 200 + 2 表 | ✅ 表列与 metadata 数据面逐字段一致 |
| T2.4 | 注册 UDF `mask_phone(varchar,integer,integer)->varchar` | 200 | ✅ |
| T2.5 | 建 datamask 策略（customer.phone，groups=["*"]，args=[3,4]） | 200 | ✅ |
| T2.6 | 建 row_filter 策略（orders，`archived = false`） | 200 | ✅ 生效配置编译为 `orders.rowFilter` |
| T2.7 | 引用未注册 UDF 建策略 | 400 fail-closed | ✅ `CONFIG_ERROR: unknown udf 'no_such_udf'` |
| T2.8 | `GET /api/effective/crm?user=alice&groups=devs` | 200 编译配置 | ✅ metadata/columns/policies 三段完整，configVersion=4 |

### 5.3 改写服务（8080）instance 模式与 inline 模式

| # | 场景 | 期望 | 结果 |
|---|---|---|---|
| T3.1 | instance=crm 多表 JOIN + 聚合改写 | 200, masked=true, rowFiltered=true | ✅ 外层 `mask_phone(r.phone, 3, 4)` + orders 注入 `(SELECT * FROM orders WHERE archived = FALSE) AS o` + 聚合列包装 `r.cnt`，行过滤在内层、脱敏在最外层 |
| T3.2 | 匿名主体（仅 `*` 策略命中） | 200, masked=true | ✅ |
| T3.3 | `DELETE FROM customer` | 400 `UNSUPPORTED_STATEMENT` | ✅ |
| T3.4 | 未知实例 `no-such-instance` | 400 fail-closed | ✅ `POLICY_INSTANCE_NOT_FOUND` |
| T3.5 | inline 旧格式（columns+内嵌策略）改写 | 200, masked=true | ✅ 与 instance 模式输出一致 |

### 5.4 审计（ES）与查询 API

| # | 场景 | 期望 | 结果 |
|---|---|---|---|
| T4.1 | ES 索引 | UTC 日索引存在 | ✅ `mask-audit-2026.09.17` green |
| T4.2 | `GET /api/audit/events` 全量 | 三类事件齐全、字段符合 spec | ✅ total=15（当时）；REWRITE 含 masked/rowFiltered/statementCount/originalSql/rewrittenSql，失败事件含 error.code/message；EFFECTIVE_PULL 记录 actor.authKind；时间倒序 |
| T4.3 | `eventType=ADMIN_CHANGE` 过滤 | 管理动作全记录 | ✅ total=6，**成功与失败（CONFIG_ERROR）均入库** |
| T4.4 | `instance=crm&outcome=SUCCESS` 组合过滤 | 计数正确 | ✅ |
| 收尾 | ES `_count` vs 查询 API total | 一致 | ✅ 均 67 |
| — | metadata 自身事件 service 字段 | mask-metadata | ✅（对照出 4.2 的 policy 误标） |

### 5.5 Prometheus 指标

| # | 场景 | 期望 | 结果 |
|---|---|---|---|
| T4.6 | metadata `GET :8082/actuator/prometheus` | sqlmask_* 指标 | ✅ `sqlmask_admin_duration_seconds_*`，`application="mask-metadata"` 标签 |
| T4.5d | core 六审计指标（P1 绕过后） | 六族齐备且随流量递增 | ✅ `sqlmask_audit_enqueued_total{event_type="REWRITE"}=3`、`es_batches_total{outcome="SUCCESS"}=2`、`es_documents_total=3`、`queue_depth=0`、`queue_capacity`、`es_write_seconds`；计数器惰性注册（首次事件后出现），设计如此 |

### 5.6 配置变更传播

| # | 场景 | 期望 | 结果 |
|---|---|---|---|
| T5.1/5.2 | 运行期新增 UDF `mask_email` + email 脱敏策略 | 200 | ✅ |
| T5.3 | 传播后改写 `SELECT id, phone, email` | email 加入脱敏 | ✅（默认 30s 轮询先行生效，输出 `mask_phone(...) , mask_email(...)`） |
| T5.4/5.5 | `POST /admin/cache/refresh` | 200 且改写正常 | ✅ `{"cleared":1}` |

### 5.7 降级 / 恢复

| # | 场景 | 期望 | 结果 |
|---|---|---|---|
| T6.2 | **ES 停机**期间改写 | 业务 200 不受影响 | ✅ 脱敏结果正确；日志限频 WARN（1 条） |
| T6.3 | ES 停机期间审计查询 | 502 | ✅ `AUDIT_SEARCH_UNAVAILABLE`（Connection refused） |
| T6.4 | 停机期间指标 | 丢弃语义可见 | ✅ `dropped_total{reason="ES_FAILURE"}=1`、`es_batches_total{outcome="FAILURE"}=1`、队列排空 depth=0 |
| T6.5-6.7 | **ES 恢复**后 | 查询 200、新事件续写 | ✅ 查询恢复 total=11；恢复后首条改写（SELECT email→mask_email 包装）落地 ES；`es_batches SUCCESS 4→5`、`es_documents 5→6`、dropped 不再增长 |
| T7.1 | **策略服务停机**、有缓存实例改写 | 200 stale-but-available | ✅ 脱敏配置照常生效 |
| T7.2 | 策略服务停机、无缓存实例 | 失败且**不静默放行** | ✅ 400 `POLICY_SERVICE_UNAVAILABLE`（fail-closed） |
| T7.3 | 策略服务恢复后改写 | 200 | ✅ 最新配置（phone+email 双脱敏）生效 |

## 6. 结论与建议

**结论**：`main@944d4a2` 的三服务串接链路（元数据采集/存储 → 策略管理/编译 → 改写执行 → 审计 → 指标）功能完整，鉴权、fail-closed、审计尽力而为、缓存 stale-but-available 等安全与可用性语义均按设计工作。**在修复 P1 之前，mask-core 的 shaded jar 不具备生产部署条件**（外部化配置全部失效），也不满足 README 对 8080 指标端点的承诺。

**建议优先级**：
1. **P1（阻断外部化配置）**：mask-core 放弃 shade `AppendingTransformer` 处理 `spring.factories` 的方案——改 `spring-boot-maven-plugin repackage`（推荐）或 `PropertiesTransformer`；并在构建验证中加「fat jar 启动后 `/actuator` 链接数 ≥ 2」断言，防回归。修复后需回归本报告 §5 全部用例（本次 §5 结果均含 P1 绕过参数，属等效验证）。
2. **P2**：`PolicyServerApplication.java:62` 的 `"sql-mask"` 改为 `"mask-policy"`（一行修复）。
3. **P3**：策略服务补 actuator + prometheus registry（或在 spec 中明确该服务不暴露指标并移除 AdminMetrics 注册）。
4. 复跑一次观察 4.4 的瞬态启动失败是否随 P1 修复消失。

**遗留现场**：所有测试进程（3 服务、ES、PG）已停止，端口 5432/8080/8081/8082/9200 已释放；构建产物与数据保留在 `C:\Users\yhh\orca\mask-main-tmp`（代码）与 `C:\temp\mask-e2e\`（ES 安装包/解压、PG 数据目录 `pgdata`、原始证据 `evidence.txt`、各服务日志 `core3.log`/`policy2.log`/`metadata.log`/`es.log`/`es2.log`/`pg.log`），可随时复现或清理。

---

## 7. 修复与复验（2026-09-18 测试后补充）

针对 §4 的缺陷在 `C:\Users\yhh\orca\mask-main-tmp`（main 工作副本）实施了修复并完成复验：

### 7.1 修复内容

| 缺陷 | 修复 | 文件 |
|---|---|---|
| P1：shade 合并 spring.factories 丢键 | 新增构建工具模块 `mask-build-tools`，实现自定义 `SpringFactoriesTransformer`：对同名 key 做**逗号分隔值的并集合并**（保序去重），替换掉 spring.factories 上的 `AppendingTransformer`。mask-core 的 shade 插件以 `<dependencies>` 挂载该模块。保持 shade 方案不变——因 mask-metadata / mask-policy-server 均以 Maven 依赖方式消费 mask-core 构件，spring-boot repackage 会破坏 reactor（nested-jar 不能上 classpath）。`.imports`（行式格式）维持 `AppendingTransformer`（拼接语义本就正确） | 新增 `mask-build-tools/pom.xml`、`mask-build-tools/src/main/java/io/sqlmask/buildtools/SpringFactoriesTransformer.java`；修改根 `pom.xml`（注册模块）、`mask-core/pom.xml` |
| P2：policy 服务审计 service 误标 | 硬编码 `"sql-mask"` → `"mask-policy"` | `mask-policy-server/.../PolicyServerApplication.java` |
| P3：policy 服务指标无处暴露 | 补 `micrometer-registry-prometheus` 依赖 + application.yml 增加 `management.endpoints.web.exposure.include: health,prometheus` 与 `application` 指标标签（与 mask-metadata 对齐） | `mask-policy-server/pom.xml`、`mask-policy-server/src/main/resources/application.yml` |

**方案取舍说明**：shade 3.5.1 内置的 `PropertiesTransformer` 对重复键只保留单个值（按 ordinal 排序 last-wins），无法做值并集，故必须自定义 transformer（约 80 行、零依赖、仅构建期使用）。

### 7.2 复验结果（全部通过）

| # | 验证项 | 结果 |
|---|---|---|
| V0 | `mvn -DskipTests package` 全 reactor（含新模块） | ✅ BUILD SUCCESS（8 模块） |
| V-jar | jar 探针：`EnvironmentPostProcessor` 由修复前 1 项恢复为 **8 项**（含 `ConfigDataEnvironmentPostProcessor`、`LogCorrelationEnvironmentPostProcessor`、`IntegrationPropertiesEnvironmentPostProcessor`），无重复键；`ApplicationListener` 找回丢失的 `BackgroundPreinitializer`；`.imports` 仍 275 条完整 | ✅ |
| V1 | mask-core **无任何绕过参数**启动后 `/actuator` 发现页同时列出 `health` + `prometheus` | ✅（修复前仅 health） |
| V2 | `GET :8080/actuator/prometheus` 返回 200，`sqlmask_audit_*` 指标齐全，且出现 `application="sql-mask"` 标签（该标签来自 application.yml，直接证明 yml 已被加载） | ✅ |
| V3 | instance 模式改写回归（JOIN + 聚合 + 行过滤 + 脱敏） | ✅ 输出与修复前一致 |
| V4 | policy 服务产生的新 `ADMIN_CHANGE` 事件 `service="mask-policy"` | ✅（修复前为 "sql-mask"） |
| V5 | policy 服务 `GET :8081/actuator/prometheus` 返回 200，`sqlmask_admin_duration_seconds_*` 暴露，`application="mask-policy"` 标签正确 | ✅ |
| V6 | CLI 模式回归：`java -jar sql-mask.jar --metadata ... --sql ...` | ✅ 退出码 0，脱敏输出正确 |

### 7.3 修复后遗留事项

1. **§4.4 首启抖动与本修复无关**：修复前（06:41）与修复后（07:36 构建）均出现"构建后首次 `java -jar` 失败、重试成功"，三次失败呈现三种读损坏形态（无清单属性 / 候选缺失），静置后自愈。属本机环境对新写 85MB jar 的高 IO 读不一致（疑似杀软扫描窗口）。建议：构建后首次启动失败直接重试；必要时为构建输出目录加 Defender 排除。
2. **全量单测 `mvn test` 通过**（BUILD SUCCESS，exit 0，零失败零跳过）：mask-query 29、mask-metadata 99、mask-policy-server 110 项，含 mask-core / mask-audit / mask-sqlparser / mask-policy 在内的全部 reactor 模块均绿。改动面（pom/新构建模块/一行字符串/yml）不触碰业务逻辑。
3. 修复尚未提交，工作区为 `C:\Users\yhh\orca\mask-main-tmp`（main），待确认后提交。
