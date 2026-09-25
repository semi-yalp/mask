# sql-mask（arch-v2 单体架构）

基于 Apache Calcite 的多引擎 SQL 脱敏与数据安全平台。改写内核以「原始查询作为内层、
最外层对结果列调用脱敏 UDF」的方式改写 SELECT / INSERT…SELECT / INSERT OVERWRITE /
CTAS，只做解析、校验、血缘分析和 SQL 输出，从不执行业务 SQL；查询网关是唯一的受控
执行面，只执行改写产物。

**arch-v2 起为模块化单体**：一个 jar = 全部 API + Web 控制台（CLI 同 jar 双模），
一个容器即可运行；mask-engine 内核保持纯库（零 Spring，enforcer 强制），可独立
嵌入查询引擎。

## 快速开始

```bash
# 方式一：docker compose 一键（推荐）
export MASK_STORAGE_PG_PASSWORD='change-me'
docker compose up -d --build          # http://<host>/

# 方式二：本机零配置裸跑（内嵌 H2,无需任何外部依赖）
mvn -pl mask-server -am package -DskipTests
java -jar mask-server/target/sqlmask-server.jar   # http://127.0.0.1:8080
```

默认**无认证**、H2 文件库、审计入 JDBC。开启认证（simple 本地用户 / LDAP）与生产
配置见 **[docs/deployment.md](docs/deployment.md)**。

## 模块拓扑

| 模块 | 形态 | 职责 |
|---|---|---|
| mask-engine | 纯内核库 | 方言/血缘/行过滤/改写管线；**零 Spring**（enforcer 强制） |
| mask-sqlparser | 纯库 | Calcite 1.42 自研语法：INSERT OVERWRITE、`SELECT TOP (n)` |
| mask-policy | 纯库 | Ranger 式策略模型与匹配 |
| mask-metadata | 域库 | 实例登记/结构采集/**分类分级** |
| mask-policy-admin | 域库 | 策略管理面/**UDF 中心（发现·部署·重同步）**/生效编译 |
| mask-query | 域库 | 查询网关：**Submitter SPI**（jdbc/http）、护栏、改写失败放行开关 |
| mask-risk | 域库 | 风控引擎/告警/UEBA/一键阻断（进程内消费审计事件） |
| mask-audit | 库 | 审计事件：**store=jdbc(默认)/es/none**；进程内风控转发 |
| mask-auth | 库 | **认证可选化：none(默认)/simple/ldap** + 集中 Bearer 门禁 |
| mask-common | 库 | 统一错误体/跨域契约/指标 |
| **mask-server** | **应用** | 单体组合 + CLI + **RewriteContextRepository**（改写零 HTTP）+ **统一授权 grants** |
| frontend/ | SPA | Vue3 控制台（打进 jar 或 nginx 托管），单源 `/api` |
| bench/ | 基准 | TPC-DS 全量 99 查询 × 脱敏/行过滤/组合 三模式矩阵 |

## 核心能力

- **SQL 改写**：五方言 + 实例级 `topN`/`insertOverwrite` 能力开关；fail-closed；
  TPC-DS 字节级 golden 回归。
- **行过滤**：白名单条件注入派生表，覆盖 JOIN/CTE/UNION/写语句源查询。
- **改写失败放行（可选）**：实例 `onRewriteFailure=PASSTHROUGH` 时纯读语句以原 SQL
  继续，响应/审计/指标全程留痕；写语句绝不放行。
- **复制表策略继承（inheritOnCopy，可选）**：列级策略声明 `inheritOnCopy: true` 后，该列的 CTAS / INSERT…SELECT / INSERT OVERWRITE 复制不再脱敏写入——目标表得到干净数据，改写服务自动在策略与元数据域注册目标表列策略（带审计与全量冲突检查，任一步失败即改写失败）；inline YAML / CLI 通道无注册能力，遇继承语句显式拒绝。
- **分类分级**：列名启发式自动识别 + 手工修正，沉淀元数据层。
- **UDF 中心**：从 PG/MySQL 引擎发现函数导入、内置脱敏模板一键部署、注册表重同步。
- **统一授权**：主体×资源×权限集中登记，编译 PG/MySQL GRANT DDL（预览/应用），矩阵查询。
- **受控查询**：`POST /api/v1/query` 只执行改写产物；实例可选 `submitter=jdbc|http`。
- **风控**：16 内置规则 + 自定义 DSL + UEBA 基线 + 一键阻断（行过滤 `1=0`）。
- **审计**：JDBC/ES 双存储，进程内直投风控。

## REST 速览

| 端点 | 说明 |
|---|---|
| `POST /api/rewrite`、`POST /api/rewrite/instances/{name}` | SQL 改写（inline/实例） |
| `/api/meta/instances/**` | 元数据实例/采集/导入 |
| `/api/classification/**` | 分类分级（overview/CRUD/auto） |
| `/api/instances/**`（策略域） | 实例/策略/UDF/生效配置；`/udfs/import|deploy|resync|templates` |
| `/api/instances/{i}/grants/**`、`/api/grants/matrix` | 统一授权 |
| `POST /api/v1/query` | 受控查询 |
| `/api/risk/**` | 风控（规则/告警/事件/资产/阻断/Demo） |
| `/api/audit/events` | 审计检索 |
| `/api/auth/login|me|mode`、`/api/auth/users` | 认证（模式探测/登录/用户管理） |

## 文档

- 部署：**docs/deployment.md**（compose 一键/裸跑/环境变量全表/认证开启/迁移）
- 功能清单：docs/功能清单-v2.md（对照重构前基线 docs/baseline-inventory-20260924.md）
- 已知问题与待确认：docs/known-issues-v2.md
- 历史设计文档：docs/superpowers/{specs,plans}/、docs/refactor-plan.md 等

## 构建

```bash
mvn verify     # 后端全部测试 + 内核纯度 enforcer + 嵌入式 PG 端到端
cd frontend && npm test && npm run build
```

License: MIT
