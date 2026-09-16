# 策略优先级统一模型（priority 主轴 + 编译期消解 + 行过滤 AND 组合）

日期：2026-09-17
状态：设计已与用户确认（统一两侧 / priority 主轴 / 仅同 priority 拒绝 / 不开 glob / 方案 A 五项决策 + 整体设计通过）
前置文档：
`2026-09-06-policy-subsystem-design.md`（PDP 决策语义 §5——本设计确认其 priority 序为统一规则，YAML 形态）、
`2026-09-06-policy-service-design.md`（策略微服务模型——本设计取代其"配置期拒绝、无 priority"决策，见 §1.3）、
`2026-09-16-policy-admin-rest-design.md`（管理面 REST + 主体维度，已实现）、
`2026-09-17-policy-server-extraction-design.md`（微服务拆分——本设计落在现有内嵌代码上，拆分原样搬走）

## 1. 背景与目标

当前存在两套并行的策略优先级语义：

1. **mask-policy PDP（内联 YAML 路径，core/CLI 消费）**：`Policy` 已有 `priority`
   字段，决策顺序 = enabled 跳过 → priority 降序 → 声明顺序，掩码首命中唯一
   （`PolicyIndex` 稳定排序 + `PolicyEngine.maskFor` 首命中）；主体特异性只在
   单个策略的 items 之间生效；资源支持四级 glob。
2. **策略服务（`io.sqlmask.policyserver`，现内嵌 mask-core）**：无 priority 字段，
   走"配置期拒绝"——创建/更新时与已启用策略同类型、同表（三级精确相等）、
   列相交（datamask）且主体可能重叠即报错；管理面只存精确资源，glob 显式拒绝；
   row_filter 按表互斥（校验与编译期双重拒绝）。

两份 09-06 spec 本身是分歧的：policy-subsystem 用 priority 首匹配；policy-service
明确"该约束取代优先级/首匹配机制，第一版无 priority 字段"。

目标：设计一套统一的优先级/冲突消解模型，两侧共用同一决策规则，消除 spec 分歧；
policy-server 引入 priority 后，"主体可能重叠即全部互斥"的硬约束得以放宽
（一条 `*` 主体策略不再挡住同列的用户级策略，拉开优先级即可共存）。

### 1.1 已确认的决策

| 决策点 | 结论 |
|---|---|
| 设计范围 | 统一两侧：PDP 与 policy-server 共用一套决策规则 |
| 消解主轴 | 显式 priority 数值主轴（Ranger 式首命中）；主体特异性仍只在策略内 items 间生效 |
| 配置期检查 | 仅同 priority 重叠才拒绝；不同 priority 自由重叠，高者胜 |
| row_filter 轴 | 移除按表互斥，编译期 AND 组合（对齐 PDP §5.3），fail-closed 方向 |
| 管理面 glob | 不开放，维持精确资源（`requireGlobFree` 不动） |
| 落地方案 | 方案 A：server 编译期消解，wire 协议 / core / CLI / PDP 零改动 |

### 1.2 关键技术前提

wire 协议已经是**按主体预消解**的扁平产物：`PolicyService.effective(name, subject)`
每次带主体编译，产出 column → UDF 的唯一绑定（`EffectiveConfigResponse.ColumnBinding`），
协议里没有策略原文。编译器现有"先到先得"的列消解（`emittedColumns` 去重）只是
顺序取自存储列表而非优先级——排序按 priority 落进去，首命中语义自动成立。
因此优先级消解天然落在 server 编译期，客户端零改动。

### 1.3 与既有 spec 的取代关系

- `2026-09-06-policy-service-design.md` §1.2"明确不做"第 3 条、§3.3 差异 3
  （同列多命中配置期拒绝、无 priority 字段）：**被本设计取代**；
- `2026-09-06-policy-subsystem-design.md` §5.2 的 PDP 决策序：**保持有效**，
  被确认为统一规则的 YAML 形态；
- `2026-09-16-policy-admin-rest-design.md` "不做策略优先级"：**被本设计取代**。

旧 spec 保留作历史记录，不回改。

## 2. 统一决策规则（本 spec 的核心产出）

**掩码（datamask）轴**，对给定资源 + 主体，依序：

1. `enabled=false` 跳过；主体不命中（`matchLevel=0`）跳过；
2. **priority 降序**；
3. 平局规则由配置形态提供：内联 YAML 按声明顺序（`PolicyIndex` 稳定排序，
   现状）；策略服务在写入期拒绝同 priority 的 datamask 重叠，故对任一主体
   而言，能同时命中它的同 priority datamask 策略列必然不相交，顺序不影响
   结果（实现上仍按 name 字典序保证确定性）；
4. 首个命中策略唯一生效；策略内 items 按主体特异性（user 精确 > group
   字典序最小 > `*`）取最具体。

**行过滤（row_filter）轴**：所有命中策略 AND 叠加，组合顺序 priority 降序 →
name 字典序（语义与顺序无关，顺序只为确定性与可读性）。未命中 → 该表不过滤。
AND 组合只会更严格，符合 fail-closed。

## 3. 数据模型与管理面 REST

- `PolicyEntity` 增加 `priority` 字段：int，缺省 0，不设范围限制（YAGNI）。
  record 组件以 `Integer` 承载，紧凑构造器归一化 null → 0。
- 创建/更新 API 接受可选 `priority`（缺省 0），策略详情与列表响应回显该字段。
- PG `schema.sql` 的 policies 表增加 `priority INT NOT NULL DEFAULT 0`；
  `JdbcPolicyStore` 读写该列；`InMemoryPolicyStore` 同步携带（仅测试用）。
- 落地位置是现有 `io.sqlmask.policyserver` 包；mask-policy-server 拆分按
  09-17 extraction spec 原样搬走，本设计不与之冲突。

## 4. 写入期校验（`PolicyValidator`）

现有重叠循环（同类型 + 三级表名相等 + 主体可能重叠）改为：

- **datamask**：仅当 `列相交 && subjectsMayOverlap && priority相等` 才拒绝；
  错误信息带上双方策略名与 priority 值，提示"拉开优先级或合并策略"。
- **row_filter**：重叠拒绝整体移除（不再按表互斥）；组合安全性由编译期
  AND 保证。
- 仅对已启用策略比对（维持现状）；`subjectsMayOverlap` 逻辑原样保留，
  只是降级为同 priority 拒绝的合取条件之一；`requireGlobFree` 不动。
- priority 变更走既有校验 + `configVersion` 单调 +1（现有机制，无新增）。

## 5. 编译期消解（`EffectiveConfigCompiler`）

enabled 且主体命中的策略先按 **priority 降序、name 升序**排序，再进现有循环：

- **datamask**：`emittedColumns` 去重语义不变，排序后即"最高优先级先占列"，
  首命中胜出——只加排序，消解逻辑零改动；
- **row_filter**：由"第二条即抛 CONFIG_ERROR"改为收集全部命中，按迭代序
  组合写入 `TablePayload.rowFilter`——单条时与现状逐字节一致（不加括号）；
  多条时组合为 `(f1) AND (f2)`，`filterExpr` 原文逐字节保留，仅外加括号与
  AND 连接。

## 6. wire 协议 / core / PDP 影响

**零改动**：`EffectiveConfigResponse` 结构不变（仍是预消解产物）；
`PolicyServiceConfigSource`（LRU 缓存、stale-but-available、fail-closed）、
`RewriteEngine`、CLI、mask-policy 的 `PolicyEngine` / `PolicyIndex` /
`PolicyYamlLoader` 均不动。优先级变更 → configVersion +1 → 客户端轮询刷新，
现有链路自洽。

## 7. 错误处理

新增错误仅一种形态：同 priority 重叠拒绝（现有 400 + `SqlMaskException.Code`
体系，信息扩展）；row_filter 编译期组合无新错误路径；编译期不新增运行时
失败模式。

## 8. 测试

- **校验器**：同 priority 重叠拒绝（含错误信息断言）/ 不同 priority 重叠放行 /
  主体不相交同 priority 放行 / row_filter 同表重叠放行 / `subjectsMayOverlap`
  各分支在合取条件下的行为；
- **编译器**：priority 决定列绑定归属（高者胜）/ 同 priority 不相交策略互不
  干扰 / row_filter 组合顺序、括号与原文保留 / name 平局确定性 / 单条
  row_filter 输出与现状逐字节一致；
- **REST**：priority 往返与缺省 0（null → 0）；
- **存储**：PG schema 列存在且默认 0；内存 store 往返；
- **PDP 回归**：mask-policy 现有测试全部保持通过（零改动的证据）。

## 9. 明确不做（YAGNI）

- 管理面 glob 资源选择器（已确认不开，将来另行立项）；
- priority 取值范围限制；
- deny 类型策略（Ranger deny 优先语义不引入）；
- 按列/按表细粒度 priority（priority 是策略级单值）；
- 主体特异性跨策略比较（特异性只在策略内 items 间生效，维持现状）；
- `GET /api/effective/*` 响应暴露 priority（产物已消解，无需暴露）；
- 旧 spec 文档回改（取代关系见 §1.3）。
