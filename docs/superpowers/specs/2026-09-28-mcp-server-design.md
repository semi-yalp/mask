# MCP Server：把 sql-mask 能力以 MCP tools 形式提供给 AI 客户端

日期：2026-09-28
状态：已评审（用户批准设计方向，spec 待确认）
路径：brainstorming → 本 spec → writing-plans

## 背景与动机

sql-mask 的改写/校验能力目前有三种消费形态：REST（mask-core `/api/rewrite`）、
CLI（同一 jar）、管理台页面。AI 客户端（Claude Desktop、ZCode 等）要用这些能力，
只能靠 agent 自己发 HTTP 或让用户切到浏览器——上下文断裂，且 agent 无法在对话内
直接拿到「这条 SQL 改写后长什么样、命中了哪些策略」。

MCP（Model Context Protocol）是 AI 客户端调用外部工具的标准协议。以 MCP server
形式暴露 sql-mask，让 agent 在对话中原生调用脱敏改写（只读工具面）与受控查询
（mask-query 执行面），是 REST/CLI 之外的第四种消费形态，与前三种共用同一内核。

## 已确认决策

| 决策点 | 结论 |
|---|---|
| 使用场景 | 两个面都要：改写工具面（agent 开发/调试用）+ 查询面（agent 走 mask-query 拿脱敏数据），分批交付 |
| 实现载体 | 新增 Maven 模块 `mask-mcp-server`，进程内直调 `RewriteEngine`，**零 Spring**（与 mask-engine 同纪律） |
| SDK | 官方 MCP Java SDK 模块化坐标 `io.modelcontextprotocol.sdk:mcp-core` + `mcp-json-jackson2`（2.0.x；旧单体 `mcp` artifact 已废弃，不用） |
| 传输 | 双模式：`stdio`（本地客户端拉起）+ Streamable HTTP（`--transport http --port 8084`，团队共享） |
| 查询面治理 | `run_masked_query` **只允许 instance 模式**（策略服务端编译），agent 不能自带 `policyYaml` 进查询面；`maxRows` 必填且钳服务端上限 |
| 首期不做 | 跨实例批量改写、异步查询、Go 版 MCP server（Go 移植线 M1 只有语法面，无改写内核；等内核就绪可平行提供，不影响本设计） |

被否决的替代方案：spring-ai-mcp 内嵌 mask-core（多一条服务职责，stdio 本地模式
不好做）；Node/Python 薄代理包 REST（多一层部署与类型映射，进程内调用的零拷贝
优势没了）。

## 目标 / 非目标

**目标**

- 新模块 `mask-mcp-server`：stdio 与 Streamable HTTP 双传输，进程内调用
  `RewriteEngine` 完成改写/校验，HTTP client 调用 mask-query 完成受控查询；
- 工具面分两批：P1 改写面（只读、零执行风险），P3 查询面（有执行边界，依赖
  mask-query instance 模式联调）；
- 错误契约对齐 `ApiError(code, message, details)`，agent 可编程消费错误码；
- 交付 docker-compose 与 Claude Desktop / ZCode 配置片段。

**非目标**

- 不在 MCP 层重复实现任何治理（改写不可绕过、行数上限、超时取消全部由
  mask-query 既有机制保证）；
- 不暴露策略管理面（8081）与元数据采集（8082）的管理操作——首期工具面不含
  写操作，管理面继续走管理台；
- 不做跨方言转写、不执行业务 SQL（改写面工具纯只读）。

## 总体架构

```
┌────────────────────────── mask-mcp-server（新模块，零 Spring）──────────────────────────┐
│                                                                                          │
│  McpServer (官方 SDK, mcp-core)                                                          │
│    ├─ transport: stdio  ──────────► 本地 AI 客户端（Claude Desktop / ZCode）             │
│    ├─ transport: Streamable HTTP ─► 团队共享（挂 mask-common ApiKeyFilter，端口 8084）    │
│    │                                                                                      │
│    ├─ rewrite_sql / validate_config / list_dialects / rewrite_instance (P3)              │
│    │     └─进程内──► mask-engine RewriteEngine（与 mask-core Controller 同源调用）         │
│    └─ run_masked_query (P3)                                                              │
│          └─HTTP──► mask-query POST /api/v1/query（instance 模式，改写不可绕过）           │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

依赖方向：`mask-mcp-server → mask-engine`（改写面）、`mask-mcp-server → mask-common`
（ApiKeyFilter、ApiError；mask-common 若含 Spring 依赖则只引用其中的纯类，构建时
验证）。`StatementRewrite` 已带 Jackson 注解（`@JsonProperty("unchanged")`），
序列化无需自建映射层。

## 工具契约

### P1：改写面（进程内，只读）

**`rewrite_sql`** —— 对齐 `/api/rewrite` 的内联模式。

入参（JSON Schema，全部必填除注明）：

| 参数 | 类型 | 说明 |
|---|---|---|
| `metadataYaml` | string | 表结构 + 列策略 + rowFilter（旧格式） |
| `sql` | string | 一条或多条分号分隔的 SQL |
| `dialect` | string | 五方言之一（见 `list_dialects`） |
| `policyYaml` | string，可选 | Ranger 式策略文件；非空时要求 metadata 内无内嵌策略，与 REST 行为一致 |
| `user` / `groups` | string / string[]，可选 | 查询主体（策略匹配与审计用）；缺省 anonymous |

出参：`statements` 数组（`ordinal`、`originalSql`、`rewrittenSql`、`masked`、
`rowFiltered`、`kind`、`unchanged`；`inheritedColumns`/`inheritedTables` 仅写语句
出现）+ `rewrittenSql`（合并脚本）——与 `RewriteResponse` 同构。

**`validate_config`** —— 对齐 PoliciesParseController：入参 `metadataYaml` +
可选 `policyYaml`，出参逐条错误（路径 + 行号 + 消息）或「合法」；不碰 SQL。

**`list_dialects`** —— 对齐 DialectRegistry：返回五方言名 + 各自能力清单
（类型系统、标识符策略等），供 agent 在调 `rewrite_sql` 前枚举合法 `dialect`。

### P3：instance 改写 + 查询面

**`rewrite_instance`**（instance 模式改写）——入参 `instance` + `sql` + `dialect`
+ 可选主体，行为对齐 mask-core instance 模式。**前置依赖**：`PolicyServiceConfigSource`
与 `InstanceConfigSources` 目前在 mask-core 且为 Spring 组件，P3 需先把
`PolicyServiceConfigSource` 下沉到 mask-engine（它是纯 HTTP client + 缓存，下沉
成本低；`@Scheduled` 轮询改为 MCP 侧自管线程）。P1 不受影响。

**`run_masked_query`** —— 对齐 mask-query `POST /api/v1/query`：

| 参数 | 类型 | 约束 |
|---|---|---|
| `instance` | string | 必填，只走 instance 模式；**不接受 `policyYaml`** |
| `sql` | string | 必填；只允许 SELECT（mask-query 既有校验，非 SELECT 拒绝） |
| `maxRows` | number | 必填且为正；MCP 层钳制到服务端硬上限 |
| `user` / `groups` | string / string[] | 见安全边界 |

出参：脱敏后结果集（列名 + 行数据）+ 截断标志。超时/取消/审计全部沿用
mask-query 既有机制，MCP 层只透传。

## 安全与治理边界

- **主体解析沿用 mask-query 原则**：HTTP 模式下 ApiKey 解出的 principal 优先，
  MCP 参数里的 `user` 仅作 fallback，真实主体进审计；stdio 本地模式信任桌面
  用户，主体取参数值。
- **凭据边界**：数据源凭据只在 mask-query 一侧；mask-mcp-server 的配置仅
  `MASK_QUERY_URL` + API Key（P3）与 `SQLMASK_ADMIN_API_KEY` 之外的自有
  API Key（HTTP 模式鉴权，独立于改写服务的 admin key）。
- **查询面只读**：`run_masked_query` 的执行不可绕过改写（mask-query 保证），
  MCP 层不做第二次实现，也不提供任何绕过参数。

## 错误处理

`ApiError(code, message, details)` → MCP tool result `isError: true` +
`structuredContent`（保留 code/details 原样）。`CONFIG_ERROR`、`STRICT_DEGRADED`
、方言/主体错误等错误码与 REST 面逐一对应，agent 可按 code 分支处理。改写失败
逐语句报告（`ordinal` + 错误），与 CLI 行为一致；tool 描述文本里写明常用错误码
含义，降低 agent 误用。

## 配置

| 配置 | 传输 | 说明 |
|---|---|---|
| `--transport stdio\|http` | 两者 | 缺省 stdio |
| `--port` | http | 缺省 8084 |
| `MASK_MCP_API_KEY` | http | Streamable HTTP 的 ApiKeyFilter 凭据；未配置时默认拒绝（与 admin cache refresh 同原则） |
| `MASK_QUERY_URL` / `MASK_QUERY_API_KEY` | P3 | mask-query 地址与凭据 |

## 测试策略

- **单测**：tool handler ↔ `RewriteEngine` 映射（含每方言至少一条改写 fixture）、
  错误码转译、`maxRows` 钳制逻辑（P3）；
- **协议集成**：用 SDK 自带 client 对 stdio 与 HTTP 各跑一次 round-trip
  （initialize → tools/list → tools/call），防止 schema 序列化回归；
- **查询面**：mock mask-query（wiremock 或手写 handler）验证 instance 透传、
  主体 fallback、超时透传；
- **鉴权**：HTTP 模式无 Key 拒绝、错 Key 拒绝、对 Key 放行。

## 部署与分发

- P2 交付 `docker-compose.mcp.yml`（HTTP 模式 + ApiKey）；
- README 新章节「MCP 接入」：Claude Desktop / ZCode 的 stdio 配置片段、
  HTTP 模式接入说明、工具清单与错误码表；
- Maven 打包 shade/launcher 单 jar（与 sql-mask.jar 同风格，`--transport` 切换）。

## 分期

| 阶段 | 内容 | 验收 |
|---|---|---|
| P1 | 模块骨架 + `rewrite_sql` / `validate_config` / `list_dialects`（stdio） | ZCode/Claude Desktop 本地可改写五方言 SQL；单测 + stdio round-trip 绿 |
| P2 | Streamable HTTP + `ApiKeyFilter` + docker-compose + README 章节 | HTTP round-trip 绿；无 Key/错 Key 拒绝 |
| P3 | `PolicyServiceConfigSource` 下沉 mask-engine；`rewrite_instance` + `run_masked_query` + mock 联调 | 查询面走通 instance 模式，主体进审计，maxRows 钳制生效 |

## 开放问题

无（SDK 版本以实现时 Maven Central 最新 2.0.x 为准；`mask-common` 对零 Spring
模块的依赖可用性在 P1 骨架时验证：`ApiKeyFilter`/`ApiError` 若因 Spring 传递依赖
不可复用，MCP 侧自带语义等价实现——同样的 `X-Api-Key` 头、同样的「未配置 Key 默认
拒绝」原则、同样的错误体结构，不引模块依赖）。
