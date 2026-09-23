# sql-mask 匿名模式测试计划（无认证 · 无主体维度）

- **版本**：v1.0（2026-09-24）
- **定位**：对齐 v1 目标「纯改写工具，主体维度后置 v2」（d973033）的裁剪版测试计划——**不包含用户认证/授权测试，不包含 user/user-group 主体维度**，适用于可信内网或嵌入式部署形态。完整计划的认证与主体部分见 `docs/test-plan.md`，本计划只收窄验收范围、新增匿名模式专属钉，**不删除任何既有测试代码**。
- **依据**（写断言前已核对代码）：
  - `EffectiveConfigController`：`user/groups` 缺省 = 匿名主体，"wildcard policies only"；
  - `SubjectSelector`：users/groups 须至少一个非空，通配符仅 `["*"]`，空选择器加载期抛 `PolicyException`；
  - `InstanceRewriteController`：改写请求不带 `user/groups` → `Subject.of(null,null)` 匿名主体；
  - `PolicyModelTest#anonymousSubjectMatchesOnlyWildcard`：通配→level 1，具名 user/group→level 0（模型层已有钉）；
  - 策略面两个过滤器（mask-core / mask-policy-server）：key 未配置 = 开放（javadoc 注明为设计）；数据面（metadata/query）：key 未配置 = 全部拒绝（fail-closed）。

---

## 1. 模式定义与部署前提

**无认证** = 不验证调用方身份；**无主体维度** = 所有策略 subject 固定 `["*"]`，effective 与 rewrite 请求一律不带 `user/groups`。

| 服务 | key 配置要求 | 说明 |
|---|---|---|
| 8080 mask-core | 不配置（开放） | 策略面 blank=放行为既有设计 |
| 8081 mask-policy-server | 不配置（开放） | 同上 |
| 8082 mask-metadata | 机制性 key（任意固定值，如 `local-dev-key`） | `ApiKeyFilter` fail-closed：blank 配置会拒绝一切请求，无法真正 keyless；key 仅作启动管道，**正确性不在本计划测试范围** |
| 8083 mask-query | 机制性 key（同上） | `QueryApiKeyFilter` fail-closed，同上 |
| 前端 | 不配置 key（Settings 留空） | http 层空 key 不发 `X-Api-Key` 头 |

> P2 待办（不在本计划执行）：为 8082/8083 增加"禁用认证"开关，实现四服务完全 keyless。

## 2. 范围

**范围内**：五方言改写正确性、行过滤、两者叠加；元数据采集/YAML 导入；策略 CRUD 与 effective 编译（仅通配 subject）；六引擎查询执行；ES 审计记录；前端控制台全页面；TPC-DS 基准；E2E 主链路与降级。

**范围外（由完整计划承接，本模式不验收）**：API key 四象限/角色隔离/越权矩阵（PSRV-KY-001/002、META-AC-002、PSRV-AU-001、E2E-005、SEC-004）；subject 特异性匹配（exact-user(3) > exact-group(2) > wildcard(1) 的层级用例）；审计事件 `authKind=API_KEY` 断言；前端密钥存储检查（FE-SEC-005、FE-CP-004 降级为"可选配置"冒烟）。

**重要澄清**：既有认证用例钉（含 2026-09-24 新增的四过滤器钉）继续随套件全量运行，防止模式切换引入回归——只是它们不计入本模式的验收门槛。

## 3. 匿名模式专属用例（新增）

| ID | 用例 | 层 | 优先 | 验证点 |
|---|---|---|---|---|
| NOAUTH-001 | 通配策略生效性：subject=`["*"]` 的 DATAMASK+ROW_FILTER 策略，匿名 rewrite（不带 user/groups）命中脱敏与行过滤 | L1 | **P0** | 输出 SQL 含脱敏包装与行过滤谓词；masked/rowFiltered 标记为 true |
| NOAUTH-002 | 具名策略对匿名不生效（防过度脱敏负向钉）：subject=`["alice"]` 的策略在匿名 rewrite 下不应用，输出与原 SQL 语义等价 | L1 | **P0** | 模型层行为已由 `PolicyModelTest` 钉死；本用例钉服务级链路（rewrite 请求→PolicyEngine→输出） |
| NOAUTH-003 | 空 subject 选择器被拒：写入 users/groups 均空的策略，写入或加载期报 `PolicyException`（明确文案提示改用 `["*"]`） | L1 | P1 | 不静默、不兜底成通配 |
| NOAUTH-004 | effective 匿名查询：`GET /api/effective/{instance}`（无 user/groups 参数）仅返回 `["*"]` 绑定；对照请求带 `user=alice` 时返回通配+具名并集 | L1 | P1 | config_version 一致；通配策略两次都在 |
| NOAUTH-005 | 全栈零 key 冒烟（E2E）：8080/8081 不配 key、8082/8083 机制性 key、前端 Settings 留空，走完 访问管理→建实例→UDF→策略（`["*"]`）→试验台改写→查询→审计可查 | L2 | **P0** | 全程无 401；ES 中 REWRITE/QUERY/ADMIN_CHANGE 事件正常落库（authKind 为空属预期） |

## 4. 沿用主计划的用例（按模块索引，认证项已剔除）

| 模块 | 保留用例 | 本模式剔除/降级 |
|---|---|---|
| mask-sqlparser | SQLP 全部 | — |
| mask-core | CORE-RE/RF/DL/LN/GD 全部；CORE-SV-002/003/004；CORE-CLI-001/002；CORE-IN-001；CORE-PF-001；CORE-SC-001 | CORE-SV-001 中 401 映射子项降级（key 场景不部署） |
| mask-lite | LITE 全部 | — |
| mask-policy | POL-PE-001（通配路径；特异性层级用例移出本模式） | — |
| mask-policy-server | PSRV-PS-001/002；PSRV-UD-001；PSRV-ME-001；PSRV-ST-001；PSRV-MT-001 | PSRV-KY-001/002、PSRV-AU-001 |
| mask-metadata | META-CO-001/002；META-ST-001；META-CR-001；META-IM-001 | META-AC-002 |
| mask-audit | AUD 全部（authKind 断言改为"匿名模式下为空"） | — |
| mask-query | QRY-EE/QE/CN/EC/TR 全部；QRY-AU-005；QRY-SC-006（JDBC 凭据卫生保留——属敏感数据处理，非用户认证） | QueryApiKeyFilter 新增用例 |
| 前端 | FE-UT-001；FE-CP-002/003（EffectiveTab 以留空 user/groups 为主路径）；FE-RT-005；FE-E2E-001/002/003/004 | FE-CP-004 降级为可选配置冒烟；FE-SEC-005 |
| E2E | E2E-001/002/003/004/006 + NOAUTH-005 | E2E-005（key 矩阵） |
| 专项 | SEC-001/002/003/005；PERF 全部；DEP 全部 | SEC-004 |

## 5. 执行方式

1. **L0/L1 全量**：`mvn -B test`（9 模块，含 mask-lite 与全部既有认证钉）——本模式与完整计划的唯一门槛差异在验收范围，不在执行集合。
2. **L2**：`e2e/` 脚本（不含 E2E-005）+ NOAUTH-005。
3. **L3**：`docs/query-acceptance/golden-queries.md` 六引擎，策略注册阶段的 subject 一律 `["*"]`，effective 查询不带 user/groups。
4. **L4**：`bench/tpcds-mask-lite` 三模式（bench YAML 本身无 subject，天然匹配本模式）。
5. **L5**：前端 vitest + Playwright（EffectiveTab 主路径 = 留空提交）。

## 6. 准出标准

| 维度 | 门槛 |
|---|---|
| 全量测试 | `mvn -B test` 9 模块全绿（含认证钉不回归） |
| 匿名专属钉 | NOAUTH-001~005 全部存在且通过 |
| 引擎验收 | golden-queries 六引擎通过率 100%（引擎侧已知限制除外） |
| 基准 | TPC-DS lite 99/99、语义违例 0；warm p50 劣化 ≤20% |
| E2E | §4 保留场景一键复跑全通过 |

## 7. 暴露面声明（风险提示，非测试项）

- 匿名模式 = 管理面全开放：任何可达 8081 的客户端都能增删策略、改写规则。仅适用于可信网络/嵌入式形态；对公网部署必须回到完整计划（配置 key 并执行认证验收）。
- 审计事件 `authKind` 为空（非 `API_KEY`），审计追溯不含调用方身份——这是本模式的已知代价。
- 8082/8083 的机制性 key 一旦泄漏等同无认证，不得复用真实密钥。
