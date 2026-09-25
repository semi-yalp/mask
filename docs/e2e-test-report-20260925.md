# 端到端测试报告 — 本机 jar 部署（H2 平台库 + 远程 PostgreSQL 数据引擎）

> 测试日期：2026-09-25 · 被测版本：origin/main @ `b08b514`（arch-v2 + rr 合并后）
> 部署形态：`mask-server/target/sqlmask-server.jar` 裸 jar 启动（`--server.port=18080`）
> 平台存储：**内嵌 H2 文件库**（零配置回退，`./data/sqlmask*`）
> 数据引擎：**远程 PostgreSQL 16**（47.100.166.158 crm 库，经 SSH 隧道 `127.0.0.1:15432` 访问）
> 结论：**19 项测试全部通过**；过程中发现并当场修复 4 个缺陷（见 §4，均已随 `b08b514` 入库）。

## 1. 环境与部署

| 项 | 值 |
|---|---|
| 启动方式 | `java -Xmx768m -jar sqlmask-server.jar --server.port=18080` |
| 平台库 | H2 文件模式（`jdbc:h2:file:./data/sqlmask;MODE=PostgreSQL`）——零外部依赖 |
| 审计 | `AUDIT_ENABLED=true`，`AUDIT_STORE=jdbc`（共享 H2 库 audit_event 表） |
| 认证 | `mask.auth.mode=none`（默认无认证） |
| 数据引擎 | 远程 PG16：crm 库（customer 50 行 / orders / archive / other + **本次自行导入 vip_guest 12 行**），7 个 mask_* UDF 就绪 |
| 网络路径 | SSH 隧道 `15432→远端5432`（PG 不暴露公网） |
| 前端 | dist 置于 `./static/`，`GET /` 返回 200（`file:./static/` 静态托管，本次新增能力） |
| 凭据 | `SQLMASK_PG_PASSWORD` 环境变量（passwordRef 白名单约定） |

## 2. 测试结果矩阵

| # | 用例 | 结果 | 关键证据 |
|---|---|---|---|
| E2E-01 | 服务启动/健康/前端 | ✅ | health UP；`/api/auth/mode` → `{"mode":"none"}`；index 200 |
| E2E-02 | 实例登记 + 在线采集 | ✅ | 登记(127.0.0.1:15432/crm, passwordRef)；采集 **5 表 29 列**，版本 1→2 |
| E2E-03 | 分类分级自动识别 | ✅ | 扫 29 列→**识别 15**（高7/中6/低2）：phone→CONTACT/HIGH、id_card→IDENTITY/HIGH、address→LOCATION/LOW |
| E2E-04 | UDF 引擎发现导入 | ✅ | introspect 远程 pg_proc：**created 9**（mask_phone/email/name/idcard/text…），pgcrypto bytea 系 20 个正确 skip |
| E2E-04+ | 同族类型重载补充 | ✅ | 列类型 varchar(20)/varchar(100)/varchar(50) 与 UDF 签名精确匹配后 4 条脱敏策略建成 |
| E2E-05 | 实例改写（读取脱敏包装） | ✅ | `SELECT id,mobile…FROM vip_guest` → 外层 `mask_phone(r.mobile,3,4)/mask_email/mask_name`，内层原文不动 |
| E2E-06 | 受控查询-真库脱敏 | ✅ | 远程 PG 真执行：`13912345678→139****5678`、`林晚秋→林*秋`、`wq.lin@→w***@`，masked=true |
| E2E-06b | 行过滤-bob（customer id≤25） | ✅ | 50 行→**25 行**，rowFiltered=true，max_id=25 |
| E2E-06c | 行过滤-alice（vip gold） | ✅ | 12 行→**4 行**（恰好 4 条 gold） |
| E2E-06d | 匿名对照 | ✅ | customer 全量 50 行，无过滤 |
| E2E-07 | 复制表策略继承 | ✅ | CTAS：phone 继承列干净写入；**自动注册 `auto.inherit.customer_bak.phone` 策略**（带 `*` 主体）+ 目标表结构自动登记；远端真实执行干净 SQL 后经网关读 customer_bak → `138****4517` 读取侧脱敏生效 |
| E2E-08 | 方言能力开关 TOP | ✅ | 默认拒绝（PARSE_ERROR "TOP is not enabled"）；实例 `topN:true` 后解析为 `FETCH NEXT 5 ROWS ONLY` 并成功执行（⚠️ 见 DEF-01） |
| E2E-09 | 统一授权 grants | ✅ | alice 表级 SELECT → `GRANT SELECT ON "crm"."public"."vip_guest" TO "alice";`；analysts 组 → `CREATE ROLE "g_analysts"` + 授权；矩阵 API 正确 |
| E2E-10 | 审计（JDBC 存储） | ✅ | QUERY=7 / ADMIN_CHANGE=18 / REWRITE=3 全部可检索；事件含 authKind=ANONYMOUS、statementCount、masked |
| E2E-11 | 风控联动 | ✅ | 进程内桥消费审计事件：events total=7、flagged=2（多语句探测/元数据探测触发 SQLI 与 MASK_BYPASS 规则） |
| E2E-12 | Prometheus 指标 | ✅ | 165 个 `sqlmask_*` 指标：rewrite_requests/audit_jdbc/effective_compile/query_rewrite_bypass 等 |
| E2E-13 | 改写失败放行开关 | ✅ | PASSTHROUGH 实例查未登记表：改写 VALIDATION_ERROR → 原 SQL 执行（count=6），**`rewrittenBypassed:true`**，计数器=1 |
| E2E-14 | 负面-写语句 | ✅ | INSERT → 400 `WRITE_STATEMENT`（结构化错误体） |
| E2E-14+ | 负面-多语句 | ✅ | `SELECT 1; SELECT 2` → 400 `MULTI_STATEMENT` |
| E2E-14+ | 负面-截断 | ✅ | maxRows=2 → rowCount=2, **truncated=true**（多读一行探测） |

## 3. 与基线的差异说明

- 数据引擎为远程 PG（隧道），平台库为 H2 —— 两层存储分离正是本次架构目标；除网络延迟（查询 150-280ms）外与 PG 直连行为一致。
- REWRITE 审计事件曾在此前单体合并中丢失，本次 E2E 发现（DEF-05）并恢复——基线"每请求恰好一条 REWRITE 事件"契约重新成立。

## 4. 测试中发现并已修复的缺陷（全部随本次提交入库）

| # | 缺陷 | 根因 | 修复 | 回归 |
|---|---|---|---|---|
| DEF-02 | `audit.store=jdbc` 时审计检索端点误报 "audit disabled" | 检索端点只认 ES 客户端类型 | 抽象 `AuditSearch` 接口，jdbc/es 双实现，端点按接口注入 | AuditQueryEndpointTest 绿 |
| DEF-03 | PASSTHROUGH 放行开关不生效 | 进程内改写抛 SqlMaskException，网关只捕获 QueryException | 网关适配器按 HTTP 客户端同语义转换（rewritePhase=true） | E2E-13 + QueryServiceTest |
| DEF-04 | 负面用例返回 500 而非结构化 400 | 多个 @RestControllerAdvice 均有无序兜底 handler，互相劫持 | 各域 advice **作用域化**（metaserver/policyserver/riskserver/query）+ 显式 @Order，server 兜底降为 ORDER 100 | 全部负面用例 400 |
| DEF-05 | 实例改写路径 REWRITE 审计事件与指标缺失 | 单体合并时 InstanceRewriteService 未接审计 | 恢复"恰好一条 REWRITE 事件"（成功/失败 alike）+ RewriteMetrics（masked 标签取真实语句集） | E2E-10/审计检索 |
| 加固 | 放行开关可被栈叠语句利用 | `isPlainRead` 只看前缀，"SELECT 1; SELECT 2" 可绕过 | 含内部分号的语句一律不放行 | QueryServiceTest 9 绿 |
| 改进 | 裸 jar 部署无法托管前端 | static-locations 仅 classpath | 增 `file:./static/`（dist 放运行目录旁即可） | index 200 |

## 5. 遗留缺陷（未修复，已记录）

| # | 缺陷 | 复现 | 影响 |
|---|---|---|---|
| DEF-01 | **TOP + ORDER BY 渲染顺序错误**：改写产物 `…FROM vip_guest FETCH NEXT 3 ROWS ONLY ORDER BY id`（FETCH 在 ORDER BY 前），PG 报语法错误 42601 | 实例 topN=true + `SELECT TOP (3) … ORDER BY …` | TOP 与 ORDER BY 同用失效（TOP 单用正常）。根因疑似 SqlMaskTopN 把 fetch 挂在内层 select 而 ORDER BY 包装在外层 SqlOrderBy。需改 mask-sqlparser codegen 模板或渲染前把 fetch 移到 SqlOrderBy 节点 |

## 6. 过程事件（非产品问题）

- 测试开始前远程主机 sshd 曾无响应（前次 245MB jar 传输诱发小内存主机僵死），由用户云控制台重启恢复；本次改用 SSH 隧道后不再向远端传大文件。
- 本机曾检出 3 个遗留旧微服务进程（policy-server/metadata/query 8081-8083）与一个持锁的旧 sqlmask-server 进程，已清理。

## 7. 结论

模块化单体在"H2 平台库 + 远程 PG 数据引擎"形态下，端到端功能全部可用：元数据采集、分类分级、UDF 发现、脱敏改写、受控查询、行过滤、策略继承、授权、审计、风控、指标、放行开关、负面防护——**19/19 通过**。测试中发现的 4 个缺陷已当场修复并回归（`b08b514`），1 个已知缺陷（DEF-01 TOP+ORDER BY）留档待修。
