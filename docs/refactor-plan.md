# sql-mask v2 重构方案（Refactor Plan）

> 状态：定稿 v1（2026-09-24）。本文档是本轮大刀阔斧重构的完整方案与实施计划，
> 基线为 `full-test` 分支（含未提交的前端控制台重构、CI 雏形、PSRV-KY-001 修复）。
> 重构成果将发布至新仓库 `semi-yalp/sql-mask`（保留完整 git 历史）。

## 0. 方针：与 v1-goals 的关系

`docs/v1-goals.md`（2026-09-23）规划了"纯改写工具"方向，其中对现状的两条架构批评
完全成立，本轮全盘采纳：

1. **改写内核与服务平台耦合**：rewrite/dialect/rowfilter 与 Spring Boot Web/CLI 混装在
   mask-core 单模块内；
2. **微服务间依赖方向混乱**：mask-metadata 为用 4 个类依赖整个 mask-core（连带 Web
   应用与 CLI）；mask-policy-server 直接 import 另一服务的 web 层类。

但 v1-goals 把"4 微服务 + 前端控制台"整体封存的取舍，与当前需求（**前端功能完整、
平台继续演进**）冲突。本轮的裁决是：

- **采纳 v1 的解耦架构**：内核抽离为 Spring-free 的 `mask-engine` 模块——这正是
  mask-lite 想做而没做成的"无 Spring 库化"，抽离后 mask-lite 失去存在意义，退役；
- **不采纳"封存平台"**：4 个微服务保留并修正依赖方向，前端控制台补齐为功能完整形态；
- 主体维度（users/groups 动态策略）维持现状能力不回退，v2 演进留给后续。

## 1. 现状诊断（证据）

### 1.1 后端

| # | 问题 | 证据 |
|---|---|---|
| B1 | **ApiKeyFilter 复制 5 份、3 种形态、2 种语义**（fail-open vs fail-closed），同一常量时间比较函数与同一内联 JSON 错误串复制 5 次 | `mask-core/.../server/PolicyApiKeyFilter.java`、`mask-policy-server/.../web/PolicyApiKeyFilter.java`、`mask-metadata/.../config/ApiKeyFilter.java`、`mask-query/.../web/QueryApiKeyFilter.java`、`mask-core/.../server/InstanceRewriteApiKeyFilter.java` |
| B2 | **依赖方向错误**：mask-metadata 依赖整个 mask-core 只为 `introspect`/`dialect.DialectProfiles`/`error.SqlMaskException`；mask-policy-server import mask-core 的 `server.EffectiveMetrics`/`server.RewriteMetrics`/`config.source.EffectiveConfigResponse`/`metadataclient.MetadataClient`（共 10 个文件） | 见上述各 import |
| B3 | **错误体 4 种形状**：core/policy-server `{code,message}`；metadata `{code,message,details}`；query 裸 `Map.of(...)`；5 个 filter 手写第三种 | 4 个 `@RestControllerAdvice` |
| B4 | **死代码**：mask-core `SqlMaskServiceApplication` 注册的 `/api/instances`、`/api/effective` filter 分支及启动告警——这两个面早已迁到 policy-server，core 上无对应 controller | `SqlMaskServiceApplication.java` |
| B5 | **层次违规**：mask-policy 是"库"却声明 spring-boot-starter-web，只因内含一个为 mask-core 组件扫描便利而存在的 `PolicyController` | `mask-policy/pom.xml` |
| B6 | **mask-lite 是 mask-core 内核 ~3600 行的改名 fork**（lineage/rewrite/rowfilter/sql/metadata/config/dialect 一一对应），修复要打两遍（RowFilterRegistry 已分叉：core 220 行 vs lite 201 行） | `mask-lite/src` |
| B7 | 服务间 HTTP 客户端手写 3 份（各自解析 JSON 错误） | `MetadataClient`、`MetadataServiceClient`、`RewriteServiceClient` |
| B8 | 无任何静态分析/依赖约束强制（无 checkstyle/spotless/enforcer） | 根 pom |

### 1.2 前端（`frontend/`，Vue3 + Element Plus，~2700 行）

| # | 问题 | 证据 |
|---|---|---|
| F1 | **生产 nginx 未代理 `/api/rewrite`**（两份 nginx conf 均注释掉）——Playground 在生产部署下直接打到 SPA 回退，**只有 dev 能用** | `frontend/nginx.conf`、`nginx.conf.docker` |
| F2 | **功能覆盖不全**：后端 4 服务 5 张 API 面，前端只覆盖 policy-server（8081）与审计（8080）；**mask-metadata 管理面（8082 实例 CRUD/在线采集/YAML 导入/版本）与 mask-query 数据面（8083 `/api/v1/query` 受控查询）完全没有 UI** | `src/api/*` 仅 6 个文件 |
| F3 | 无表单校验：`el-form` 不带 `:model`/`:rules`，手写非空 trim；`priority` 用 `parseInt` 无 NaN 防护 | `PoliciesTab.vue` 等 |
| F4 | 无分页（仅审计有服务端分页）、无加载态（`TablesTab` 字面量 `v-loading="false"`、`UdfsTab` 无 loading） | 各 Tab |
| F5 | 删除无确认（TablesTab 行删除）、方言列表硬编码 2 处、示例实例内联在组件里、元数据导入地址硬编码 `127.0.0.1:8082` | `AccessManager.vue`、`TablesTab.vue` |
| F6 | 无 401/门禁失效处理（错误仅以字符串弹出）；Dashboard"近期实例"只是 list 前 6 个 | `http.ts`、`Dashboard.vue` |
| F7 | 测试仅 3 个（http 包装、settings store、审计查询构造），无组件/视图测试 | `frontend/tests/` |
| F8 | `deploy.sh` 内嵌生产主机 `root@47.100.166.158` 作默认值 | `frontend/deploy.sh` |
| F9 | 旧版 853 行单文件 `policy-console.html` 仍随包发布（"应急入口"），与功能完整的新控制台并存造成双入口 | `frontend/policy-console.html` |

### 1.3 工程与发布

| # | 问题 |
|---|---|
| P1 | CI（`.github/workflows/ci.yml`）只跑 `mvn -B package`（刻意不跑 test）+ 前端只跑 vitest 不构建 |
| P2 | 仓库根 ~35 个游离日志文件（`t4.log`…`t15.log`，未跟踪）；`.worktrees/` 多个历史工作树 |
| P3 | README 与重构后结构、前端能力不匹配（写于单页控制台时代的大量描述需更新） |

## 2. 目标架构

### 2.1 后端模块拓扑（重构后）

```
mask-parser          Calcite 定制解析（原 mask-sqlparser，不改）
mask-engine          纯内核库：dialect / rewrite / rowfilter / lineage / metadata /
                     introspect / config(YAML) / error —— 零 Spring、零 picocli、零 JDBC 驱动
mask-common          服务面共享件：统一 ApiKeyFilter / ApiError(code,message,details) /
                     EffectiveConfigResponse / MetadataClient / EffectiveMetrics / RewriteMetrics
mask-audit           ES 审计库（不改）
mask-policy          策略匹配库（摘除 Spring controller 后变纯库）
mask-core            应用：Spring Boot Web(8080) + CLI（瘦身为 server/cli/config.source，
                     依赖 engine+policy+common+audit）
mask-policy-server   应用(8081)：依赖 policy+engine+common+audit（脱离 mask-core）
mask-metadata        应用(8082)：依赖 engine+common+audit（脱离 mask-core）
mask-query           应用(8083)：依赖 common+audit（本就仅 HTTP 调 core）
mask-build-tools     shade transformer（保留，core fat jar 需要）
```

依赖铁律（用 maven-enforcer 落地）：

- `mask-engine` **禁止**声明 Spring / Spring Boot / picocli 依赖（BannedDependencies
  按 compile/runtime/provided 作用域精确禁止；test 作用域放行——内核的真实 PG
  测试需要 spring-jdbc/JdbcTemplate；内核主代码经全量扫描确认零 Spring 引用）；
- 应用模块（core/policy-server/metadata/query）**禁止**互相依赖；
- `mask-common` 依赖 mask-engine（使用内核的 `SqlMaskException` 错误契约与
  StatementRewrite 类型）与 mask-audit，属"服务面共享件"；mask-query 为统一
  错误体/过滤器引入 common，engine 随之传递进入其 classpath（惰性存在，
  query 自身代码仅经 HTTP 调用 core）。

### 2.2 统一安全过滤器与错误体

- 一个 `io.sqlmask.common.web.ApiKeyFilter`（builder 配置：header 名、key 集、
  **未配置 key 时 fail-open / fail-closed 显式声明**、放行路径），常量时间比较与
  统一 401 错误体只写一遍。各服务语义保持现状：core/policy-server fail-open
  （本地开发友好），metadata/query fail-closed（README 明文的既有契约）；
- 一个 `io.sqlmask.common.web.ApiError(code, message, details)` + 共享
  `BaseApiExceptionHandler`（按 code→HTTP status 映射），四个服务的错误体统一为
  `{code, message, details[]}`（metadata/query 现有形状，README 已文档化；
  core/policy-server 的 `{code,message}` 平移补 `details: []`，前端按 code 处理不受影响）；
- mask-core 的死 filter 分支（/api/instances、/api/effective）与误导性启动告警删除。

### 2.3 前端信息架构（功能完整形态）

```
/                      总览：实例/表/策略/UDF 计数、门禁状态、快速开始、近期实例
/access-manager        访问管理：policy 实例网格（5 方言分组）+ 新建/删除/示例
/metadata-manager      【新】元数据服务：实例 CRUD（engine/host/port/db/user/passwordRef）、
                       在线采集（collect）、YAML 导入、表结构与版本查看
/policy-manager/:name  策略管理器：策略 / 表结构 / UDF / 生效配置 四页签（全面打磨）
/query-console         【新】数据面查询：选实例 + 查询主体 + maxRows，POST /api/v1/query，
                       结果表格、masked/rowFiltered/truncated 徽标、改写 SQL 查看、本地历史
/playground            改写试验台：instance 模式 + 内联 YAML 模式 + 【新】policies.yaml
                       模式（user/groups 主体），方言选择
/audit                 审计：五页签 + 筛选 + 分页（既有，打磨时间格式）
/settings              设置：双 API Key + 服务拓扑 + 【新】各服务连通性探测
```

工程性补齐：`el-form` 规则化校验（名称格式、端口范围、priority 整数、参数列表）、
全列表客户端分页 + 关键词过滤、统一加载态、删除全确认、401 统一拦截（提示配置 Key
并引导到设置页）、方言/引擎常量收敛到 `src/constants`、示例实例抽出为独立模块、
`formatTime` 用 `Intl.DateTimeFormat`。

### 2.4 网关路由设计（解决路径冲突）

`/api/instances` 同时存在于 policy-server(8081) 与 metadata-service(8082)，路径无法
区分。约定网关前缀（nginx 与 vite dev 代理同构）：

| 前端路径 | 转发目标 | 说明 |
|---|---|---|
| `/api/rewrite`、`/api/config`、`/api/policies`、`/api/audit`、`/api/metadata/pull` | 8080 mask-core | 由 `/api/` 兜底 location 覆盖（最长前缀匹配） |
| `/api/instances/**`、`/api/effective/**` | 8081 policy-server | 原样（策略域实例） |
| `/api/meta/**` | 8082 `/api/**` | **新增**：元数据服务管理面/数据面（`/api/meta/instances` → 8082 `/api/instances`） |
| `/api/v1/**` | 8083 mask-query | 启用（修 F1） |

前端 api 层新增 `src/api/meta.ts` 统一走 `/api/meta/` 前缀；后端零改动。

### 2.5 CI 与发布

- CI 后端：`mvn -B verify`（test + package + enforcer 约束全跑）；
- CI 前端：`npm ci || npm install` + `vitest run` + `vue-tsc && vite build`；
- `deploy.sh`：生产主机从环境变量 `DEPLOY_HOST`/`DEPLOY_ROOT` 读取，**未设置即报错退出**，
  不再内置默认主机；
- 删除 `frontend/policy-console.html`（功能完整的新控制台取代应急页；localStorage Key
  `mask-policy-console-keys` 沿用兼容）；
- 新仓库：`semi-yalp/sql-mask`，以重构分支推送为 `main`，**保留全部 git 历史**；
  旧仓库 `semi-yalp/mask` 不动（封存只读）。

## 3. 实施顺序与提交切分

| # | 提交 | 内容 | 验收 |
|---|---|---|---|
| 1 | `chore: 基线落盘` | 未提交的前端控制台重构 + CI + 测试计划 + PSRV-KY-001 | 后端 mvn test、前端 vitest 全绿 |
| 2 | `refactor(common): 统一 ApiKeyFilter 与错误体` | mask-common 模块 + 5 filter 收敛 + 4 advice 收敛 + 死分支删除 | 全量 mvn test |
| 3 | `refactor(engine): 内核抽离 mask-engine` | 71 个内核类迁移（包名不变）+ enforcer 禁 Spring + mask-core 瘦身 + policy-server/metadata 改依赖 | 全量 mvn test |
| 4 | `refactor: mask-lite 退役` | 删除 mask-lite 与 bench/tpcds-mask-lite（内核抽离后其使命由 mask-engine 承接） | 聚合构建绿 |
| 5 | `feat(fe): 元数据服务管理 + 数据面查询控制台` | 新视图 + api 层 + nginx/vite 路由 | vitest + build |
| 6 | `feat(fe): 既有视图全面打磨` | 校验/分页/加载/确认/401/常量收敛/示例抽出 | vitest + build |
| 7 | `feat(fe): Playground 增强` | policies.yaml 模式 + 方言选择 | vitest + build |
| 8 | `chore(ci,docs): CI 升级 + README 重写 + deploy 去硬编码` | — | CI YAML 合法、README 与结构一致 |
| 9 | 发布 | 新建 `sql-mask` 仓库推送 main | 远端可见 |

## 4. 验收标准

1. `mvn -B verify` 全绿（≈130 个测试类，含嵌入式 PG 全链路 `QueryEndToEndTest`）；
2. `mask-engine` 的 jar 依赖树中无 Spring/picocli/JDBC 驱动（enforcer 保证）；
3. 应用模块间零互相依赖（enforcer 保证）；
4. 前端 `vitest run` 全绿且用例数 ≥ 15；`vue-tsc --noEmit` + `vite build` 通过；
5. nginx 生产配置代理面覆盖前端全部 api 调用（`/api/meta/`、`/api/v1/`、`/api/rewrite` 在内）；
6. 新仓库 `sql-mask` main 分支包含全部历史与本方案落地。

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| 包迁移引发大量 import 修复遗漏 | 包名不变（仅模块边界移动），IDE 级全量编译兜底；先迁移后跑全量测试 |
| 统一错误体改变响应形状，破坏现有测试 | advice 收敛时保留各服务 code→status 映射；逐模块跑测试 |
| nginx 前缀 `/api/meta/` 与后端路径映射写错 | `proxy_pass` 带 URI 尾斜杠做前缀重写，dev（vite rewrite）与 prod（nginx）同构，vitest 覆盖 api 层路径常量 |
| mask-lite 退役丢失 TPC-DS 基准资产 | 基准语料与报告留在旧仓库封存可查；v1 工具仓库启动时按 v1-goals 重建全量矩阵 |
| gh CLI 未登录无法建仓 | 用 Git 凭据管理器中的 GitHub token 走 REST API 建仓（scope 覆盖 repo/workflow） |
