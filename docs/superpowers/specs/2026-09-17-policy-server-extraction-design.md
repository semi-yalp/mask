# 策略微服务独立部署设计（mask-policy-server 拆分 + instance 模式接线）

日期：2026-09-17
状态：设计已与用户确认（内嵌彻底移除 / PG 默认存储 / UDF 归属 三项决策 + 整体设计通过）
前置文档：
`2026-09-06-policy-service-design.md`（策略微服务模型、§5 引擎接入、§6 部署形态——本设计落地其遗留项）、
`2026-09-15-udf-registry-design.md`（UDF 注册表，已实现）、
`2026-09-16-policy-admin-rest-design.md`（管理面 REST + 主体维度，已实现）

## 1. 背景与目标

策略微服务的服务端代码**已经全部实现**，但内嵌在 mask-core（8080）同一进程：

- `io.sqlmask.policyserver`：`PolicyService`、`PolicyValidator`、`EngineDefs`、
  `EffectiveConfigCompiler`、model、store（`JdbcPolicyStore` + `InMemoryPolicyStore`）；
- `io.sqlmask.server`：`EffectiveConfigController`（数据面）、`PolicyAdminController`、
  `UdfController`、`MetadataImportController` + `MetadataStructureFetcher`（管理面与导入）、
  `PolicyApiKeyFilter`；
- 当前以 `InMemoryPolicyStore` 默认运行（datasource 自动装配被显式排除）。

客户端侧同样已就绪但未接线：`PolicyServiceConfigSource`（LRU 256 主体缓存、
stale-but-available、无缓存不可达 fail-closed、`refresh()` 全量轮询）无任何调用方；
`RewriteEngine.rewrite(LoadedConfig, …)` 入口已存在。

本次目标：

1. 拆出第三个独立微服务 **mask-policy-server（8081）**，core 回归纯改写服务；
2. 补上 09-06 spec §5 规划但未落地的**接线**：`/api/rewrite` 支持 `instance` 模式
   （与 `metadataYaml` 二选一），CLI 对应 `--instance` / `--policy-service`；
3. 独立服务 **PG 默认存储**，部署形态对齐 mask-metadata。

### 1.1 已确认的决策

| 决策点 | 结论 |
|---|---|
| 内嵌模式去留 | 彻底移除：core 只留内联 YAML 路径 + HTTP 客户端，不留内嵌开关 |
| 独立服务存储默认 | PG 默认（`JdbcPolicyStore` + `schema.sql`）；`InMemoryPolicyStore` 仅测试用 |
| UDF 归属 | UDF 注册表全量进 policy 服务（REST / 签名存储 / 四步校验 / 引用守恒守卫）；改写引擎按配置拼 UDF 调用 SQL 的逻辑留 core；内联 YAML 路径不动 |
| 模块形态 | 新建 `mask-policy-server`；不把服务端塞进 `mask-policy`（见 §2） |
| 端口 | 8081（09-06 spec 既定；core 8080 / policy 8081 / metadata 8082） |
| 迁移原则 | 迁移端点的对外行为逐字节不变（含错误状态码——现状 `SqlMaskException` 统一 400，迁移不改） |

### 1.2 明确不做（YAGNI）

- 管理面 UI（沿用 09-06 spec 决策）；
- 推送式配置分发（轮询 + 手动刷新）；
- core 内嵌策略服务的兼容开关（已确认移除）；
- 存量数据迁移（内嵌 store 是内存态，重启即失，无持久数据）；
- `GET /api/effective/{instance}/version` 轻量版本端点：客户端 `refresh()` 按主体
  全量刷新已够用（LRU 上限 256 主体），主体量级变大再立项；
- `/api/rewrite` 业务面鉴权与多租户（另行立项不变）；
- 新增 HTTP 状态码语义（404/503 映射）：延续现状 400 + code，另行统一。

## 2. 模块与依赖

```
mask/
├── mask-core/            ← 改写服务（瘦身，见 §4）
├── mask-policy/          ← 纯库不动（匹配引擎 + /api/policies/parse）
├── mask-policy-server/   ← 新增：策略微服务
├── mask-metadata/        ← 不动
└── pom.xml               ← <modules> 增加 mask-policy-server
```

依赖方向（无环）：

- `mask-policy-server` → `mask-core`：复用编译产物契约类型
  （`io.sqlmask.config.source.EffectiveConfigResponse` 等）、`MetadataClient`、
  `SqlMaskException`（错误码）。产物类型同源，客户端/服务端零漂移；
- `mask-policy-server` → `mask-policy`：`SubjectSelector`、`Subject`（经 core 传递亦可，
  显式声明以表达直接依赖）；
- `mask-core` → `mask-policy`：引擎库（现状不变）；
- core ↔ policy-server 运行时**仅有 HTTP**。

**为什么不用 `mask-policy` 承载服务端**（09-06 spec §6.1 的字面方案）：core 依赖
mask-policy（`PolicyEngine` 等引擎库），服务端又需要 core 的契约类型——把服务端放进
mask-policy 会形成 `core → mask-policy → core` 循环，被迫再拆一层共享模块。新建模块
是零重构的唯一解。

## 3. 新服务 mask-policy-server

### 3.1 迁入清单

| 现位置（mask-core） | 新位置（mask-policy-server） |
|---|---|
| `io.sqlmask.policyserver.*` 整包（PolicyService / PolicyValidator / EngineDefs / compile / model / store） | 包名原样保留 |
| `server/EffectiveConfigController` | `io.sqlmask.policyserver.web` |
| `server/PolicyAdminController` | `io.sqlmask.policyserver.web` |
| `server/UdfController` | `io.sqlmask.policyserver.web` |
| `server/MetadataImportController` | `io.sqlmask.policyserver.web` |
| `server/MetadataStructureFetcher` | `io.sqlmask.policyserver.web`（与使用它的导入控制器同包） |
| `server/PolicyApiKeyFilter` | **复制**到 `io.sqlmask.policyserver.web`；core 保留原类与注册（见 §9 变更记录） |
| core `resources/schema.sql` | policy-server `resources/schema.sql` |
| `policyserver.*` 与上述 controller 的全部测试 | 对应测试包 |

core 的 `ApiExceptionHandler` **不迁**（core 自己的 `/api/rewrite` 等仍要用）；
policy-server 新建 `web/PolicyApiExceptionHandler`：逐行复制 core 现有映射
（`SqlMaskException` → 400 + `{code, message}`、`PolicyException` → 400 CONFIG_ERROR、
兜底 500），保证迁移端点行为不变。

### 3.2 启动类与配置

`io.sqlmask.policyserver.PolicyServerApplication`（`scanBasePackages =
"io.sqlmask.policyserver"`，不排除 `DataSourceAutoConfiguration`）：

```yaml
server:
  port: 8081
spring:
  application:
    name: mask-policy
  datasource:
    url: ${POLICY_PG_URL:jdbc:postgresql://127.0.0.1:5432/mask_policy}
    username: ${POLICY_PG_USER:postgres}
    password: ${POLICY_PG_PASSWORD:postgres}
  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql
```

- Bean：`JdbcPolicyStore`（默认，经自动装配的 `JdbcTemplate`）、`PolicyValidator`、
  `PolicyService`、`MetadataStructureFetcher.Http`、`PolicyApiKeyFilter` 注册
  （urlPatterns `/api/instances/*` 与 `/api/effective/*`，env
  `SQLMASK_ADMIN_API_KEY` / `SQLMASK_DATA_API_KEY`，未配置不拦截——全部照搬现状）；
- `InMemoryPolicyStore` 不注册为 Bean，仅测试直接 `new`；
- 鉴权语义、401 响应形状与现状逐字节一致。

### 3.3 UDF 归属（已确认）

- **进 policy 服务**：UDF 注册表 REST（`/api/instances/{instance}/udfs` CRUD）、
  `UdfDefinition` 存储、策略引用 UDF 的四步校验（存在性 / 参数个数 / 标量矩阵 /
  列类型）、UDF 删除与替换的引用守恒守卫；
- **留 core**：改写引擎按配置中的 UDF 名拼接「外层调用脱敏 UDF」SQL——它是改写
  本职，不依赖注册表；内联 `metadata.yaml` 自带 `policies` 的路径原样不动。

## 4. core 侧变化

### 4.1 移除

- `io.sqlmask.policyserver` 包整包及测试；
- `io.sqlmask.server` 中：`EffectiveConfigController`、`PolicyAdminController`、
  `UdfController`、`MetadataImportController`、`MetadataStructureFetcher`（随迁）；
  `PolicyApiKeyFilter` 类与注册保留在 core（同 §9 变更记录）；
- `SqlMaskServiceApplication` 的 `policyStore` / `policyValidator` / `policyService` /
  `metadataStructureFetcher` / `policyApiKeyFilter` 五个 @Bean（保留 `RewriteEngine`、
  `PgMetadataIntrospector`）；datasource 排除注解保留（core 依旧无库）。

### 4.2 保留（消费方代码，属于改写服务）

- `io.sqlmask.config.source` 全部：`ConfigSource`、`InlineYamlConfigSource`、
  `PolicyServiceConfigSource`、`EffectiveConfigAssembler`、`EffectiveConfigResponse`
  （契约类型，policy-server 反向依赖 core 引用它们）；
- `/api/rewrite` 内联路径、`/api/config/parse`、`/api/metadata/pull`、
  `/api/policies/parse`（mask-policy 提供）、内置页面（已验证无管理面引用）、
  CLI 现有参数。

### 4.3 instance 模式接线（新增行为）

**请求契约**：`RewriteRequest` 增加 `instance` 字段：

- `instance` 与 `metadataYaml` **二选一**：同时给出或缺省 → 400 `CONFIG_ERROR`
  （固定文案说明两种模式）；
- `instance` 模式下给 `policyYaml` → 400 `CONFIG_ERROR`（策略已编译进生效配置）；
- `dialect` 请求字段在 instance 模式忽略（方言来自生效配置），文档注明。

**服务端配置**（Spring `@Value`，均未配置时 instance 模式报 `CONFIG_ERROR` 提示缺失）：

| 配置 | 环境变量 | 说明 |
|---|---|---|
| `policy.service.url` | `POLICY_SERVICE_URL` | 策略服务基址，如 `http://localhost:8081` |
| `policy.service.api-key` | `POLICY_SERVICE_API_KEY` | 数据面 Key（`SQLMASK_DATA_API_KEY` 配置时必传） |
| `policy.service.poll-interval-ms` | `POLICY_SERVICE_POLL_INTERVAL_MS` | 缓存刷新周期，默认 30000 |

**请求流程**：按 `instance` 取复用的 `PolicyServiceConfigSource`（core 内
`ConcurrentHashMap<instanceName, source>`——实例数小，不做逐出；客户端自身
synchronized）→ `load(Subject.of(user, groups))` → `ResolvedConfig` →
`engine.rewrite(loaded, null, sql, dialect, subject)`。LRU、stale-but-available、
fail-closed 全部由既有客户端承担，不改语义。

**定时刷新**：`@EnableScheduling` + `@Scheduled(fixedDelayString =
"${policy.service.poll-interval-ms:30000}")` 遍历已建 source 调 `refresh()`，
版本变化记日志；`POLICY_SERVICE_URL` 未配置时调度器不建 source、天然空转。
不做 `/version` 轻量端点（§1.2）。

**手动刷新**：`POST /admin/cache/refresh`（core，09-06 spec §5.2 既定，归属改写
服务）：清空 source Map（可选 body `{"instance": "..."}` 只清一个），下一次请求
重新拉取；与 `/api/rewrite` 同为无鉴权现状。

**CLI**：`--instance <name>` + `--policy-service <url>` 与 `--metadata` 互斥
（09-06 spec §5.1 既定）；URL 缺省时回退 env `POLICY_SERVICE_URL`，两者皆无 → 
`CONFIG_ERROR`；Key 只读 env `POLICY_SERVICE_API_KEY`，不新增参数。
CLI 为一次性进程，无缓存与调度，直接拉取。

## 5. 部署

- `docker/policy.Dockerfile`：仿 `metadata.Dockerfile`（多阶段构建 → JRE 运行）；
- `docker-compose.policy.yml`：`postgres`（policy 专用库）+ `policy`（8081），env
  对齐 §3.2——与 `docker-compose.metadata.yml` 完全对称、互不干扰；
- core 接入示例：`POLICY_SERVICE_URL=http://localhost:8081`（+ 可选
  `POLICY_SERVICE_API_KEY`）；
- README 更新：三服务形态与端口表、单 jar 行为变化（§7）、instance 模式用法。

## 6. 错误与安全

- 错误码沿用：`POLICY_INSTANCE_NOT_FOUND`、`CONFIG_ERROR`、
  `METADATA_SERVICE_UNAVAILABLE` / `METADATA_INSTANCE_NOT_FOUND`（导入透传）、
  401（API Key）；HTTP 状态维持现状统一 400（§1.2 不做 404/503 细分）；
- policy-server 异常处理器为 core 处理器的复制件（§3.1），迁移端点响应形状不变；
- 客户端 fail-closed 语义不变：无缓存 + 服务不可达 → `POLICY_SERVICE_UNAVAILABLE`，
  绝不降级为不脱敏输出。

## 7. 兼容性影响

- 8080 上 `/api/instances/**` 与 `/api/effective/**` **消失**（含 UDF CRUD），
  统一迁至 8081；
- `/api/rewrite`、`/api/policies/parse`、`/api/config/parse`、`/api/metadata/pull`
  仍在 8080；`/api/rewrite` 新增 `instance` 模式为纯增量，既有字段行为不变；
- 无存量数据迁移（内嵌 store 内存态）。

## 8. 测试策略

1. **迁移回归**：policy-server 侧 policyserver 包 + 四个 controller + filter 的既有
   测试随包迁移全绿（MockMvc CRUD / 配置期校验 / 编译 / 主体过滤 / 鉴权）；
2. **core 回归**：rewrite / CLI / 内置页面 / config / metadata 测试零变化；
   内嵌相关测试删除或随迁；
3. **instance 模式**：二选一与 `policyYaml` 互斥校验；MockWebServer 覆盖拉取 +
   组装 + 按 instance 复用 source + `refresh()` 调度 + stale-but-available +
   无缓存 fail-closed + 401 / 404 / 非 200 映射；
4. **手动刷新端点**：全清与按实例清、清后重拉；
5. **端到端**：`docker-compose.policy.yml` 起 policy + PG → 管理面建实例 / 注册
   UDF / 建带主体策略 → core 以 `instance` 模式按主体改写成功；切换 isEnabled 后
   一个轮询周期内生效。

## 9. 变更记录

- **2026-09-17（计划期）**：`PolicyApiKeyFilter` 由"迁移"改为"复制"。原因：分支上
  并行的审计工作线（未提交 WIP：mask-core 引入 mask-audit、`PolicyApiKeyFilterTest`
  新增 `/api/audit/events` 守卫测试）表明 core 还要继续用该过滤器守护自己的管理面
  （审计事件端点），迁移会折断审计工作；过滤器为自包含小类，两服务各持一份、
  行为逐字节一致，避免为此新拆共享模块。core 的注册 bean 保留（守护路径由审计
  工作线定义；指向无控制器的路径无害）。
