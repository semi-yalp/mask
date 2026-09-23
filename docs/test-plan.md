# sql-mask 测试计划

- **版本**：v1.0（2026-09-23）
- **编制基线**：`full-test` 分支工作区（后端与 main 基本一致，前端控制台重构 WIP 未提交）；已通读 `docs/test-coverage-gap-analysis-main-20260918.md`（99 条缺口清单）、`bench/tpcds-mask-lite/report/REPORT.md`、`docs/integration-test-report.md`、`docs/query-acceptance/golden-queries.md`
- **定位**：可执行的测试总纲——明确分层策略、各模块用例设计、跨服务专项、基建改造、排期与准出标准。用例级细节以 §5 各表为准，历史缺口清单（99 条）按 §10 排期分批消化，不在此重复罗列。

---

## 1. 被测系统与范围

### 1.1 系统组成

| 组件 | 定位 | 端口 | 测试现状 |
|---|---|---|---|
| mask-sqlparser | Calcite 定制解析器（fmpp+javacc 代码生成，babel 等价） | — | 7 个测试类，语料驱动 |
| mask-core | 改写引擎 + HTTP 服务（8080）+ CLI + 元数据内省（PG/MySQL/Trino） | 8080 | 70 个测试类，含 golden 字节级锁定、内嵌 PG |
| mask-lite | 独立纯改写 CLI（仅 PostgreSQL 方言，不依赖 sql-mask 模块） | — | 12 个测试类；TPC-DS 99×3 基准 99/99 |
| mask-policy | 策略模型库（PolicyEngine/GlobMatcher/模型守卫） | — | 8 个测试类 |
| mask-policy-server | 策略管理面（DATAMASK + ROW_FILTER、UDF 注册表、effective 下发） | 8081 | 20 个测试类，内嵌 PG |
| mask-metadata | 元数据采集与版本化存储（采集门：pg/mysql/trino；hive/sparksql 拒绝走 YAML 导入） | 8082 | 18 个测试类，内嵌 PG |
| mask-audit | 审计库（仅 Elasticsearch 后端 + Noop 兜底，异步尽力写） | — | 9 个测试类 |
| mask-query | 统一查询服务（改写→JDBC 执行→审计桥），6 引擎：PG/MySQL/StarRocks/Trino/Hive/SparkSQL | 8083 | 11 个测试类，含 MockMvc + 内嵌 PG + stub 上游 E2E |
| mask-build-tools | shade fat-jar 变换器（SpringFactoriesTransformer） | — | **0 测试** |
| frontend | Vue3 + Vite + Element Plus 控制台（访问管理/策略管理器/试验台/审计/设置） | 80(nginx) | 3 个 spec 文件 16 个用例（纯函数层）；**组件/路由/E2E 为零**；重构 WIP 未提交 |

部署件：根目录 5 个 docker-compose（metadata+ES、policy+PG、query-acceptance profile（mysql/trino/starrocks）、metrics/prometheus、frontend nginx）；nginx 按 `/api/instances`、`/api/effective`→8081，`/api/audit`→8080 路由，其余 SPA fallback。

### 1.2 范围

**范围内**：
1. 五方言（mysql/postgresql/trino/hive/sparksql）改写正确性、行过滤注入正确性、两者叠加语义；
2. 四个服务的管理面/数据面 REST 契约与错误码；
3. 全链路：前端 → 8081 策略 → 8080 改写 → 8083 执行 → ES 审计；
4. 安全承诺：脱敏泄露面、API key 认证、敏感信息不入日志；
5. 性能回归：TPC-DS 基准不劣化；
6. 部署形态：compose 栈可用、nginx 路由正确、fat-jar 可启动。

**范围外（v1 明确后置）**：主体/角色维度策略（v2）、行过滤动态函数、非 ES 审计后端、除列脱敏与静态行过滤以外的脱敏形态。

### 1.3 当前主要风险面（测试设计的主攻方向）

| # | 风险 | 依据 |
|---|---|---|
| R1 | 行过滤为最新功能（parse 后 validate 前注入），与脱敏、CTE、子查询、写语句的组合语义未经系统性钉死 | 21a6518 起新增；TPC-DS 三模式仅覆盖SELECT主路径 |
| R2 | PG 方言三处修复（CONCAT 去重、DATE±INTEGER、标量子查询血缘）缺乏长期回归钉 | 74e94aa |
| R3 | 前端整页重构（access/policymanager/settings 替换 instances/*）未提交、新增页面零测试、nginx SPA fallback 刚改 | full-test 工作区 diff |
| R4 | 两个 API key 过滤器 blank 语义相反（policy-server fail-open / metadata fail-closed）且存在非常量时间比较 | 09-18 缺口报告 §6.3，至今未见修复记录 |
| R5 | 无 CI、无覆盖率度量、无 IT/UT 分离；mask-lite 不在根 pom 聚合内，`mvn test` 不含它 | 构建盘点 |
| R6 | 审计 ES 降级路径（ES 宕机/恢复、批量丢失）只有主路径测试 | mask-audit 现状 |
| R7 | mask-query 六引擎中 Hive/SparkSQL 执行面只有单测，无真实引擎验收 | golden-queries 文档标注 HS2 手工 |

---

## 2. 测试分层模型

结合项目已有风格（内嵌 PG、MockMvc、stub 上游、golden 文件、compose 手册），采用六层：

| 层 | 名称 | 载体 | 触发方式 | 目标 |
|---|---|---|---|---|
| L0 | 单元测试 | JUnit5，无外部依赖，surefire | 每次 `mvn test` | 分支/边界/错误消息契约 |
| L1 | 组件/服务集成 | 内嵌 PG（zonky）、MockMvc、JDK HttpServer stub 上游、golden 文件 | `mvn test`（与 L0 同相位，暂不拆 failsafe，见 §9） | 模块内主流程、REST 契约、SQL 往返 |
| L2 | 跨服务 E2E | 本机/compose 全栈 8080-8083+ES+PG，黑盒 REST（curl/脚本） | 手册 `docs/integration-test-report.md` 22 例 → §6.1 脚本化 | 服务间契约、传播、降级 |
| L3 | 引擎真实验收 | golden-queries runbook：PG/MySQL/Trino/StarRocks（compose profile）+ Hive/Spark（HS2） | 发布前必跑 | 改写 SQL 在真引擎执行且结果符合脱敏预期 |
| L4 | 专项 | TPC-DS 基准（bench/tpcds-mask-lite）、安全用例集、混沌注入 | 周期/发布前 | 性能不劣化、泄露面受控 |
| L5 | 前端测试 | vitest（现有）→ 组件测试 → Playwright E2E | 随前端构建（远程 deploy.sh 通道） | 页面功能、路由、密钥处理 |

**各层准入/准出**：L0/L1 全绿是合入底线；L2 是"周构建可发布"门槛；L3/L4 是版本发布门槛；L5 E2E 是前端发版门槛。任何一层发现缺陷，先在下层补回归钉再修。

---

## 3. 用例编号与优先级规范

- 编号：`<模块>-<域>-<三位序号>`。模块码：SQLP/CORE/LITE/POL/PSRV/META/AUD/QRY/BT(build-tools)/FE/E2E/SEC/PERF/DEP。域码两字母，如 RE(rewrite)、RF(rowfilter)、DL(dialect)、AU(audit)、KY(api key)。
- 优先级：**P0** 主流程/安全承诺/数据正确性，回归即事故；**P1** 重要分支、对外契约、异常路径；**P2** 防御性分支、错误文案、次要边界。
- 新增缺陷一律先落用例（复现→钉死→修复），用例号写进 commit message。

---

## 4. mask-sqlparser 测试设计

目标：解析层是全系统地基，任何回退都是全局事故。

| ID | 用例 | 类型 | 优先 | 验证点 |
|---|---|---|---|---|
| SQLP-CR-001 | babel 等价差分：语料逐条与 `SqlBabelParserImpl` 双解析比对 AST | L0 | P0 | 等价或差异均在白名单内（已有，保持） |
| SQLP-CR-002 | 语料扩充：`mask-parser-corpus.sql` 增补 TPC-DS 99 条全部纳入（当前为子集） | L0 | P1 | 99 条解析无异常、耗时在预算内 |
| SQLP-CN-001 | 各方言 conformance 矩阵：反引号/双引号/大小写折叠、`LATERAL`、`UNNEST`、窗口命名、`QUALIFY`（Trino）、`OVERWRITE`（Hive/Spark） | L0 | P1 | 方言拒绝/接受与 README §方言支持一致 |
| SQLP-IO-001 | InsertOverwrite unparse 往返（原 P0 缺口 #10） | L0 | **P0** | parse→unparse→reparse AST 等价 |
| SQLP-ID-001 | 标识符边界：保留字引用、大小写折叠、Unicode、反引号内转义 | L0 | P1 | 与 IdentifierPolicy 一致 |

---

## 5. mask-core 测试设计（重点）

### 5.1 改写引擎与脱敏

| ID | 用例 | 类型 | 优先 | 验证点 |
|---|---|---|---|---|
| CORE-RE-001 | 写入语句（INSERT/CTAS/INSERT OVERWRITE）「脱敏+行过滤」叠加（原 P0 缺口 #1） | L1 | **P0** | 写入目标库的数据本身已脱敏/已过滤，不是查询时脱敏 |
| CORE-RE-002 | 聚合输出列命中文本策略的包装形态：聚合列不插 CAST、外层包装正确（原 P0 缺口 #2） | L1 | **P0** | `COUNT(email)` 类输出的包装合法性 |
| CORE-RE-003 | 五方言 × TPC-DS 高频算子矩阵：CTE/子查询/集合运算/窗口/ORDER BY 别名引用 | L1 | P1 | 输出可被 trino-parser/各自方言再解析 |
| CORE-RE-004 | 输出列名保持：脱敏后列别名与原列名一致（含中文列名、需要引用的列名） | L0 | P1 | 下游 `SELECT c_email FROM (...)` 可用 |
| CORE-RE-005 | 策略未命中路径：无策略表原样透传、binary 安全 | L0 | P1 | 无策略时输出与输入语义等价 |
| CORE-RE-006 | RewritePlan 可观测性：每语句 masked/rowFiltered/statement kind 标记与实际行为一致 | L1 | P1 | 是 mask-query 响应字段的数据源 |

### 5.2 行过滤（R1 主攻）

| ID | 用例 | 类型 | 优先 | 验证点 |
|---|---|---|---|---|
| CORE-RF-001 | 注入位置：parse 后 validate 前注入在各类语句下的表现——SELECT/CTE/派生表/EXISTS/标量子查询/UNION 两臂 | L1 | **P0** | 每个表引用点都被过滤，无遗漏、无重复注入 |
| CORE-RF-002 | 行过滤 × 脱敏叠加三模式与 TPC-DS 语义校验一致（bench `SemanticCheck` 移植为常驻单测，抽样 10 条代表查询） | L1 | **P0** | 语义违例数 0 |
| CORE-RF-003 | 行过滤谓词的方言形态：布尔列直用 vs `= TRUE` vs 自定义谓词，在 pg/mysql/trino 下的语法适配 | L1 | P1 | 输出方言合法 |
| CORE-RF-004 | rowFilter 未配置/配置为空/谓词引用不存在列 三种失败形态 | L0 | P1 | 明确错误码，不静默丢弃（原 mask-lite P1 缺口同源问题在 core 钉死） |
| CORE-RF-005 | 视图/嵌套视图下的注入次数 | L1 | P1 | 恰好一次 |
| CORE-RF-006 | YAML rowFilter 解析边界：缺字段、多谓词、注释、引号转义 | L0 | P1 | 拒绝或正确解析 |

### 5.3 方言与血缘（R2 主攻）

| ID | 用例 | 类型 | 优先 | 验证点 |
|---|---|---|---|---|
| CORE-DL-001 | **PG 方言三修复回归钉**：CONCAT 重载注册去重（重复注册不抛异常）；`DATE ± INTEGER` 天数算术不变形；标量子查询血缘可追溯到列 | L1 | **P0** | 对应 74e94aa，三处各一用例永久锁定 |
| CORE-DL-002 | 函数覆盖矩阵：每方言 masking 可用函数清单 × 常见嵌套（`CONCAT(SUBSTR(x,1,3),'***')`） | L1 | P1 | 输出方言合法、语义符合策略 |
| CORE-DL-003 | 类型强转：email/phone 文本策略与数值/时间列交叉时的 CAST 行为；decimal(p,s) 声明（含 scale=0 缺省，1a8399d 回归钉） | L0 | P1 | 无 NPE、无精度丢失 |
| CORE-DL-004 | Hive/SparkSQL 同族 profile：反引号、小写折叠、类型集、OVERWRITE 扩展与 README §安全失败清单一致 | L1 | P1 | 拒绝清单逐条断言 |
| CORE-LN-001 | LineageAnalyzer 边界：多表 JOIN 同名列、别名链、三层嵌套派生表、SELECT * 展开 | L0 | P1 | ColumnOrigin 正确 |

### 5.4 服务层与 CLI

| ID | 用例 | 类型 | 优先 | 验证点 |
|---|---|---|---|---|
| CORE-SV-001 | 实例模式策略服务故障错误码映射（404/401/500/超时）（原 P0 缺口 #5） | L1 | **P0** | 上游状态正确翻译为 CONFIG_ERROR 族错误码，消息含实例名 |
| CORE-SV-002 | 配置轮询失败时 stale 缓存保全（原 P0 缺口 #4） | L0 | **P0** | 刷新抛异常后旧配置仍可服务，恢复后自动换新 |
| CORE-SV-003 | CacheRefreshController 主动刷新并发安全；刷新期间请求不 5xx | L1 | P1 | 读写锁语义 |
| CORE-SV-004 | `/api/rewrite` 契约：多语句、空语句、超长语句、CRLF/仅注释输入、非 SQL 文本 | L1 | P1 | 错误码与 README §REST API 一致 |
| CORE-CLI-001 | CLI `--strict` 拦截降级导出 STRICT_DEGRADED（原 P0 缺口 #3） | L1 | **P0** | 退出码与 stderr 文案 |
| CORE-CLI-002 | CLI 双模式（stdin/stdout、--pull-metadata）参数矩阵与 exit code | L0 | P1 | 含 8 类错误参数组合 |
| CORE-GD-001 | golden 字节级回归：23 个 golden 文件保持，任何故意变更走 `-Dgolden.write=true` + diff review 流程 | L1 | **P0** | 已有，制度化 |
| CORE-IN-001 | 元数据内省：PG/MySQL/Trino 三内省器对空 schema、视图、多 catalog、降级 YAML 的生成（现有 introspect-*.yaml 覆盖，补 MySQL/Trino 真连用例入 L3） | L1 | P1 | YAML 与真实库结构一致 |

### 5.5 非功能

| ID | 用例 | 类型 | 优先 | 验证点 |
|---|---|---|---|---|
| CORE-PF-001 | 单次 rewrite 延迟基线：TPC-DS 99 查询 warm p50/p95 与 bench/REPORT.md 基线比对，劣化 >20% 阻断 | PERF | P1 | 基准脚本入 §9 周任务 |
| CORE-SC-001 | 策略文件/元数据 YAML 中的敏感字段（密码）不落入日志与审计事件 | SEC | P0 | 日志断言 grep |

---

## 6. mask-lite 测试设计

mask-lite 已从"边缘化"转为活跃主线（TPC-DS 99/99），但仍是构建孤儿（不在根 pom）。

| ID | 用例 | 类型 | 优先 | 验证点 |
|---|---|---|---|---|
| LITE-RE-001 | 既有 12 类保持全绿；TPC-DS 99×3 模式改写成功率 99/99 作为门禁（finalize.sh 语义校验 0 违例） | L1 | **P0** | 已有，制度化：bench 命令进 §9 周任务 |
| LITE-DL-002 | PG 方言修复回归钉（与 CORE-DL-001 同源三处，在 lite 侧独立钉死） | L0 | **P0** | CONCAT 去重 / DATE±INTEGER / 标量子查询血缘 |
| LITE-RF-001 | 行过滤模式：谓词注入位置矩阵（CTE/子查询/UNION，同 CORE-RF-001 用例集在 lite 复刻） | L0 | **P0** | 与 core 行为一致 |
| LITE-RF-002 | rowFilter 配置异常形态（引用不存在列/谓词非法）不静默丢弃 | L0 | **P0** | 报错而非忽略（原缺口清单 §4.9 P1 项） |
| LITE-CLI-001 | Main 参数矩阵：缺 --metadata、文件不存在、非法 YAML、stdin 空、超大输入（>1MB SQL） | L0 | P1 | usage 文案与退出码 |
| LITE-LN-001 | OutputLineage 正确性：metadata/lineage.yaml 场景扩展多 JOIN/嵌套 | L0 | P1 | 列级来源准确 |
| LITE-BL-001 | **纳入根 pom 聚合或建立独立 CI job**（二选一，倾向前者：加 profile 或直接加入 modules），使 `mvn test` 必含 lite | 基建 | **P0** | §9 CI 落地前提 |

---

## 7. 策略面（mask-policy + mask-policy-server）测试设计

| ID | 用例 | 类型 | 优先 | 验证点 |
|---|---|---|---|---|
| PSRV-KY-001 | API key 过滤器加固（R4）。**2026-09-24 执行修订**：读码后确认策略面 blank=放行是有意设计（javadoc 注明本地浏览器 UI/开箱即用，前端 settings store 的空态与 http 层空 key 不带头均依赖它），故不翻转语义；实际修复 = metadata `ApiKeyFilter` 改常量时间比较（`MessageDigest.isEqual`），并为全部 5 个过滤器补齐四象限钉（匹配/错配/缺失/未配置 + 空串 header + 非 ASCII key）。若产品决定策略面也 fail-closed，属一行改动，另立决策 | L1 | **P0** | ✅ 已完成：1 处修复 + 4 文件 9 用例 |
| PSRV-KY-002 | 数据面 effective 无 admin key 可访问、admin 面无 data key 不可访问（角色隔离矩阵 2×2） | L1 | **P0** | 已有部分，补矩阵化 |
| PSRV-PS-001 | PolicyService 写路径：校验失败不入库、并发写同名策略、config_version 单调递增 | L1 | **P0** | effective 下发一致性 |
| PSRV-PS-002 | effective 编译：DATAMASK+ROW_FILTER 同时存在时的合并输出、UDF 重载签名区分、通配符 subject 归一 wildcard | L1 | **P0** | 与前端 EffectiveTab 展示字段一致 |
| PSRV-UD-001 | UDF 注册表 CRUD：重载签名冲突、删除被引用 UDF 的行为、参数个数边界 | L1 | P1 | 配合 .udf-smoke/rest-test.sh 用例脚本化 |
| PSRV-ME-001 | 元数据导入契约：非法 YAML、重复导入版本覆盖、导入后 effective 立即可见 | L1 | P1 | MetadataImportController |
| PSRV-AU-001 | `/api/audit` 门禁注册盲区 + 上下文级 401（原 P0 缺口 #7）；AUDIT_ENABLED=false 时全链路不抛 | L1 | **P0** | 原 P0 |
| PSRV-ST-001 | JdbcPolicyStore vs InMemory 行为一致性（同一用例集跑双实现）；重启后策略不丢 | L1 | **P0** | 生产 PG 存储 |
| PSRV-MT-001 | effective 数据面指标三分支（原 P0 缺口 #8：命中/未命中/异常） | L1 | P1 | Prometheus 文本格式可抓取 |
| POL-PE-001 | PolicyEngine 匹配：Glob 边界（`*`/`?`/跨段）、多策略优先级、miss 路径（已有，保持并补 P1 缺口 1 条） | L0 | P1 | — |

---

## 8. mask-metadata / mask-audit / mask-query 测试设计

### 8.1 mask-metadata

| ID | 用例 | 类型 | 优先 | 验证点 |
|---|---|---|---|---|
| META-CO-001 | 采集门硬边界：hive/sparksql collect 必拒绝且消息指向 YAML 导入；pg/mysql/trino 通过 | L1 | **P0** | 原缺口 P0 |
| META-CO-002 | 采集失败原子性：失败不触碰已存结构、版本号不前进 | L1 | **P0** | 已有设计承诺，钉死 |
| META-ST-001 | JdbcMetaStore connection 字段 round-trip（含特殊字符密码）（原 P0 缺口 #9） | L1 | **P0** | 加密/转义不丢失 |
| META-CR-001 | EnvCredentialResolver：passwordRef 环境变量缺失/为空/含空格 | L0 | P1 | 明确错误 |
| META-AC-002 | 管理面/数据面 API key 隔离与 PSRV-KY-001 同规格 | L1 | **P0** | — |
| META-IM-001 | YAML 导入路径（hive/sparksql 唯一通道）：版本递增、结构变更、导入后可被 query 使用 | L2 | P1 | 跨服务见 §11 |

### 8.2 mask-audit

| ID | 用例 | 类型 | 优先 | 验证点 |
|---|---|---|---|---|
| AUD-ES-001 | flush-interval 定时小批次刷新（原 P0 缺口 #6）：单事件 < flush 批量阈值也能在 interval 内落 ES | L1 | **P0** | 用 stub ES HTTP 服务断言请求时机 |
| AUD-ES-002 | ES 宕机降级：异步队列积压→FailureReporter 兜底→恢复后自动续写，期间业务请求不受影响 | L1 | **P0** | R6 主攻 |
| AUD-ES-003 | 索引模板：按日滚动命名、mapping 缺失自动建、版本升级兼容 | L1 | P1 | IndexTemplateManager |
| AUD-EV-001 | 审计事件 JSON 契约：必填字段、时间格式、SQL 截断长度、不含明文敏感列值 | L0 | **P0** | 泄露面检查 |
| AUD-SE-002 | 审计查询 API 分页/时间窗/事件类型过滤与前端 AuditView 参数一致 | L1 | P1 | 与 FE-AU-003 对照 |

### 8.3 mask-query

| ID | 用例 | 类型 | 优先 | 验证点 |
|---|---|---|---|---|
| QRY-EE-001 | 既有 `QueryEndToEndTest` 扩展：改写服务返回多语句/部分失败/超时三形态 | L1 | **P0** | 部分失败时已执行语句的处理与响应标记 |
| QRY-QE-002 | 六引擎目录单测保持；新增 sslmode/URL 构造矩阵（大小写 sslmode、hive2 null 库名——1a8399d 回归钉） | L0 | P1 | — |
| QRY-CN-003 | 并发：同一实例 permit 耗尽时的排队/拒绝、CancelRegistry 取消后 permit 归还 | L1 | **P0** | 已有测试被删过（工作区 diff 显示 CancelRegistryTest 等有改动），必须恢复全量 |
| QRY-EC-004 | ErrorClassifier：JDBC 异常→错误码映射矩阵（连接拒绝/认证失败/语法错/超时/取消） | L0 | P1 | 与 README §错误码一致 |
| QRY-AU-005 | 审计桥：QUERY 事件含改写前后 SQL 摘要、rowCount；审计失败不阻塞查询 | L1 | **P0** | — |
| QRY-SC-006 | 凭据安全：请求中的 JDBC 密码不落日志/审计/异常消息 | SEC | **P0** | grep 断言 |
| QRY-TR-007 | 截断语义：truncated 阈值边界（恰好等于/超过 maxRows） | L0 | P1 | — |

---

## 9. 前端测试设计（R3 主攻）

约束：本机无 Node，构建/测试走 `deploy.sh` 远程通道——先在远程跑通 `npm test`，中长期建议恢复本地 Node 或加 CI runner。

### 9.1 vitest 单测/组件测试（新增）

| ID | 用例 | 类型 | 优先 | 验证点 |
|---|---|---|---|---|
| FE-UT-001 | 现有 3 spec（16 用例）保持 | L0 | P0 | — |
| FE-CP-002 | **AccessManager.vue**：按方言分组渲染、创建实例对话框表单校验、"载入示例实例"序列（建实例→UDF→策略）的 API 调用顺序、删除确认 | 组件 | **P0** | 新页面零测试，最高优先 |
| FE-CP-003 | **PolicyManager 四 Tab**：PoliciesTab 过滤/DATAMASK 与 ROW_FILTER 表单/优先级；TablesTab 脏状态"保存全部"批量 PUT；UdfsTab 重载签名；EffectiveTab 请求参数（data key、user/groups） | 组件 | **P0** | 逐 Tab 各 2-3 用例 |
| FE-CP-004 | **Settings.vue**：双 key 保存到 `mask-policy-console-keys`、状态 tag 展示、拓扑卡静态内容 | 组件 | P1 | — |
| FE-RT-005 | 路由：6 条新路由渲染正确组件；legacy 重定向 `/instances`→access-manager、`/instances/:name`→policy-manager；catch-all→dashboard；`props:true` 传参 | 组件 | **P0** | 重构刚改 router |
| FE-ST-006 | instances store：load 失败态、select/forget 生命周期 | 组件 | P1 | — |

### 9.2 Playwright E2E（新增基建，§13 落地）

| ID | 用例 | 类型 | 优先 | 验证点 |
|---|---|---|---|---|
| FE-E2E-001 | 冒烟：首页加载→访问管理→载入示例→策略管理器四 Tab 切换→设置页存 key | E2E | P1 | 真实 8081 后端（compose） |
| FE-E2E-002 | 试验台：粘贴 SQL→调 /api/rewrite→结果区渲染（含错误态 `[CODE] message`） | E2E | P1 | — |
| FE-E2E-003 | 审计页：时间预设切换、事件类型筛选、7 天窗限制的服务端错误展示 | E2E | P2 | — |
| FE-E2E-004 | SPA fallback：直接刷新 `/policy-manager/crm`、`/instances/crm`（旧链）不 404 | E2E | **P0** | nginx 刚改 try_files，发布必验 |
| FE-SEC-005 | key 不出现在 URL/错误提示/控制台日志；sessionStorage 行为与 README 一致 | SEC | **P0** | — |

---

## 10. mask-build-tools（BT）

| ID | 用例 | 类型 | 优先 | 验证点 |
|---|---|---|---|---|
| BT-SF-001 | SpringFactoriesTransformer：多 spring.factories 合并、同名 entry 去重——用最小 jar fixture 单测 | L0 | P1 | 结束 shade fat-jar 只有手工冒烟的历史 |
| BT-SF-002 | fat-jar 启动冒烟进 CI：`java -jar mask-core.jar --help` 与 policy/metadata/query 三 jar `--spring.main.web-application-type=none` 启动探测 | L1 | **P0** | 944d4a2 类缺陷的机器防线 |

---

## 11. 跨服务 E2E 与专项

### 11.1 全链路 E2E（L2）

以 `docs/integration-test-report.md` 22 例为蓝本脚本化（bash+curl，入 `docs/query-acceptance/` 同级的 `e2e/` 目录），新增/强化：

| ID | 场景 | 优先 |
|---|---|---|
| E2E-001 | 标准链路：metadata 采集（PG）→ policy 建策略+行过滤 → rewrite 服务改写 → query 执行 → ES 查到 REWRITE+QUERY 事件 | **P0** |
| E2E-002 | YAML 导入链路：hive/sparksql 元数据经 import 后全链路可用 | P1 |
| E2E-003 | 配置传播：策略变更→effective config_version 递增→rewrite 轮询感知（≤轮询间隔） | **P0** |
| E2E-004 | 降级语义：ES 宕机时 rewrite/query 正常返回（审计尽力而为）；rewrite 宕机时 query 返回明确 CONFIG_ERROR；恢复后自愈 | **P0** |
| E2E-005 | API key 矩阵：四个服务 × admin/data/无 key/错 key 全组合（与 PSRV-KY-001 修复联动） | **P0** |
| E2E-006 | nginx 路由：`/api/instances`、`/api/effective`→8081，`/api/audit`→8080，未匹配 `/api/*` 404，SPA fallback | **P0** |

### 11.2 引擎真实验收（L3）

- 执行 `docs/query-acceptance/golden-queries.md` 全部六引擎表（PG 本机、MySQL/Trino/StarRocks 走 `docker-compose.query.yml` profile、Hive/Spark 手工 HS2），每引擎记录通过率，发布门槛 100%（StarRocks/Hive/Spark 允许标注的已知引擎侧限制除外）。
- 行过滤三模式（mask/rowfilter/both）纳入每引擎至少 2 条 golden 查询（当前 golden 以脱敏为主，需补行过滤场景）。
- 每条 golden 增补"未配置策略时结果与原库一致"的反向断言（防过度脱敏）。

### 11.3 安全专项（SEC）

| ID | 项 | 验证方法 |
|---|---|---|
| SEC-001 | 脱敏泄露面抽查 | 对 TPC-DS 三模式输出做模式匹配：email/phone/hash 列在结果集中不得出现明文（bench SemanticCheck 已含部分，扩为独立脚本） |
| SEC-002 | 写语句泄露 | CORE-RE-001 的落地验证：CTAS 后查目标表 |
| SEC-003 | 敏感信息入日志 | 全链路跑一遍后 grep 日志：JDBC 密码、API key、脱敏前列值样本 |
| SEC-004 | 未授权访问 | E2E-005 矩阵；前端 key 存储位置检查（sessionStorage vs localStorage 差异需在 README 与 Settings 页文案一致） |
| SEC-005 | 注入面 | 策略行过滤谓词含 SQL 特殊字符（引号/分号/注释）时被安全处理（预期：原样注入或明确拒绝，绝不允许策略作者以外的人注入） |

### 11.4 性能专项（PERF）

- `bench/tpcds-mask-lite`：lite 99×3 改写成功率、warm p50、round-trip、DuckDB 语义校验四项指标作为基线（当前 99/99、0 违例），每次 bench 输出对比 REPORT.md，劣化即开缺陷。
- core 引擎同矩阵（当前参照 95/99）：未通过的 4 条建立白名单并用例注释原因，白名单变化需 review。
- mask-query 容量：单实例 permit 上限下的吞吐与排队延迟（嵌入式 PG + stub 上游即可，不必真引擎），出基线数字。

---

## 12. 部署与发布验证（DEP）

| ID | 项 | 验证点 |
|---|---|---|
| DEP-001 | 四个 compose 栈各自 `up -d` 后健康：8080/8081/8082/8083 契约探针、ES 9200、PG 5432/5433、prometheus 9090 抓取 sql-mask 与 metadata 两个 job | 发布前必跑 |
| DEP-002 | 前端镜像：`deploy.sh build` 产物 dist-only 镜像启动后 `/` 出控制台、`/api` 路由转发正确（nginx.conf.docker 与本地 nginx.conf 行为一致） | FE-E2E-004 联动 |
| DEP-003 | 数据持久化：policy/metadata PG 卷重建后服务可空库启动、有卷时数据保留 | P1 |
| DEP-004 | 升级路径：effective config_version 跨重启单调；审计索引跨日滚动 | P2 |
| DEP-005 | 仓库卫生：根目录 t*.log / inst2.log 清理或 gitignore（不影响测试，但影响 e2e 脚本可移植性） | P2 |

---

## 13. 测试基建改造清单

| # | 事项 | 说明 | 优先 |
|---|---|---|---|
| 1 | **建 CI**（GitHub Actions 单 workflow） | job1 `mvn package`（8 模块 + lite，package 而非 test 顺带验证 shade 装配）；job2 前端 vitest（GitHub runner 自带 Node，直接跑，无需远程通道；仓库无 lockfile，用 npm install）。**✅ 2026-09-24 已落地 `.github/workflows/ci.yml`** | **P0** |
| 2 | mask-lite 聚合 | 根 pom 加入 modules（其 pom 无 parent，直接列 module 即可）。**✅ 2026-09-24 已落地** | **P0** |
| 3 | `PgMetadataIntrospectorRealTest` 环境守卫 | 默认必跑（zonky 二进制走 Maven 依赖，有缓存即可离线），加逃逸阀 `MASK_SKIP_REAL_PG=true|1|on` 跳过。**✅ 2026-09-24 已落地** | P0 |
| 4 | jacoco 覆盖率 | 根 pom pluginManagement + report 聚合；目标见 §15 | P1 |
| 5 | failsafe IT 分离 | 第一阶段暂缓（现有 L1 测试在 surefire 内跑得动），待单测+IT 总时长 >10min 再拆；先在类名上用 `*IT` 预留命名 | P2 |
| 6 | e2e 脚本目录 | `e2e/` 收编 integration-report 22 例 + §11.1 新增，make/脚本一键跑 | P1 |
| 7 | golden-queries 制度化 | 每引擎通过率写入 `docs/query-acceptance/RESULTS.md`，模板随 PR 更新 | P1 |
| 8 | Playwright 引入 | 前端 devDeps + `tests/e2e/`；因本机无 Node，初期放远程通道，CI job3 预留 | P1 |
| 9 | 缺陷→用例流程 | commit message 携带用例号；缺陷复现脚本进对应模块 src/test | P0 |

---

## 14. 执行排期

| 阶段 | 内容 | 准出标准（可度量） |
|---|---|---|
| **阶段 0（第 1 周）：守护网** | 基建 #1/#2/#3/#9；API key 语义统一修复（PSRV-KY-001）+ 矩阵用例；CORE-GD-001/LITE-RE-001 制度化 | CI 全绿（8 模块 + lite + 前端 vitest）；两过滤器行为一致有测试钉 |
| **阶段 1（第 2-3 周）：P0 清零** | 原缺口 10 条 P0（§5.4 CORE-RE-001/002、CORE-CLI-001、CORE-SV-001/002、PSRV-AU-001、PSRV-MT-001、META-ST-001、SQLP-IO-001、AUD-ES-001）；新增 P0：CORE-RF-001/002、LITE-DL-002/LITE-RF-001/002、CORE-DL-001、QRY-CN-003/AU-005/SC-006、AUD-ES-002/002 系、FE-CP-002/003、FE-RT-005、FE-E2E-004、BT-SF-002 | P0 用例 100% 存在且通过；`mvn test` 含 lite 全绿 |
| **阶段 2（第 4-5 周）：跨服务与专项** | E2E 脚本化 6 场景；golden-queries 六引擎执行并补行过滤 golden；SEC-001~005；PERF 基线报告 | e2e 一键脚本 100% 通过；golden 六引擎结果归档 RESULTS.md；安全专项无未缓解发现 |
| **阶段 3（第 6 周）：收尾与发布** | P1 用例批量补齐（39 条历史 P1 + §5-§10 表中 P1）；DEP-001~004；覆盖率报告首采；发布验收 | 准出：§15 全部达标；遗留项降级为已知问题清单并公示 |

原则：阶段 1 期间任何缺陷修复必须先落 P0 级回归钉；阶段 2 的 e2e 脚本从第一次编写起就进 git（不留本地私有脚本）。

---

## 15. 准出标准与度量

| 维度 | 目标 |
|---|---|
| L0/L1 用例通过率 | 100%（CI 红即阻断合入） |
| P0 缺口清零 | 历史 10 条 + 本计划新增 P0 全部实现 |
| 行覆盖率（jacoco 首采后定基线） | mask-core/查询链路模块行覆盖 ≥70%、分支 ≥55%（首采后 ±5% 内调整，只升不降） |
| TPC-DS 基准 | lite 99/99 改写成功、语义违例 0；core 白名单不变或缩小；warm p50 劣化 ≤20% |
| golden-queries | 六引擎通过率 100%（标注的引擎侧已知限制除外），每版本归档 |
| E2E | §11.1 六场景全通过，一键可复跑 |
| 安全 | PSRV-KY-001 修复合入；SEC-001~005 无未缓解高/中危发现 |
| 缺陷逃逸 | 发布后 P0/P1 逃逸 ≤1，且必须 48h 内补回归钉 |

---

## 16. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| CI 环境内嵌 PG 需联网下载 binaries | CI 建不起来 | 缓存目录 + 失败回退为显式 skip 并标黄，不允许静默 |
| 本机无 Node，前端测试全在远程 | 迭代慢、结果难归档 | ✅ CI runner 自带 Node，vitest 已入 GitHub Actions；本机迭代仍走 deploy.sh 远程通道，后续争取本地 Node |
| Hive/Spark 真实引擎依赖手工 HS2 | L3 覆盖不稳 | 保留手工手册 + 用 Docker HS2 镜像（apache/hive）尝试脚本化，失败则明确标注"人工执行" |
| 前端 WIP 未提交期间测试对象漂移 | 用例写完代码又变 | 阶段 1 起点先把 full-test 前端改动提交/合入，测试跟随 PR |
| golden 字节级锁定与有意变更冲突 | 误报噪音 | 走 `-Dgolden.write=true` + diff review 流程，golden diff 必须出现在 PR 描述 |
| 99 条历史缺口与新增功能并行补齐撞车 | 资源冲突 | 阶段 1 只做 P0；P1/P2 随改随补，不设专班 |

---

## 附录 A：与既有文档的关系

- `docs/test-plan-noauth.md`：**匿名模式（无认证/无主体维度）裁剪版计划**——对齐 v1「纯改写工具」定位，验收范围收窄（剔除 API key/subject 特异性验收）、执行集合不变（全量套件照跑防回归），并新增 NOAUTH-001~005 匿名专属钉。

- `docs/test-coverage-gap-analysis-main-20260918.md`：99 条缺口的**明细清单**（本文的用例来源库），本文负责排期与新增面（行过滤、mask-lite 复活、前端重构、query 引擎），两者用例号互不重叠、可对照。
- `docs/integration-test-report.md`：22 例 E2E 的**首次执行报告**，§11.1 将其脚本化为准永久资产。
- `docs/query-acceptance/golden-queries.md`：L3 验收 **runbook**，本文只定义门槛与归档要求。
- `bench/tpcds-mask-lite/report/REPORT.md`：性能基线出处。
