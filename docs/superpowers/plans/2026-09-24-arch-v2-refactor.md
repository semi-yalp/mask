# arch-v2 模块化单体重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 5 微服务 + 12 模块重组为"模块化单体 + 可嵌入内核"架构，落地认证可选化、分类分级、UDF 中心、统一授权、查询 Submitter SPI、改写失败放行开关、前端重构与一键部署。

**Architecture:** mask-engine 内核保持纯库不变；mask-core 溶解、四个服务应用降级为域库；新 mask-server 单体组合全部域 + 版本化编译快照（RewriteContextRepository）使改写热路径零 HTTP；存储 PG 默认 / H2 文件回退；认证 none(默认)/simple/ldap 多模式；前端单源 /api 重构。

**Tech Stack:** Java 17 / Spring Boot 3.3.5 / Calcite 1.42.0 / PostgreSQL 16 / H2 2.x / Vue3 + Element Plus + Vite / docker compose。

**Spec:** `docs/architecture-evolution-20260924.md`（设计）+ `docs/baseline-inventory-20260924.md`（基线）。

## Global Constraints

- mask-engine 禁止 Spring/picocli 编译期依赖（enforcer `enforce-kernel-purity` 必须继续通过）。
- 改写内核语义不变：fail-closed、行过滤白名单、字节级 golden 全部保持通过（`mvn verify` 绿）。
- 默认无认证即可完整使用全部功能；认证开启为可选增强。
- 数据库 DDL 必须同时兼容 PostgreSQL 与 H2（PG 模式）：TEXT 存 JSON、无 `::jsonb`、无 PG 专有类型。
- 所有既有 REST 端点路径保持不变（前端与 e2e 脚本兼容），新增端点另立路径。
- 每个 Task 结束 `mvn -q -pl <module> -am verify`（或全量）通过并 git commit。

## File Structure（目标模块拓扑）

```
mask-sqlparser        不变
mask-policy           不变（纯模型）
mask-engine           不变（内核；+UdfIntrospector 扩展点）
mask-common           瘦身：ApiError/BaseApiExceptionHandler/EffectiveConfigResponse/MetadataClient/metrics；ApiKeyFilter 删除
mask-auth             升级：AuthProvider 多模式（none/simple/ldap）+ SimpleUserStore
mask-audit            升级：+JdbcAuditRecorder +进程内 RiskSink
mask-metadata         域库（去 Application）+分类分级域
mask-policy-admin     ← mask-policy-server 改名（去 Application）+UDF 中心域
mask-query            域库（去 Application）+Submitter SPI+放行开关
mask-risk             ← mask-risk-server 改名（去 Application）
mask-server           新：单体应用 + CLI + RewriteContextRepository + 授权域 + 静态前端
mask-build-tools      不变
frontend              重构（单源 /api）
bench/                从 feat/ldap-auth 恢复并适配
deploy/docker-compose.yml + docker/server.Dockerfile + docs/deployment.md
```

---

### Task 1: 仓库卫生——清理已提交的合并冲突标记

**Files:** Modify `frontend/.gitignore`、`docker/nginx.Dockerfile`
**Steps:**
- [ ] 去掉两文件中的 `<<<<<<<`/`=======`/`>>>>>>>` 标记（nginx.Dockerfile 取双方等价注释的合并版）
- [ ] `grep -rn "<<<<<<< HEAD" --exclude-dir={target,node_modules,.git} .` 零命中
- [ ] commit `chore: 清理已提交的合并冲突残留`

### Task 2: 模块重组——创建 mask-server，溶解 mask-core，降级四个服务应用

**Files:**
- Create: `mask-server/pom.xml`、`mask-server/src/main/java/io/sqlmask/server/SqlMaskApplication.java`（双模：无参 Web / 带参数 CLI）
- Move: mask-core 的 `io.sqlmask.server.*`、`io.sqlmask.cli.*`、`io.sqlmask.config.source.*` → mask-server 同包
- Delete: `mask-core/pom.xml` 与模块目录；四个服务模块的 `*Application.java`、`src/main/resources/application.yml`
- Rename: `mask-policy-server` → `mask-policy-admin`（git mv，artifactId 同步）；`mask-risk-server` → `mask-risk`
- Modify: 根 `pom.xml` 模块列表；各域库 pom：去掉 spring-boot-maven-plugin repackage 与独立 actuator，保留依赖
- Create(每域库): `src/test/java/.../TestApp.java`（@SpringBootConfiguration + @EnableAutoConfiguration + 组件扫描），全部 `@SpringBootTest(classes=XxxApplication.class)` 改指 TestApp
- Modify: 各服务里 Filter 注册（ApiKeyFilter/BearerAuthFilter 的 @Bean 注册）整体移除（认证统一到 mask-server Task 5）；risk 的 RiskApiFilter 注册移除

**Interfaces:**
- Produces: 单一可启动应用 `io.sqlmask.server.SqlMaskApplication`；组件扫描根 `io.sqlmask`；四个域库的包结构不变
- 注意：`mask-server/pom.xml` 用 spring-boot-maven-plugin（主类 SqlMaskApplication），依赖 = 全部域库 + mask-auth + mask-common + picocli

**Verify:** `mvn -q verify` 全绿（此时 mask-server 仅组合、未加新功能；RewriteController 的 instance 模式暂时仍走 HTTP 配置源）；commit。

### Task 3: 统一存储——PG 默认 / H2 回退，schema 可移植化

**Files:**
- Modify: `mask-metadata/src/main/resources/metadata-schema.sql`、`mask-policy-server(→admin) .../schema.sql`：JSONB→TEXT，去 PG 专有语法
- Create: `mask-server/src/main/resources/schema-server.sql`：auth_user、audit_event、classification、grant、query_submit 相关表
- Modify: `JdbcMetaStore`/`JdbcPolicyStore`：去 `::jsonb` 写法
- Create: `mask-server/src/main/java/io/sqlmask/server/storage/StorageAutoConfiguration.java`：有 `MASK_STORAGE_PG_URL` → PG DataSource；否则 H2 文件 `./data/sqlmask`（JDBC URL `jdbc:h2:file:./data/sqlmask;MODE=PostgreSQL;AUTO_SERVER=TRUE`）
- Modify: `mask-server/src/main/resources/application.yml`：`spring.sql.init.schema-locations` 聚合各域 schema
- Test: `mask-server/src/test/.../storage/H2BootSmokeTest.java`（H2 起 context，建表成功，meta/policy store 读写各一条）

**Interfaces:**
- Produces: 单一 DataSource bean；环境变量 `MASK_STORAGE_PG_URL/USER/PASSWORD`（缺省 H2）；各域 store 构造注入统一 DataSource

**Verify:** `mvn -q -pl mask-server -am verify` + 手动 `java -jar` H2 模式起服 curl /actuator/health；commit。

### Task 4: RewriteContextRepository——改写热路径进程内化

**Files:**
- Create: `mask-server/src/main/java/io/sqlmask/server/rewrite/RewriteContext.java`（不可变：instanceName、dialect、TableMetadata[]、compiled policies、udf 签名、configVersion、metadataVersion）
- Create: `mask-server/.../rewrite/RewriteContextRepository.java`：
  ```java
  public RewriteContext get(String instance);            // 命中缓存直接返回
  public void invalidate(String instance);               // 变更时调用
  public void invalidateAll();
  // 编译：MetadataService 快照 + EffectiveConfigCompiler 输出 → LoadedConfig 等价物 → mask-engine RewriteEngine 入参
  ```
- Modify: `InstanceRewriteController` 改用 repository（去掉 PolicyServiceConfigSource/InstanceQueryAssembler 的 HTTP 路径，保留类用于 CLI）
- Modify: mask-metadata 的 `StructureService`、mask-policy-admin 的 `PolicyService/UdfService` 在任意变更后调 `invalidate`（Spring 事件或直接注入，域库间用接口回调 `io.sqlmask.common.rewrite.RewriteInvalidation` 解耦方向：域库只依赖 mask-common 中的接口，mask-server 实现并发布事件）
- Test: repository 单测（变更→版本递增→新上下文；未变更→同引用）；InstanceRewrite 集成测试改造（不再 mock HTTP）

**Verify:** `mvn -q verify`；e2e：起服后创建实例+策略，POST /api/rewrite/instances/{name} 全链路（H2+内存 store）；commit。

### Task 5: 认证可选化——none(默认)/simple/ldap

**Files:**
- Modify: `mask-auth`：新增 `AuthMode`（NONE/SIMPLE/LDAP）、`AuthProvider` 接口（authenticate(name,pwd)→AuthResult）、`SimpleAuthenticator`（PBKDF2WithHmacSha256，javax.crypto，无新依赖）、`SimpleUserStore` 接口；LdapAuthenticator 实现 Provider
- Create: `mask-server/.../auth/UserAdminController.java`（simple 模式用户 CRUD，ADMIN 角色）；首启自动建 `admin/admin`（日志强提示修改密码）
- Create: `mask-server/.../auth/AuthEndpoints.java`：`POST /api/auth/login`（simple|ldap 统一）、`GET /api/auth/me`、`GET /api/auth/mode` → `{mode:"none"|"simple"|"ldap"}`（兼容旧 `ldap` 布尔字段）
- Modify: 删除 `ApiKeyFilter` 及其测试/全部引用；BearerAuthFilter 仅在 mode!=NONE 时注册；角色规则（AuthRule）沿用
- Modify: 审计 authKind：NONE 时 ANONYMOUS
- Test: none 模式全端点免认证 smoke；simple 登录/错密码/角色；ldap 既有测试保持

**Verify:** `mvn -q verify` + curl 三模式冒烟；commit。

### Task 6: 查询网关——Submitter SPI + 改写失败放行开关

**Files:**
- Create: `mask-query/src/main/java/io/sqlmask/query/submit/QuerySubmitter.java`：
  ```java
  public interface QuerySubmitter {
      String type();                                     // "jdbc" | "http" | ...
      QueryResult submit(SubmitRequest req) throws QueryException;
  }
  public record SubmitRequest(String instance, String engine, ConnectionInfo conn, String sql,
                              int maxRows, int timeoutSeconds, CancelRegistry cancel) {}
  ```
- Modify: 现有六引擎执行器归入 `JdbcQuerySubmitter`（逻辑平移，不减护栏）；Create `HttpQuerySubmitter`（POST `${sql}` 进 bodyTemplate，JSON path 取列/行，用于带 HTTP SQL API 的引擎）
- Create: `SubmitterRegistry`（类型注册 + 默认 jdbc）；实例连接信息增加 `submitter` 字段（缺省 jdbc）
- Modify: `QueryService`：走 registry；**放行开关**——实例级 `onRewriteFailure: REJECT|PASSTHROUGH`（存 mask-metadata 实例表新列，默认 REJECT）：改写抛错且 PASSTHROUGH 时以原 SQL 继续，响应标 `rewriteBypassed:true`、审计 detail 记 bypass、指标 `sqlmask.query.rewrite_bypass_total`
- Test: PASSTHROUGH 开关两态集成测试（mock 改写失败）；HttpSubmitter 单测（内嵌 HttpServer）

**Verify:** `mvn -q -pl mask-query,mask-server -am verify`；commit。

### Task 7: 分类分级域（元数据）

**Files:**
- Create: `mask-metadata/.../classification/ClassificationService.java`、`Classification.java`（record: instance、columnKey(catalog.schema.table.column)、category、level、source(MANUAL|AUTO)、note、updatedAt）、`ClassificationTaxonomy.java`（内置类别 IDENTITY/PII/CONTACT/FINANCE/LOCATION/MEDICAL/OTHER × 级别 HIGH/MEDIUM/LOW + 名称启发式规则表：phone|mobile|tel→CONTACT/HIGH 等 20+ 模式）
- Create: `JdbcClassificationStore`（表 meta_classification）+ schema 增列
- Create: `ClassificationController`：`GET /api/instances/{i}/classification`（列表+过滤）、`PUT .../classification/{columnKey}`、`DELETE`、`POST .../classification/auto`（按启发式批量识别，返回新增/变更数）、`GET /api/classification/overview`（全局统计：按级别/类别/实例分布、未分类列数）
- Test: 启发式命中/不误伤用例、store CRUD（embedded PG）、auto 幂等

**Verify:** `mvn -q -pl mask-metadata -am verify`；commit。

### Task 8: UDF 中心——发现/部署/动态同步

**Files:**
- Create: `mask-engine/.../introspect/udf/UdfIntrospector.java`（接口：List<UdfSignature> introspect(ConnectionSpec)）+ `PgUdfIntrospector`（查 `pg_proc`/`pg_get_function_identity_arguments`/`prorettype`，排除系统 schema）、`MysqlUdfIntrospector`（information_schema.ROUTINES/PARAMETERS）；注册进现有 introspector 注册表模式；hive/trino 抛 UNSUPPORTED（ENOTSUP 语义）
- Create: `mask-policy-admin/.../udf/UdfCenterService.java`：
  - `importFromEngine(instance)`：经实例连接发现签名 → upsert 注册表（source=IMPORTED，记 lastSyncedAt）
  - `deployBuiltIn(instance, templateName)`：内置模板（mask_phone/mask_email/mask_name/mask_idcard/mask_text，PG plpgsql 版，参考 deploy/local-e2e/01_udf.sql 移植）→ 在目标引擎执行 CREATE OR REPLACE FUNCTION → 再 import 刷新
  - `deployCustomDdl(instance, ddl)`：任意 DDL 确认执行（幂等性由用户负责，响应返回执行结果）
  - `resync(instance)`：引擎真相 vs 注册表 diff（added/removed/changed 签名）
- Create: `UdfCenterController`：`POST /api/instances/{i}/udfs/import`、`POST .../udfs/deploy`、`POST .../udfs/resync`、`GET .../udfs/templates`（内置模板目录）
- UdfDefinition 模型加 `source`、`lastSyncedAt` 字段（schema 增列，默认 REGISTERED）
- Test: embedded-PG：真建函数→introspect→导入→签名一致；resync 检测函数变更

**Verify:** `mvn -q -pl mask-engine,mask-policy-admin -am verify`；commit。

### Task 9: 元数据层统一授权（grants v1）

**Files:**
- Create: `mask-server/.../grant/GrantModel.java`（record GrantEntry: principalType(USER|GROUP)、principal、resourceType(CATALOG|SCHEMA|TABLE|COLUMN)、resourceId、privilege(SELECT|INSERT|UPDATE|DELETE|ALL)、grantedBy、createdAt）
- Create: `JdbcGrantStore`（表 grant_entry）+ `GrantService` + `GrantCompiler`：按方言生成 DDL——PG：`GRANT SELECT ON TABLE schema.t TO role/user`（组→先建组角色 `CREATE ROLE g_xxx`）；MySQL：`GRANT SELECT ON db.* TO user@'%'`（需引擎用户存在，仅生成不执行用户创建）；hive/sparksql/trino：生成对应 GRANT 文本（v1 仅预览）
- Create: `GrantController`：CRUD + `GET /api/instances/{i}/grants/preview`（编译 DDL）+ `POST .../grants/apply`（可选执行，走实例连接，需确认头）+ `GET /api/grants/matrix?principal=`（主体×资源有效权限矩阵）
- Create: `mask-query` 可选预检：实例开关 `enforceGrants`（默认 false）开启时，SELECT 查询前用 FROM/JOIN 表提取（复用风控 SqlFeatureExtractor 的提取逻辑移至 mask-common）比对 grant，无权限→403 PERMISSION_DENIED
- Test: 编译器方言输出快照测试；预检开关两态；store CRUD

**Verify:** `mvn -q verify`；commit。

### Task 10: 审计 Jdbc 存储与风控进程内化

**Files:**
- Create: `mask-audit/.../jdbc/JdbcAuditRecorder.java`（批量写 audit_event 表，异步同 ES 版语义：有界队列+批量+丢数计数）+ `JdbcAuditSearchClient`（实现既有 AuditSearch 接口：过滤/时间范围/分页 SQL 版）
- Modify: `AuditAutoConfiguration`：`audit.store=jdbc(默认)|es|none`；`risk.forward` 进程内模式——新增 `RiskIngestSink` 接口（mask-audit 定义），mask-risk 实现直调 RiskEngine.ingest（去 HTTP RiskForwarder，保留 HTTP 实现给独立部署场景但默认 inproc）
- Modify: `mask-risk`：`risk.demo.seed-on-start` 默认 false；BlockService 改调进程内 PolicyService（去 HTTP base-url）；保留 JSON 文件持久化（记录 PG store 为后续项）
- Test: Jdbc recorder/search 单测（H2）；inproc 转发集成测试（audit 事件→risk 命中）

**Verify:** `mvn -q verify`；commit。

### Task 11: 指标补齐与清理

**Files:**
- Modify: `RewriteMetrics` 方言标签白名单扩至注册表全集（从 DialectRegistry 动态取）
- Modify: mask-query 关键路径埋点（submit duration/rows/errors/bypass）；mask-risk ingest/alert 埋点（micrometer，无 actuator 依赖）
- mask-server 暴露 /actuator/prometheus（含 health）
- Test: 指标名冒烟

**Verify:** `mvn -q verify` + curl /actuator/prometheus | grep sqlmask；commit。

### Task 12: 前端重构（单源 /api + 响应式 + 新页面）

**Files:**
- Modify: `frontend/vite.config.ts`（dev 代理全量 → 127.0.0.1:8080，单源）；`frontend/src/api/http.ts`（去 API Key，Bearer 可选）；`frontend/src/stores/auth.ts`（mode: none/simple/ldap 三态）；登录页支持 simple
- Modify: `frontend/src/styles/theme.scss`（统一设计令牌：色板/间距/圆角/阴影一套 CSS 变量，risk 硬编码色并入）；`ConsoleLayout.vue`（响应式侧边栏：<1024px 折叠为图标栏；顶栏面包屑）
- Modify: 全部页面 `el-col` 加 `:xs/:sm` 响应式断点；抽屉/dialog 宽度改 max-width 百分比
- Fix: QueryConsole 实例禁用 bug（列表接口补 connection 摘要字段或复用详情）、MetadataManager N+1（后端列表返回 connection 概要）
- Modify: 菜单重组：总览 / 数据资产（元数据+分类分级）/ 策略中心（策略+UDF+授权）/ 数据查询（查询台+试验台）/ 风险 / 审计 / 设置（认证模式+用户管理+引擎连接）
- Create: `views/classification/ClassificationView.vue`（统计卡+分布图+按实例列表明表+批量 auto 识别+手工编辑弹窗）；PolicyManager 列选择器显示分类标签
- Modify: `views/policymanager/UdfsTab.vue` → UDF 中心增强（来源徽标/导入/部署模板/resync diff 展示）
- Create: `views/grants/GrantsView.vue`（授权矩阵+新增授权+DDL 预览/执行）
- Create: `views/settings` 重做（认证模式展示、simple 用户管理、提交器配置说明）
- Test: 既有 vitest 适配 + 新 store/页面核心逻辑测试

**Verify:** `npm test && npm run build` 通过；本地起服手工走查关键页面（截图留档）；commit。

### Task 13: TPC-DS bench 恢复与适配

**Files:**
- `git checkout feat/ldap-auth -- bench/`（恢复全量资产）
- Modify: `bench/tpcds-mask-lite/run-case.sh` 等：引擎从 lite/core 二选一改为 `sqlmask`（新 jar 的 CLI 路径 `java -jar mask-server*.jar --cli --metadata ... --policies ...`）；README 更新口径；lite 相关行列退役
- [ ] 抽样 10 条查询跑通三模式（mask/rowfilter/both）+ summarize 出矩阵；round-trip 全过
- [ ] 记录 99 条全量结果（本地能跑则跑，跑不完记录命令与抽样结果）

**Verify:** `bench/tpcds-mask-lite/run-bench.sh 1` 抽样通过；commit。

### Task 14: 部署——一键 compose + 详细文档

**Files:**
- Create: `docker/server.Dockerfile`（多阶段：node 构建 frontend/dist → maven 构建 → temurin17 运行，dist 进 jar classpath:/static）
- Modify: `mask-server` 静态资源：`spring.web.resources.static-locations` 追加 `classpath:/static/`（SPA fallback 到 index.html）
- Create: `docker-compose.yml`：默认 postgres + sqlmask(+前端)；profiles：`full`（+elasticsearch+prometheus）、`ldap`（+openldap）
- Create: `docs/deployment.md`：环境变量全表、三种部署形态（compose 一键 / 裸 java -jar H2 / 远程 PG）、认证开启步骤、升级与备份、故障排查
- Delete/归档: 旧 5 个分服务 compose 移至 `deploy/legacy/`（保留历史）
- [ ] 本机 `docker compose up -d` + e2e 冒烟（参照 deploy/e2e-verify.sh 改造为 `deploy/e2e-verify-v2.sh`，指向 8080 单体）

**Verify:** compose 起服 → 前端可开 → 改写/查询/审计全链路 curl 断言通过；commit。

### Task 15: 文档与收尾

**Files:**
- Rewrite: `README.md`（新架构、快速开始、端口/环境变量）
- Create: `docs/功能清单-v2.md`（重构后功能清单，对照 baseline）
- Create: `docs/known-issues-v2.md`（未实现/需确认项清单）
- [ ] 全量 `mvn verify` + `npm test` + `npm run build` 终验

### Task 16: 基线回归测试

- [ ] 对照 `docs/baseline-inventory-20260924.md` §3 逐项勾验（改写内核五方言 golden、行过滤、策略三来源、实例模式、CLI、审计、风控、认证）
- [ ] e2e-verify-v2.sh 全过；嵌入式 PG E2E（QueryEndToEnd）过
- [ ] 远程 PG 真机验证：ssh 47.100.166.158（凭据存在则执行 deploy/pg-setup + e2e；不可达则记录待办）

### Task 17: 合并推送与最终报告

- [ ] `git checkout main && git merge feature/arch-v2`（无 ff 或 ff 视情况）
- [ ] `git push origin main`
- [ ] 输出：功能清单-v2 报告（聊天+docs）+ 推送后项目问题分析报告（docs/post-push-issues-20260924.md）

## Self-Review 结论

- 覆盖检查：用户 17+ 点需求 ↔ T2(拆分/部署)、T3(本地运行)、T4(策略不影响改写)、T5(认证可选/简单认证/新模块)、T6(轻查询/提交方式注册/放行开关)、T7+T12(分类分级+前端)、T8(UDF)、T9(统一授权)、T10(风控重构自定)、T11(指标)、T12(前端美观)、T13(TPC-DS)、T14(部署文档/远程验证材料)、T15(清单报告)、T16(完备测试/远程PG)、T17(推送+最终报告)。关键字 TOP/INSERT OVERWRITE：解析器已支持，T6 实例级方言能力开关补 TOP 接线（新增到 T6 步骤：实例 `dialectFeatures` 覆盖 topN/insertOverwrite，传递至 DialectRegistry 创建 profile 覆盖）。
- 类型一致性：RewriteContextRepository/QuerySubmitter/GrantEntry/Classification 命名在任务间一致。
- 风险点：T2 测试迁移量大（机械）；T4 需保 CLI 兼容；T13 bench 依赖 DuckDB 本机可用性（不可用则降级为 round-trip）。
