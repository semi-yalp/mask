# 审计日志 ES 设计方案（mask-audit 模块 + 三类事件 + 内置查询 API）

日期：2026-09-16
状态：设计已确认，待实施
前置文档：`2026-09-16-policy-admin-rest-design.md`（管理面 REST，审计的主要接入面）、
`2026-09-07-metadata-service-design.md`（metaserver，第二接入服务）

## 1. 背景与目标

脱敏系统的三类关键动作——查询改写、策略/实例变更、引擎拉取生效配置——目前
没有任何留痕：谁在什么时候改了策略、哪个主体发起了什么查询、命中了哪些脱敏/
行过滤，都无从回溯。本设计为 mask-core 与 mask-metadata 增加统一的审计日志
子系统，事件写入 Elasticsearch，并在 mask-core 提供内置查询 API。

目标：

1. 新建共享模块 `mask-audit`：事件模型 + 异步 ES 写入管道 + 查询封装，两个
   服务各自依赖接入；
2. 记录三类事件：数据面改写（REWRITE）、管理面变更（ADMIN_CHANGE）、
   生效配置拉取（EFFECTIVE_PULL）；
3. 可靠性语义为**异步尽力而为**：ES 故障不影响任何业务请求；
4. mask-core 提供固定条件的审计查询 REST，自由探索交给 Kibana。

### 1.1 已确认的决策

| 决策点 | 结论 |
|---|---|
| 审计范围 | 三类全记：REWRITE / ADMIN_CHANGE / EFFECTIVE_PULL；失败的改写与失败的变更操作也记（outcome=FAILURE） |
| 服务范围 | mask-core + mask-metadata 都接入；查询 API 只放 mask-core |
| 可靠性 | 异步尽力而为：有界内存队列，队列满丢弃并计数，写失败整批丢弃 + 限频 WARN，不重试、不落盘兜底、不阻断业务 |
| SQL 存储 | 原文 + 改写结果都存，单字段超 `sql-max-chars`（默认 8192）截断并打标 |
| 管理面事件内容 | 只记资源标识 + `detail` 摘要，不携带完整请求体（避免大 YAML 与敏感参数进审计） |
| 读取侧 | ES + mask-core 内置查询 API（固定过滤条件、分页），不做 DSL 透传 |
| ES 客户端 | 方案 A：官方 `elasticsearch-java`（版本由 spring-boot-dependencies 3.3.5 BOM 管理） |
| 测试 ES | 单测用 JDK `HttpServer` 假 ES；集成验证优先本地 compose 的 ES，必要时可在远程主机部署 ES（`AUDIT_ES_URL` 指向）做联调 |
| metaserver 数据面读取 | `GET /api/metadata/instances/{name}`（策略服务轮询源）**有意不记**，类比策略库读操作；将来需要时加开关 |

### 1.2 明确不做（YAGNI）

- 不做本地文件兜底重放（ES 挂了就丢，接受尽力而为语义的代价）；
- 不做写入重试/退避（失败即丢弃计数，重试只会推迟丢弃）；
- 不做审计事件的管理端点/指标端点（运行状态走限频日志）；
- 不做多租户身份体系：管理面是单把 API Key，事件记 `authKind: API_KEY` +
  `sourceIp`，区分不了个人，将来鉴权细化后再补；
- 不做 DSL 透传查询、不做聚合/报表端点；
- 不在服务端管理保留策略（按天索引天然支持部署侧清理，模板 v1 不绑 ILM，
  文档给可选 ILM 示例）；
- 不审 mask-core 的无副作用工具端点（`/api/config/parse`、`/api/policies/parse`、
  `/api/metadata/pull`）与 GET 读操作（`/api/instances` 列表/详情等）。

## 2. 模块结构

新 Maven 模块 `mask-audit`（`io.sqlmask:mask-audit`），加入 parent `<modules>`；
mask-core、mask-metadata 的 pom 增加对它的依赖。包 `io.sqlmask.audit`：

| 类 | 职责 |
|---|---|
| `AuditEvent` | 统一事件模型（不可变 record，公共信封 + 类型专属字段，第 3 节） |
| `AuditRecorder` | 发射接口：`void record(AuditEvent)`，**约定永不抛异常**；业务路径开销仅一次队列 offer |
| `EsAuditRecorder` | 默认实现：有界队列 + 单后台线程 + 批量 bulk（第 4 节） |
| `NoopAuditRecorder` | `audit.enabled=false` / 未配置时的退化实现 |
| `AuditAdminHelper` | 管理面包装：`<T> T adminChange(action, resourceType, instance, resourceName, detailSupplier, work)`——执行 work、计时、记 SUCCESS/FAILURE、原样重抛业务异常 |
| `AuditSearchClient` | 查询封装：固定条件 → ES search → DTO（第 6 节） |
| `AuditProperties` | `audit.*` 配置绑定（第 4.5 节） |
| `AuditAutoConfiguration` | Spring Boot 自动装配（`META-INF/spring/...AutoConfiguration.imports`），`@ConditionalOnProperty(audit.enabled)`；ES 客户端、模板初始化、recorder、search client 一起装配 |

依赖：`elasticsearch-java`（含 rest-client 传输层）、`jackson-databind`（作
JsonpMapper）、`spring-boot-autoconfigure`。版本全部由 spring-boot-dependencies
管理，模块 pom 不写死版本号。

## 3. 事件模型

三类事件共用一个索引族、一个文档结构（公共信封 + 类型专属字段）：

```json
{
  "@timestamp": "2026-09-16T12:34:56.789Z",
  "eventType": "REWRITE",
  "service": "sql-mask",
  "outcome": "SUCCESS",
  "durationMs": 12,
  "sourceIp": "10.0.0.5",
  "actor": { "user": "alice", "groups": ["devs"], "authKind": "ANONYMOUS" },
  "error": null,

  "dialect": "postgresql",
  "statementCount": 2,
  "masked": true,
  "rowFiltered": false,
  "originalSql": "SELECT phone FROM customer;",
  "rewrittenSql": "SELECT \"mask_phone\"(...) ...",
  "sqlTruncated": false,

  "resourceType": null,
  "action": null,
  "instance": null,
  "resourceName": null,
  "detail": null
}
```

字段与 mapping：

| 字段 | ES 类型 | 说明 |
|---|---|---|
| `@timestamp` | date | 事件产生时间（UTC，写入时生成） |
| `eventType` | keyword | `REWRITE` / `ADMIN_CHANGE` / `EFFECTIVE_PULL` |
| `service` | keyword | `sql-mask` / `mask-metadata`（取 spring.application.name） |
| `outcome` | keyword | `SUCCESS` / `FAILURE` |
| `durationMs` | integer | 操作耗时（控制器内计时） |
| `sourceIp` | ip | 取自当前 HTTP 请求；无请求上下文为 null |
| `actor.user` / `actor.groups` | keyword / keyword | 数据面与生效面来自请求参数；管理面为 null |
| `actor.authKind` | keyword | `API_KEY` / `ANONYMOUS`：请求是否携带了通过校验的 `X-Api-Key` |
| `error.code` / `error.message` | keyword / text | 失败时的结构化错误（SqlMaskException 的 code/message 或 500 类异常类名+消息） |
| `dialect` | keyword | REWRITE 专属 |
| `statementCount` / `masked` / `rowFiltered` | integer / boolean / boolean | REWRITE 专属；masked/rowFiltered 取「任一语句为真」 |
| `originalSql` / `rewrittenSql` | text + `.keyword`(ignore_above 256) | REWRITE 专属；请求内多语句取拼接后全文 |
| `sqlTruncated` | boolean | 任一 SQL 字段被截断时为 true |
| `resourceType` | keyword | ADMIN_CHANGE 专属：`INSTANCE` / `TABLES` / `POLICY` / `UDF` |
| `action` | keyword | `CREATE` / `UPDATE` / `DELETE` / `REPLACE_TABLES` / `IMPORT` / `COLLECT` / `REGISTER` |
| `instance` | keyword | 操作所属实例名（两级资源才有；顶层操作为 null） |
| `resourceName` | keyword | 被操作的资源名（策略名/UDF 名/实例名） |
| `detail` | object（dynamic） | 按 resourceType 的摘要，见下 |

`detail` 摘要约定（不携带完整请求体）：

- `INSTANCE`：`{dialect, tableCount}`；metaserver 侧另有 `{engine, database}`；
- `TABLES`（REPLACE_TABLES / IMPORT）：`{tableCount}`；IMPORT 另记
  `{sourceInstance}`（mask-core 从 metaserver 导入时）；
- `POLICY`：`{policyType, enabled, resource:{catalog,schema,table,columns},
  subjects:{users,groups}}`——不含 udf arguments（可能是敏感参数）；
- `UDF`：`{paramTypes, returnType}`。

密码红线：审计事件任何字段不含数据库密码、API Key 原文、ES 凭据。

## 4. 写入管道（EsAuditRecorder）

### 4.1 队列与批量

- `ArrayBlockingQueue<AuditEvent>`，容量 `audit.queue-capacity`（默认 10000）；
- `record()` 只做 `offer()`：队列满即**丢弃 + 计数**（`dropped` 计数器）；
- 单后台线程（守护线程，命名 `audit-es-writer`）：攒批到 `audit.batch-size`
  （默认 200）或距上次 flush 超 `audit.flush-interval-ms`（默认 2000ms）即执行
  一次 `client.bulk()`（目标索引 = 当日索引名，每个事件一条 index 操作）；
- 一次 bulk 的 HTTP 超时：连接 3s / socket 10s。

### 4.2 失败语义

- bulk 失败（连接失败/超时/部分 item 错误）：整批丢弃 + `failedBatches` 计数；
- WARN **限频**：至多每 60s 一条，携带窗口内累计（丢弃数/失败批数/当前队列
  深度）；ES 恢复（某次 bulk 成功）时输出一条 INFO（带中断时长）；
- 不重试、不落盘；`record()` 在任何路径上都不抛出。

### 4.3 索引与模板

- 索引名：`{index-prefix}-YYYY.MM.dd`（默认前缀 `mask-audit`，日期 **UTC**）；
- 启动时幂等 `PUT /_index_template/{index-prefix}`：

```json
{
  "index_patterns": ["mask-audit-*"],
  "template": {
    "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
    "mappings": { "properties": { ...第 3 节 mapping... } }
  }
}
```

- 模板 PUT 失败（ES 未就绪/权限不足）：WARN + 每 60s 后台重试，**不阻塞启动、
  不影响写入尝试**（写入到未建模板的索引会按 ES 动态映射落地，模板就绪后
  新索引恢复正常）；
- 单节点本地套默认 `replicas: 0`；生产部署可 PUT 覆盖同名模板调整分片/副本。

### 4.4 停机

- `@PreDestroy`：停后台线程前排空队列，最多等 5s，超时剩余事件丢弃并记日志。

### 4.5 配置（两个服务同构，全部环境变量可覆盖）

```yaml
audit:
  enabled: ${AUDIT_ENABLED:true}
  elasticsearch:
    url: ${AUDIT_ES_URL:http://127.0.0.1:9200}
    api-key: ${AUDIT_ES_API_KEY:}          # 与 username/password 二选一
    username: ${AUDIT_ES_USER:}
    password: ${AUDIT_ES_PASSWORD:}
  index-prefix: ${AUDIT_INDEX_PREFIX:mask-audit}
  queue-capacity: ${AUDIT_QUEUE_CAPACITY:10000}
  batch-size: ${AUDIT_BATCH_SIZE:200}
  flush-interval-ms: ${AUDIT_FLUSH_INTERVAL_MS:2000}
  sql-max-chars: ${AUDIT_SQL_MAX_CHARS:8192}
  effective-pull:
    enabled: ${AUDIT_EFFECTIVE_PULL_ENABLED:true}
```

`enabled=false` → NoopRecorder，不创建 ES 客户端、不起线程。

## 5. 接入点

### 5.1 mask-core（service = `sql-mask`）

| 控制器 | 事件 | 记录内容 |
|---|---|---|
| `RewriteController` | REWRITE ×1/请求 | subject 进 actor；成功：拼接 SQL + statementCount + masked/rowFiltered 任一；失败：error.code/message。控制器内 try/catch 记录后**原样重抛** |
| `PolicyAdminController` | ADMIN_CHANGE | 建实例 / REPLACE_TABLES / 删实例 / 建改删策略；经 `AuditAdminHelper` 包装 |
| `UdfController` | ADMIN_CHANGE | UDF 注册 / 替换 / 删除（action=REGISTER/UPDATE/DELETE） |
| `MetadataImportController` | ADMIN_CHANGE | action=IMPORT、resourceType=TABLES、detail.sourceInstance |
| `EffectiveConfigController` | EFFECTIVE_PULL | instance + 请求 subject 进 actor；受 `audit.effective-pull.enabled` 开关 |

`authKind` 判定：复用 `PolicyApiKeyFilter` 的口径——请求过了 admin/data key
校验即 `API_KEY`；实现上 filter 校验通过后在 request attribute 打标
（如 `audit.authKind`），控制器侧只读不判。

### 5.2 mask-metadata（service = `mask-metadata`）

管理面全部变更接入，经同一 `AuditAdminHelper`：

- `MetadataAdminController`：实例创建 / 更新 / 删除（action=CREATE/UPDATE/DELETE）；
- `MetadataYamlImporter` 所在的导入端点：action=IMPORT，detail `{tableCount}`；
- `CollectController`：action=COLLECT，detail `{engine, database, tableCount,
  columnCount}`。

数据面读取（`MetadataDataController` 的结构下发）**不记**（见 1.1）。

### 5.3 量级提示

引擎按版本轮询 `/api/effective`，EFFECTIVE_PULL 事件量随引擎数 × 轮询频率线性
增长。v1 全量记录，`audit.effective-pull.enabled` 留退路；将来量大可改「仅
config_version 变化时记录」。

## 6. 查询 API（仅 mask-core）

路径 `/api/audit/**`，纳入 `PolicyApiKeyFilter` 的**管理面 key**（`requiredKey`
增加 `/api/audit` 前缀分支；key 未配置则放行，与现有行为一致）。

`GET /api/audit/events`

| 参数 | 说明 |
|---|---|
| `eventType` / `outcome` / `instance` / `resourceType` / `action` / `user` | 可选，keyword 等值过滤（user 匹配 `actor.user`） |
| `from` / `to` | 可选 ISO-8601 时间范围；缺省 `to`=now、`from`=to-24h；**跨度上限 7 天**，超出 400 |
| `page` / `size` | 分页；`size` 默认 50、**上限 200**，`page` 从 0 起 |

响应 200：

```json
{ "total": 123, "page": 0, "size": 50,
  "events": [ { ...第 3 节文档原样... } ] }
```

- 按 `@timestamp` 倒序；`events` 为 ES `_source` 原样（不再二次裁剪）；
- ES 不可达返回 502：`{ "code": "AUDIT_SEARCH_UNAVAILABLE", "message": ... }`；
- **不做 DSL 透传**、不提供聚合端点——自由探索走 Kibana；
- mask-audit 提供 `AuditSearchClient`（条件 → bool query → 命中/total 映射），
  controller 只做参数校验与 DTO 组装。

## 7. 部署与保留

- `docker-compose.metadata.yml` 增加单节点 ES 服务（`security` 关闭、
  `ES_JAVA_OPTS=-Xms512m -Xmx512m`、内存上限 1g、数据卷），本地开发套
  一条命令起齐；
- 两个服务的容器/进程通过 `AUDIT_ES_URL` 指向 ES；缺省 `127.0.0.1:9200`
  覆盖 jar 裸跑场景；
- **保留策略不在服务端管理**：按天索引支持部署侧按天清理（ILM / curator /
  脚本均可）；文档给一份可选 ILM 策略示例（如 30 天删除），模板 v1 不绑；
- 远程主机 ES：联调或长期验证时可在远程主机部署 ES（同版本 8.x），服务侧
  仅需 `AUDIT_ES_URL`（及凭据，若启用安全特性）指向它；compose 里的本地
  ES 仅是默认便利项，不是依赖前提。

## 8. 测试策略

- **mask-audit 单测**（不起真实 ES）：
  - `AuditEvent` → ES 文档 JSON 映射、SQL 截断打标；
  - 队列满丢弃计数、批量/定时 flush 触发、停机排空；
  - 失败限频 WARN（窗口内一条）、恢复 INFO；
  - 索引名按 UTC 日期滚动、模板 PUT 幂等；
  - `AuditSearchClient`：条件→query 组装、响应→DTO 映射、时间跨度/size 边界。
  - ES 交互用 JDK 内置 `com.sun.net.httpserver.HttpServer` 起假 ES：断言
    `_bulk` NDJSON 载荷形状、模板 PUT 路径与体、search 响应解析。**零新测试
    依赖**（不引入 testcontainers）。
- **接入侧单测**（stub recorder 注入）：
  - RewriteController 成功/失败路径各发一条、字段正确、业务异常原样重抛；
  - `AuditAdminHelper` SUCCESS/FAILURE 两路径、不吞异常、计时合理；
  - EffectiveConfigController 开关关闭时不发事件；
  - PolicyApiKeyFilter 打标 authKind、`/api/audit` 纳入 admin key 管控。
- **集成冒烟（手动，沿用仓库 curl 冒烟工具风格）**：
  - 本地：`docker compose -f docker-compose.metadata.yml up`（含 ES）→ 起
    mask-core → 走一遍 改写/建实例/建策略/拉生效 → Kibana 或查询 API 验证事件
    落地与查询过滤；
  - 远程主机 ES：`AUDIT_ES_URL` 指向远程 → 同上矩阵 + 停 ES 验证业务不受影响、
    恢复后写入恢复。
- README 增补「审计日志」章节（配置表、查询 API、compose 用法、ILM 示例）。

## 9. 错误处理语义汇总

| 场景 | 行为 |
|---|---|
| ES 未启动/不可达 | 启动正常；写入丢弃计数 + 限频 WARN；查询 API 502 |
| 队列满 | 丢弃该事件 + 计数（业务无感） |
| bulk 部分 item 失败 | 按整批失败处理（丢弃 + 计数） |
| 模板 PUT 失败 | WARN + 60s 重试，写入继续（动态映射兜底） |
| `audit.enabled=false` | 全链路 Noop，零 ES 依赖 |
| 服务停机 | 排空队列最多 5s |
| 审计代码自身异常 | `record()` 内部吞掉 + 计数，绝不影响业务请求 |
