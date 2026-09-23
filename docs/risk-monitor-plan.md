# sql-mask 风险监控与预警系统（Risk Monitor）设计方案

> 版本 v1.0（2026-09-24）｜状态：已实施（Demo 可运行）
> 定位：在现有 sql-mask 脱敏改写平台之上，构建一套**面向 SQL 访问链路的数据安全风险监控 / 预警系统**，
> 覆盖「事件采集 → 规则检测 → 风险评分 → 告警收敛 → 可视化运营」全链路。

---

## 1. 背景与目标

sql-mask 已经具备：

- **改写数据面**：`/api/rewrite`（mask-core 8080）与 `/api/v1/query`（mask-query 8083）承载所有 SQL 访问；
- **审计管道**：`mask-audit` 模块以 `AuditRecorder` SPI 异步落 Elasticsearch（REWRITE / QUERY / ADMIN_CHANGE / EFFECTIVE_PULL 四类事件，含 user/ip/SQL/脱敏标记/错误码）；
- **敏感列配置**：policy-server（8081）中 DATAMASK 策略 + mask-lite YAML 均以「列」为粒度声明敏感数据。

缺失的是**消费侧**：审计数据只落库不分析，敏感列被谁、何时、以何种频率访问，是否存在注入探测、脱敏旁路、批量拖库，无人知晓。

本方案补齐这一层，目标：

| 目标 | 说明 |
|---|---|
| 实时检测 | 每次 SQL 访问（改写/执行）经过规则引擎，秒级产出风险命中与评分 |
| 内置规则 | 开箱即用：SQL 注入探测、敏感列高频访问、失败风暴、非工作时间、大结果集、SELECT *、脱敏旁路、新主体首访等 |
| 自定义规则 | 控制台可视化配置：条件表达式（字段/运算符/值）+ 滑动窗口阈值，无需改代码 |
| 告警收敛 | 按规则×主体分组去重、冷却窗口，避免告警风暴；支持确认/解决闭环 |
| 可视化 | 风险大盘（趋势/分布/Top 榜）、告警中心、事件流、规则管理、敏感资产分级 |
| 可运行 Demo | 与现有系统（mask-core / policy-server / 前端控制台）一键联动 |

## 2. 业界方案对标

设计借鉴了以下成熟系统的模式，而非重新发明：

| 系统 | 借鉴点 |
|---|---|
| **Apache Ranger** | 审计事件模型（who/when/what/resource/outcome）、策略中心与访问审计同域管理、Admin 控制台信息架构（本控制台延续 Ranger 风格） |
| **Imperva SecureSphere / 数据库防火墙** | SQL 注入特征库（永真条件、UNION 探测、堆叠语句、延时盲注、注释截断、元数据探测、混淆载荷）、异常行为基线（访问频率/结果集大小/时间窗口） |
| **ModSecurity CRS / WAF** | 规则=「特征模式 + 严重级别 + 处置动作」三元组，规则可开关、可调参、可自定义 |
| **Elastic SIEM / detection rules** | 检测规则与告警（Alert）分离：规则产出告警文档，告警有状态机（open → acknowledged → resolved） |
| **Prometheus Alertmanager** | 告警分组（group by 规则+主体）、抑制/冷却窗口（避免风暴）、多级别路由 |
| **Splunk UBA / UEBA** | 用户实体基线：新主体首次访问高敏资产、行为偏离（本版实现首访基线，频率基线列入路线图） |
| **阿里云数据安全中心 DSC** | 敏感资产分级（PII/金融/身份/联系方式）驱动风险判定，而非只看 SQL 文本 |

与「指标监控」（Prometheus/Grafana）的边界：本系统做**访问语义级**检测（SQL 内容、主体、资产敏感度），指标监控继续承载吞吐/延迟/错误率，两者互补。

## 3. 总体架构

```
┌──────────────┐   POST /api/rewrite    ┌──────────────┐
│  前端控制台    │ ─────────────────────▶ │  mask-core   │ 8080
│  (Vue3)      │                        │  改写引擎     │
│              │   POST /api/v1/query   └──────┬───────┘
│              │ ─────────────────────▶ ┌──────┴───────┐
└──────────────┘                        │  mask-query  │ 8083
                                        └──────┬───────┘
              mask-audit AuditRecorder SPI      │ (1) 审计事件
               ┌────────────────────────────────┤
               ▼                                ▼
        ┌──────────────┐                 ┌──────────────┐
        │ Elasticsearch │                 │ RiskForwarder │  ← 本期新增（mask-audit 内）
        │  审计长期存储  │                 │ 异步best-effort│
        └──────────────┘                 └──────┬───────┘
                                                │ (2) POST /api/risk/ingest
                                                ▼
        ┌───────────────────────────────────────────────────────┐
        │                  mask-risk-server (8084)               │
        │  ┌─────────┐  ┌──────────┐  ┌─────────┐  ┌──────────┐  │
        │  │ 事件接入  │▶│ 特征提取  │▶│ 规则引擎  │▶│ 评分/告警 │  │
        │  └─────────┘  │ 表/列/模式 │  │ 内置+自定义│  │ 分组/冷却 │  │
        │               └──────────┘  └────┬────┘  └────┬─────┘  │
        │  ┌──────────────┐   ┌──────────┐  │敏感资产    │        │
        │  │ 内存事件/告警库 │◀──│ 规则配置库 │◀─┘分级注册表 │        │
        │  └──────────────┘   └──────────┘  └──────────┘         │
        └───────────────────────────┬───────────────────────────┘
                                    │ (3) REST /api/risk/**
                                    ▼
                     前端：风险大盘 / 告警中心 / 事件流 / 检测规则 / 敏感资产
```

三个关键决策：

1. **接入点选择 `AuditRecorder` SPI 装饰器（`ForwardingAuditRecorder`）**
   所有服务（core/query/policy/metadata）已经把每请求审计事件交给该 SPI；在 mask-audit 自动装配处加一个可选中断转发（`risk.forward.url` 配置即启用，JDK HttpClient + 有界队列 + 守护线程，与 EsAuditRecorder 同款 best-effort 语义，故障绝不影响请求路径）。**零侵入**：业务代码一行不改。
2. **独立服务而非嵌入 mask-core**
   风险域（规则/告警/资产）与改写域（解析/策略/脱敏）生命周期不同：规则调参频繁、告警查询重、后续要接通知渠道。独立 8084 端口，遵循现有微服务拓扑与 fail-open/fail-closed 约定（风险面属运营面，API Key 未配置时 fail-open + WARN，与 policy-server 管理面一致）。
3. **存储先内存、接口抽象**
   Demo 与中小规模（≤ 5 万事件滚动窗口）用内存 + 淘汰；`RiskStore` 留接口，生产接 ES/PG（见 §9 路线图）。事件入参即 ES 文档结构（`@timestamp`/`actor.user`/`detail`…），迁移零成本。

## 4. 数据模型

### 4.1 RiskEvent（风险事件 = 审计事件 + 检测结果）

| 字段 | 来源 | 说明 |
|---|---|---|
| id / timestamp | 服务端 | 事件 ID、时间戳 |
| service / eventType / outcome / durationMs | 审计事件 | sql-mask / mask-query，REWRITE / QUERY / ADMIN_CHANGE / EFFECTIVE_PULL，SUCCESS / FAILURE |
| user / groups / authKind / sourceIp | 审计事件 actor | 访问主体 |
| dialect / statementCount / masked / rowFiltered | 审计事件 | 方言、语句数、**是否命中脱敏**、是否行过滤 |
| originalSql / rewrittenSql / sqlTruncated | 审计事件 | 原始与改写后 SQL（截断保护） |
| errorCode / errorMessage | 审计事件 | 失败码（PARSE_ERROR/VALIDATION_ERROR…） |
| instance / detail | 审计事件 | 实例名、扩展（rowCount 等） |
| tables / sensitiveColumns | 特征提取 | 涉及表、命中的敏感列（含敏感级别） |
| hits | 规则引擎 | `[{ruleId, ruleName, severity, evidence, category}]` |
| riskScore | 评分器 | 0–100 |

### 4.2 RiskRule（检测规则）

```
id / name / description / kind(BUILT_IN|CUSTOM) / category(SQLI|BEHAVIOR|COMPLIANCE|DATA_EXPOSURE|CUSTOM)
severity(INFO|LOW|MEDIUM|HIGH|CRITICAL) / enabled / params{} / createdAt / updatedAt
```

自定义规则额外带 **条件 DSL**（JSON，控制台可视化编辑）：

```jsonc
{
  "conditions": [                          // AND 组合
    { "field": "sql", "op": "regex", "value": "union\\\\s+select" },
    { "field": "user", "op": "in", "value": "svc_report,bi_sync" }   // 可选：仅对特定主体
  ],
  "window": {                              // 可选：滑动窗口阈值（缺省=单事件即判定）
    "seconds": 60, "count": 10, "groupBy": "user"   // groupBy: user|ip
  }
}
```

支持字段：`user / ip / eventType / outcome / dialect / masked / rowFiltered / sql / errorCode / rowCount / hour / instance / table / column`；
运算符：`eq / neq / contains / not_contains / regex / in / not_in / gt / lt / startswith / endswith`。

### 4.3 Alert（告警）

```
id / ruleId / ruleName / category / severity / title / description
user / sourceIp / sqlSnippet / eventIds[] / eventCount
status(OPEN|ACKNOWLEDGED|RESOLVED) / ackNote / createdAt / updatedAt / lastHitAt
```

**收敛策略**（借鉴 Alertmanager）：
- 事件级规则：severity ≥ `risk.alerting.min-severity`（默认 MEDIUM）才生成告警，更低只标记事件；
- 分组键 = `ruleId + user`：命中已存在的 OPEN 告警且在冷却窗口（默认 15 分钟）内 → 追加 eventId、eventCount+1、时间前移，**不新建**；
- 窗口类规则（高频访问/失败风暴）达到阈值时产一条告警，同样参与冷却。

### 4.4 SensitiveColumn（敏感资产分级注册表）

```
columnKey(catalog.schema.table.column) / sensitivity(HIGH|MEDIUM|LOW)
category(IDENTITY|PII|CONTACT|FINANCE|LOCATION|OTHER) / enabled / source(MANUAL|SEEDED)
```

- 驱动 SENSITIVE_BURST / SELECT_STAR / MASK_BYPASS / NEW_SUBJECT 等行为规则的判定；
- 与 policy-server 的 DATAMASK 策略互补：策略决定「怎么脱」，分级决定「访问它算多大风险」；
- Demo 预置 crm 样例（idcard/phone/bank_card=HIGH, email/address=MEDIUM…），支持手工增删与导入。

## 5. 规则体系（内置规则清单）

| ID | 名称 | 类别 | 级别 | 判定逻辑（可调参数） |
|---|---|---|---|---|
| `SQLI_TAUTOLOGY` | 永真条件注入 | SQLI | CRITICAL | `OR 1=1`、`OR 'a'='a'`、`OR TRUE` 等模式 |
| `SQLI_UNION` | UNION 注入探测 | SQLI | CRITICAL | `UNION [ALL] SELECT` 组合查询 |
| `SQLI_STACKED` | 堆叠语句注入 | SQLI | CRITICAL | 语句后追加 `; SELECT/INSERT/UPDATE/DROP/GRANT…` |
| `SQLI_COMMENT` | 注释截断注入 | SQLI | HIGH | 引号后 `--`/`#`/`/*` 截断注释模式 |
| `SQLI_TIME_BASED` | 延时盲注 | SQLI | CRITICAL | `pg_sleep` / `sleep(` / `benchmark(` / `waitfor delay` |
| `SQLI_META_PROBE` | 元数据探测 | SQLI | HIGH | `information_schema` / `pg_catalog` / `mysql.user` / `sys.` |
| `SQLI_OBFUSCATION` | 混淆载荷 | SQLI | HIGH | 长 16 进制字面量、`chr(0x..)` 链、`'||'` 拼接 |
| `SENSITIVE_BURST` | 敏感列高频访问 | BEHAVIOR | HIGH | 同一主体 window(60s) 内触碰敏感列 ≥ count(10) 次 |
| `FAILURE_BURST` | 失败风暴（探测/枚举） | BEHAVIOR | HIGH | 同一主体 window(60s) 内 FAILURE ≥ count(5) 次 |
| `OFF_HOURS_ACCESS` | 非工作时间访问 | COMPLIANCE | MEDIUM | 事件小时 ∉ [start(8), end(19)) |
| `LARGE_RESULT` | 大结果集拉取 | DATA_EXPOSURE | MEDIUM | detail.rowCount ≥ threshold(1000) |
| `SELECT_STAR_SENSITIVE` | 敏感表全列拉取 | DATA_EXPOSURE | MEDIUM | `SELECT *` 且涉及表含敏感列 |
| `MASK_BYPASS` | 敏感列未脱敏直出 | DATA_EXPOSURE | HIGH | 触碰 HIGH 敏感列但 `masked=false`（策略缺口/旁路） |
| `NEW_SUBJECT_SENSITIVE` | 新主体首访高敏列 | BEHAVIOR | LOW | 用户首次访问该 HIGH 敏感列（基线学习） |

评分：`riskScore = Σ severityWeight`（CRITICAL 40 / HIGH 20 / MEDIUM 10 / LOW 5 / INFO 2，封顶 100），事件级聚合到用户即「用户风险分」。

## 6. API 设计（mask-risk-server，前缀 /api/risk）

| 方法 & 路径 | 说明 |
|---|---|
| `POST /ingest` | 事件接入（单条或数组），同步跑规则引擎，返回命中摘要 |
| `GET /events` | 事件流查询：user/severity/ruleId/eventType/outcome/keyword/时间窗/分页 |
| `GET /stats/overview` | 大盘聚合：24h 概览、severity 分布、30 分钟桶时序、Top 用户/规则/敏感列、最近告警 |
| `GET/POST/PUT/DELETE /rules` | 规则 CRUD；`PUT /rules/{id}/enabled` 启停；`GET /rules/catalog` 内置规则文档目录 |
| `POST /rules/test` | 规则试运行（对最近 N 条事件 dry-run，返回命中数与样例，不产生告警） |
| `GET /alerts`、`PUT /alerts/{id}` | 告警查询 / 状态流转（ACKNOWLEDGED/RESOLVED + 处理备注） |
| `GET/POST/PUT/DELETE /sensitive-columns` | 敏感资产注册表 CRUD |
| `POST /demo/seed`、`POST /demo/simulate`、`POST /demo/reset` | 演示：生成 48h 真实流量 + 攻击剧本；按剧本即时注入；重置 |

鉴权：`RISK_API_KEY`（fail-open + WARN，管理面语义与 policy-server 一致）；配置 `risk.forward.url` 的转发端自动携带同 key。

## 7. 前端设计（延续 Ranger 风格控制台）

新增导航组「风险监控」（Warning 图标 + 飞出抽屉），五个页面：

1. **风险大盘 `/risk/dashboard`**
   顶部演示工具条（重置数据 / 攻击模拟下拉）+ 渐变 hero；
   指标卡：24h 事件、风险命中率、未处理告警（分级 pill）、平均风险分；
   图表：24h 事件/告警趋势（堆叠面积）、风险等级分布、Top 风险用户、Top 命中规则、敏感列访问 Top、最近告警流。
2. **告警中心 `/risk/alerts`**：severity/status/规则/关键字筛选，表格 + 详情抽屉（证据 SQL、命中事件跳转、确认/解决/备注闭环操作）。
3. **事件流 `/risk/events`**：全量事件（含风险分徽标、命中规则 tags），SQL 详情抽屉（原始/改写对照、命中证据）。
4. **检测规则 `/risk/rules`**：内置规则（文档抽屉 + 参数编辑 + 启停开关）与自定义规则（条件构造器：字段/运算符/值动态行 + 可选窗口阈值 + 试运行）统一管理。
5. **敏感资产 `/risk/assets`**：敏感列分级注册表（HIGH/MEDIUM/LOW 着色），增删改 + 策略导入。

图表实现：优先 ECharts（`npm i echarts`），离线环境回退自绘 SVG 组件（当前实现即为轻量 SVG，零新增依赖）。

## 8. 与现有系统的对接（本期落地）

| 接触点 | 改动 |
|---|---|
| `mask-audit` | 新增 `RiskForwardProperties` / `RiskForwarder` / `ForwardingAuditRecorder`；自动装配在 `risk.forward.url` 非空时以 @Primary 装饰既有 recorder。默认关闭（url 为空），行为零变化 |
| `mask-core` | 无代码改动（env `RISK_FORWARD_URL` 即启用）；`/api/rewrite`（Playground 使用的路径）事件自动进入风险链路 |
| `mask-query` | 同上（QueryAuditor → SPI → 转发） |
| 前端 | vite 代理与 nginx 增加 `/api/risk → 8084`；路由/侧边栏/设置页拓扑更新 |
| 根 pom | 聚合新模块 `mask-risk-server` |

## 9. 生产化路线（Demo 之后）

1. ~~**存储**：`RiskStore` → ES / PG~~ **已落地单机版（v1.2）**：`PersistingRiskStore`——文件快照持久化（`risk.store.persistence-path`，默认 `data/risk-store.json`）。规则（含自定义与参数修改/启停）、告警（含状态机/处置备注/合并事件）、敏感资产、首访基线、最近事件（`max-persisted-events` 条）与 ID 序列全部跨重启保留；变更标脏 → 3 秒节流 → tmp+原子 rename 落盘，启动时损坏文件降级为全新开始。阻断名单独立持久化（`risk.block.state-path`）。已修缺陷：告警状态流转/命中合并原为原地变更、绕过持久化标脏，现统一回写。**多实例/超大规模仍需 ES/PG 后端（路线图）**；
2. **实时性**：转发改 MQ（Kafka topic `risk.events`），风险服务消费，削峰 & 多实例水平扩展；（待办）
3. ~~**通知**：告警动作接 webhook / 钉钉企微 / 邮件~~ **已落地（v1.1）**：`NotificationSink`——新告警达到 `risk.notify.min-severity`（默认 HIGH）即产出通知：webhook 已配置则异步 POST 告警载荷（SENT/FAILED），未配置则本地记录（RECORDED），控制台「告警中心 → 通知记录」面板可查；
4. ~~**响应闭环**：告警 → 工单 → 处置~~ **已落地（v1.1）**：`BlockService` 一键阻断——从告警详情直接对风险用户在 policy-server 下发 ROW_FILTER「1 = 0」策略（每表一条，`risk-block-*` 前缀，优先级 10000），并回读生效配置验证编译下发；解除时只删除本服务创建的策略；告警自动转「已确认」并留处置备注；配置 `risk.block.base-url/api-key/default-instance`；
5. ~~**基线与 UEBA**：按用户×资产建立访问频率/时段/结果集基线~~ **已落地（v1.3）**：`BaselineService` + `UserProfile`——从持久化事件窗口自动学习每个主体的行为画像（基线速率/次每小时、24 小时活跃直方图、结果集中位数，TTL 缓存增量重算）；内置规则 `BEHAVIOR_BASELINE`（中危，可调参启停）检测三类偏离——**频率**（10 分钟突发 ≥8 次且折合速率 ≥5× 基线）、**结果集**（单次返回 ≥5× 个人中位数，需 ≥10 样本）、**时段**（≥30 次历史中从未出现的时段）；历史样本不足不判定（冷启动保护）。控制台大盘新增「用户行为基线」卡片（速率/突发徽标/活跃热力条）；演示场景 `baseline-deviation` 一键复现。老库升级自动合并新内置规则（保留用户已有调参/启停）。
6. **响应闭环**：告警 → 工单 → 处置（加策略/禁账号）→ 复盘报告；（部分完成：阻断已自动化，工单化待办）
7. **检测即代码**：规则导出 YAML 入 Git，CI 校验（参考 Elastic detection-rules 工程）。（待办）

> 演示环境拓扑说明：阻断联动的 policy-server 用独立内存实例（从稳定提交构建，跑在 8086，
> `RISK_BLOCK_POLICY_BASE_URL` 指向；内存数据重启即失，需重建 crm 实例），生产应指向正式
> policy-server（8081）并配置管理 Key。演示数据持久化在 `data/`（已 gitignore）；
> `POST /api/risk/demo/seed` 重建演示数据，`RISK_DEMO_SEED=false` 关闭首启自动播种。

## 10. 验收标准（本期）

- [x] `mvn -pl mask-audit,mask-risk-server test` 通过（规则引擎/评分/告警收敛/自定义 DSL/统计 单测）；
- [x] 前端 `vue-tsc` 构建通过，vitest 新增用例通过；
- [x] 端到端：Playground 提交可疑 SQL → mask-core 转发 → 风险服务命中规则 → 大盘/告警页可见；
- [x] Demo 剧本：`demo/seed` 一键生成 48h 流量与攻击场景，大盘图表非空。
