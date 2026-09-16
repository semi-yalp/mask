# Prometheus 指标设计方案（Actuator + Micrometer，双服务接入 + 审计管道指标）

日期：2026-09-17
状态：设计讨论中（待评审确认）
前置文档：`2026-09-16-audit-log-es-design.md`（审计管道指标是其 §1.2 YAGNI 项的转正）

## 1. 背景与目标

系统目前没有任何指标设施：数据面改写吞吐与错误率、管理面变更频率、策略服务
生效配置的拉取与版本推进、元数据采集成败，都只能翻日志。审计设计补上了"逐
事件留痕"（ES），但没有"聚合视角"——QPS、错误率、P99 延迟、审计管道自身的
健康度（队列积压、丢弃速率）这类运维视角无出处。本设计为 mask-core 与
mask-metadata 接入 Prometheus 指标。

目标：

1. 两个 Spring Boot 服务暴露标准 `/actuator/prometheus` 抓取端点；
2. 定义一组低基数业务指标：数据面改写、管理面变更、生效配置、元数据采集；
3. 弥补审计设计的 YAGNI 项：审计管道运行指标（队列深度、丢弃、ES 批写），
   契约在本 spec 定义，随 mask-audit 模块实现落地；
4. 免费获得 JVM / HTTP / 连接池等基础指标（Actuator 自带）；
5. 提供 Prometheus 抓取配置与 compose 示例。

### 1.1 已确认的决策

| 决策点 | 结论 |
|---|---|
| 服务范围 | mask-core + mask-metadata 都接入，与审计设计的服务范围对齐 |
| 技术选型 | 方案 A：`spring-boot-starter-actuator` + `micrometer-registry-prometheus`，版本由 spring-boot-dependencies 3.3.5 BOM 管理 |
| 审计管道指标 | 一并纳入本次设计（契约），实现随 mask-audit 模块交付；取代审计 spec §1.2 "运行状态走限频日志"的口径（限频日志保留，指标为聚合视角） |
| 主体维度 | user/groups **不进任何指标 label**（无界基数风险）；按主体归因走 ES 审计日志 |
| 暴露方式 | 同端口（mask-core 8080 / mask-metadata 8082）暴露 `/actuator/prometheus`；`management.server.port` 作为部署侧可选隔离手段，文档说明、不设默认 |
| 鉴权 | 指标端点不加鉴权（与数据面 `/api/rewrite` 现状一致），靠内网隔离，**不得暴露公网** |
| 部署配套 | 端点 + `docker/prometheus.yml` 抓取配置 + `docker-compose.metrics.yml` 示例；Grafana / Alertmanager 不做 |

### 1.2 明确不做（YAGNI）

- CLI 模式不出指标：短生命周期进程，pull 模型抓不到；改写留痕已由审计 ES 覆盖；
- 主体（user/groups）维度不进 label，不做按人聚合指标；
- 不做 pushgateway、remote write、服务发现；抓取 targets 静态配置；
- 不做 Grafana 面板、Alertmanager 告警规则（后续按需另立项）；
- 不新建 Maven 模块：埋点就地（计数发生在事件发生地），命名规范以本 spec 为准；
- mask-core 的 `/api/metadata/pull`、`/api/config/parse`、`/api/policies/parse`
  等无状态工具端点与 metaserver 实例读取端点不设专属指标——通用
  `http.server.requests` 已覆盖请求量与耗时；
- metaserver 实例读取端点维持审计设计"有意不记审计"的口径，指标侧仅由通用
  HTTP 指标覆盖；
- mask-lite 是库不是服务，不涉及。

## 2. 技术选型

| 方案 | 结论 | 说明 |
|---|---|---|
| **A. Actuator + Micrometer + Prometheus registry（选定）** | ✅ | JVM/HTTP/HikariCP 基础指标零代码；Micrometer 是门面，将来换后端不改业务代码；依赖由 BOM 管理 |
| B. 原生 Prometheus simpleclient + 手写 /metrics | ❌ | 依赖最小，但基础指标与端点两个服务各写一遍，仅适合严格砍依赖的场景 |
| C. OpenTelemetry SDK + Prometheus exporter | ❌ | 为 traces 铺路，当前只有指标需求，依赖树与概念面超配 |

## 3. 指标总目录

命名走 Micrometer 规范（点分小写）；Prometheus 抓取端呈现为小写下划线，
counter 追加 `_total`，timer 呈现为 `*_seconds_count / _sum / _max`（+ 桶）。
label 全部有界，枚举与审计事件模型对齐（`dialect`、`outcome`、
`resourceType`、`action`、`eventType` 同名同值，便于两侧互相印证）。

### 3.1 数据面改写（mask-core `RewriteController`）

| Micrometer 名 | Prometheus 呈现 | 类型 | label | 说明 |
|---|---|---|---|---|
| `sqlmask.rewrite.requests` | `sqlmask_rewrite_requests_total` | counter | `dialect`(postgresql/mysql/trino)、`outcome`(SUCCESS/FAILURE)、`masked`(true/false)、`row_filtered`(true/false) | 主指标：QPS、错误率、脱敏/行过滤命中率都从这里算。失败时 `masked`/`row_filtered` 记 false（改写中止，未产生脱敏结果） |
| `sqlmask.rewrite.failures` | `sqlmask_rewrite_failures_total` | counter | `dialect`、`code` | 失败细分。`code` 取 `SqlMaskException.Code` 闭合枚举（14 值），纪律：新增错误码才允许新增取值，非枚举异常统一记 `REWRITE_ERROR` |
| `sqlmask.rewrite.duration` | `sqlmask_rewrite_duration_seconds_{count,sum,max,bucket}` | timer | `dialect` | 改写处理耗时（控制器内计时，含解析/校验/血缘/改写，不含 HTTP 传输）。直方图桶在代码显式给定（§5） |
| `sqlmask.rewrite.statements` | `sqlmask_rewrite_statements_total` | counter | `dialect` | 改写语句总数，衡量实际工作量（与请求数对照看批大小） |

`sum(requests_total{outcome="FAILURE"})` 与 `sum(failures_total)` 恒等，可互为校验。

**label 取值纪律（全目录通用）**：来自请求输入的 label（`dialect`、`instance`）
必须先归一化并对照白名单，再进计数器——`dialect` 小写化后须命中
postgresql/mysql/trino，未命中一律记固定哨兵值 `invalid`；`instance` 未命中
已知实例（含拼写错误、恶意灌值）一律记固定哨兵值 `(not_found)`。这保证基数
上限由代码与管理面操作决定，不受请求输入影响。

### 3.2 管理面变更（mask-core 管理 REST + metaserver 管理 REST）

| Micrometer 名 | Prometheus 呈现 | 类型 | label | 说明 |
|---|---|---|---|---|
| `sqlmask.admin.requests` | `sqlmask_admin_requests_total` | counter | `resource_type`(INSTANCE/TABLES/POLICY/UDF/METADATA)、`action`(CREATE/UPDATE/DELETE/REPLACE_TABLES/IMPORT/REGISTER)、`outcome`(SUCCESS/FAILURE) | 枚举对齐审计事件 `resourceType`/`action`，外加 `METADATA`（metaserver 自身实例 CRUD/导入）。双服务通用 tag `application` 区分来源 |
| `sqlmask.admin.duration` | `sqlmask_admin_duration_seconds_*` | timer | `resource_type`、`action` | 与审计 `AuditAdminHelper` 同位置计时，避免双重包装 |

注意：metaserver 的引擎采集（审计 `action=COLLECT`）走 §3.4 的
`sqlmask.metadata.collect`（需要 `engine` 维度），不计入 `admin.requests`，
故 action 枚举不含 COLLECT；审计侧仍记 ADMIN_CHANGE 事件，两者职责不同、
互不影响。

### 3.3 生效配置（mask-core 策略服务 `GET /api/effective/{instance}`）

| Micrometer 名 | Prometheus 呈现 | 类型 | label | 说明 |
|---|---|---|---|---|
| `sqlmask.effective.pull` | `sqlmask_effective_pull_total` | counter | `instance`、`dialect`、`outcome` | 引擎拉取行为与成败。`instance` 是全目录唯一半开放 label：由管理面操作驱动、实践量级 <100，接受；若担心可去掉只留 `outcome`，实例维度去审计查 |
| `sqlmask.effective.compile` | `sqlmask_effective_compile_seconds_*` | timer | `instance` | 逐主体编译耗时 |
| `sqlmask.effective.config_version` | `sqlmask_effective_config_version` | gauge | `instance` | 当前编译产物版本号。回答"策略改了、引擎拉到没有"：版本不涨 + pull 在涨 = 引擎在拉旧配置 |
| `sqlmask.effective.policies` | `sqlmask_effective_policies` | gauge | `instance` | 当前生效策略条数（实例级，非逐主体） |

### 3.4 元数据采集（metaserver `CollectController`）

| Micrometer 名 | Prometheus 呈现 | 类型 | label | 说明 |
|---|---|---|---|---|
| `sqlmask.metadata.collect` | `sqlmask_metadata_collect_total` | counter | `engine`(postgresql/mysql/trino)、`outcome` | 采集请求成败 |
| `sqlmask.metadata.collect.duration` | `sqlmask_metadata_collect_seconds_*` | timer | `engine` | 采集耗时（受源库系统目录查询速度支配） |
| `sqlmask.metadata.warnings` | `sqlmask_metadata_warnings_total` | counter | `engine` | 类型降级告警条数（jsonb/uuid/array → varchar），衡量"语义损失量"，突增说明源库 schema 在漂移 |

### 3.5 审计管道（mask-audit 模块内实现，本节为契约）

随 mask-audit 实现交付；名字与语义此处定死，audit 实施计划直接引用。
审计 spec §1.2 的"不做指标端点"由本节取代：限频 WARN 日志保留（仍有人读），
指标补聚合视角。

| Micrometer 名 | Prometheus 呈现 | 类型 | label | 说明 |
|---|---|---|---|---|
| `sqlmask.audit.enqueued` | `sqlmask_audit_enqueued_total` | counter | `event_type`(REWRITE/ADMIN_CHANGE/EFFECTIVE_PULL) | 受理事件数（进入队列） |
| `sqlmask.audit.dropped` | `sqlmask_audit_dropped_total` | counter | `reason`(QUEUE_FULL/ES_FAILURE) | 被丢弃事件条数。`dropped_total / (enqueued_total + dropped_total)` 即审计损失率；`dropped{reason="ES_FAILURE"}` 持续增长 = ES 有问题 |
| `sqlmask.audit.queue.depth` | `sqlmask_audit_queue_depth` | gauge | 无 | 当前队列积压，持续逼近容量说明 ES 写入跟不上 |
| `sqlmask.audit.queue.capacity` | `sqlmask_audit_queue_capacity` | gauge | 无 | 队列容量（来自 `audit.queue-capacity` 配置），算水位用 |
| `sqlmask.audit.es.batches` | `sqlmask_audit_es_batches_total` | counter | `outcome`(SUCCESS/FAILURE) | bulk 批写成败 |
| `sqlmask.audit.es.documents` | `sqlmask_audit_es_documents_total` | counter | 无 | 成功写入文档数（批大小累加），与 `enqueued` 对账 |
| `sqlmask.audit.es.write` | `sqlmask_audit_es_write_seconds_*` | timer | 无 | ES 批写耗时 |

### 3.6 基础指标（Actuator 免费，零工作量）

依赖引入即有，仅列出与运维相关的子集：

- `http_server_requests_seconds_*`：`uri`/`method`/`status`/`outcome` 维度，
  覆盖 §1.2 列出的不设专属指标的端点；
- `jvm_memory_used_bytes` / `jvm_gc_pause_seconds_*` / `jvm_threads_live`；
- `process_uptime_seconds` / `process_cpu_usage` / `system_cpu_usage`；
- `hikaricp_connections_*`：两个服务都有 JDBC 存储（policy store / meta store），
  连接池等待与活跃数是常见瓶颈位。

`http.server.requests` 的 `uri` label 由 Spring 自动模板化（如
`/api/effective/{instance}`），不落具体实例名，无基数风险；`exception` label
为代码中实际抛出的异常类名，实际有界。

### 3.7 基数预算

label 全部有界，单服务 series 估算：

| 来源 | 估算 |
|---|---|
| 改写四件套 | requests 3×2×2×2=24，failures 3×14=42，duration 3×11桶=33，statements 3 ≈ **102** |
| 管理面 | 5×7×2=70 + timer 35 ≈ **105**（实际组合远小于笛卡尔积） |
| 生效配置 | 实例数 N（<100）× (3×2 + 11桶 + 2 gauge) ≈ **数百，N=10 时约 160** |
| 元数据采集 | 3×2 + 3×11桶 + 3 ≈ **42** |
| 审计管道 | 3 + 2 + 2 + 2 + 1 + 11桶 ≈ **21** |
| 基础指标 | HTTP 端点数 × 状态码 + JVM/Hikari 固定 ≈ **百级** |

两服务各千级以内，远低于需要基数治理的量级。唯一增长源是 `instance` label，
其上限即管理面创建的实例数，本身有审计事件可查。

## 4. 埋点方式

不新建 Maven 模块。各 Controller/Service 注入 `MeterRegistry` 就地埋点：

- 改写四件套在 `RewriteController` 内 try/catch/finally 计时计数（异常捕获后
  原样重抛，不改错误语义）；`dialect` 按 §3.1 label 纪律先归一化白名单再计数；
- 管理面计时与计数落在与审计 `AuditAdminHelper` 相同的包装位置（管理面本来
  就要包一层记审计，指标在同处顺带记录，不二次包装）；
- 生效配置 gauges 在 `PolicyService` 编译/拉取路径上注册，version/policies
  取自编译结果；实例不存在时按 §3.1 的 label 纪律记
  `pull{instance="(not_found)", dialect="(not_found)", outcome=FAILURE}`
  （错误本身是 `POLICY_INSTANCE_NOT_FOUND`，值得看见），不为请求提供的任意
  实例名创建 series；
- 元数据三项落在 metaserver `CollectService`；
- 审计管道六项由 `EsAuditRecorder` 内部直接读队列与批写结果，随 mask-audit
  交付；
- 直方图桶在代码建 timer 时显式给定，不走全局配置（§5）。

## 5. 配置

依赖（两服务 pom 各加两行，版本走 BOM）：

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

application.yml（两服务同形）：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  observations:
    key:
      values:
        application: ${spring.application.name}   # sql-mask / mask-metadata 公共 tag
```

- 暴露路径 `/actuator/prometheus`，同端口（8080 / 8082）；文档注明部署侧可用
  `management.server.port` 单独隔离指标端口（可选，不设默认）；
- 指标端点不加鉴权：与数据面 `/api/rewrite` 现状一致，靠网络隔离，**不得暴露
  公网**（label 含实例名与错误码，属于内部信息）；
- timer 直方图桶（代码显式给定，Prometheus 侧可算任意分位）：
  - `sqlmask.rewrite.duration`：1ms / 5ms / 10ms / 25ms / 50ms / 100ms / 250ms /
    500ms / 1s / 5s / 10s；
  - `sqlmask.admin.duration`、`sqlmask.effective.compile`：同档位；
  - `sqlmask.metadata.collect.duration`：10ms / 50ms / 100ms / 250ms / 500ms /
    1s / 2.5s / 5s / 10s / 30s / 60s（源库目录查询较慢）；
  - `sqlmask.audit.es.write`：5ms / 10ms / 25ms / 50ms / 100ms / 250ms / 500ms /
    1s / 2.5s / 5s / 10s。

## 6. 部署配套

`docker/prometheus.yml`（新增）：

```yaml
scrape_interval: 15s

scrape_configs:
  # 服务跑在宿主机、Prometheus 跑容器的本地开发形态（默认）
  - job_name: sql-mask
    static_configs:
      - targets: ["host.docker.internal:8080"]
  - job_name: mask-metadata
    static_configs:
      - targets: ["host.docker.internal:8082"]
  # 若服务也在 compose 网络内，把 targets 换成服务名:
  #   sql-mask:8080 / metadata:8082
```

`docker-compose.metrics.yml`（新增，与现有 `docker-compose.metadata.yml` 并存叠加）：

```yaml
services:
  prometheus:
    image: prom/prometheus:v2.53.0
    volumes:
      - ./docker/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    ports:
      - "9090:9090"
    extra_hosts:
      - "host.docker.internal:host-gateway"
```

验证路径：起服务 → `curl localhost:8080/actuator/prometheus | grep sqlmask_` →
起 compose → Prometheus UI 查 `sqlmask_rewrite_requests_total`。

## 7. 测试

- 单测（`SimpleMeterRegistry` 注入替换真实 registry）：
  - 改写成功：`requests{dialect, SUCCESS, masked/row_filtered}` 计数正确，
    `statements` 与语句数一致，timer 有记录；
  - 改写失败：`outcome=FAILURE` + `masked=false`，`failures{code}` 对应
    `SqlMaskException.Code`，非 SqlMaskException 异常归 `REWRITE_ERROR` 且原样重抛；
  - label 纪律：`dialect="JUNK"` 记入 `invalid` 哨兵；生效配置拉取未知实例记
    `instance="(not_found)"` 哨兵，不产生请求输入派生的 series；
  - 管理面：成功/失败各 +1，`resource_type`/`action` 取值正确；
  - 生效配置：pull 计数、version/policies gauge 推进、实例不存在时不创建
    实例维 series；
  - 元数据采集：计数/耗时/告警三项与采集结果一致；
  - 审计管道六项随 mask-audit 的实施计划测试（队列满丢弃 → `dropped{QUEUE_FULL}`，
    批写失败 → `dropped{ES_FAILURE}` + `es.batches{FAILURE}`）。
- 冒烟（MockMvc）：`GET /actuator/prometheus` 返回体包含
  `sqlmask_rewrite_requests_total`、`sqlmask_audit_queue_depth` 等关键序列与
  `application` 公共 tag。
- 部署物验证按 §6 验证路径人工走一遍。

## 8. 实施顺序说明

- 本 spec 的 §3.1–3.4 与 §5/§6 可独立实施，不依赖 mask-audit；
- §3.5 审计管道指标随 mask-audit 模块落地（其 spec 的实施计划需引用本节契约）；
- 其余依赖只有两个 pom 坐标与若干 controller/service 的埋点改动。
