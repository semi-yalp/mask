# 策略子系统设计方案（参考 Apache Ranger）

> 状态：设计稿 v1（2026-09-06）。目标：把"策略"从改写引擎的配置附件升级为独立子系统，
> 借鉴 Apache Ranger 的策略模型（资源层级 × 主体 × 策略项 × 决策引擎），为后续
> access 策略、策略管理服务化预留结构。

## 1. 背景与目标

当前 sql-mask 的"策略"是 YAML 配置的附属品：`policies:`（名字 → UDF + 参数）、
`columns:`（精确列绑定）、表级 `rowFilter:`。策略对**所有人无条件生效**，匹配只有
精确命中一种，策略代码（`io.sqlmask.policy`）与改写引擎耦合在同一构建里。

本设计把策略功能独立为一个子系统，核心变化：

1. **主体维度**（Ranger 的本质）：策略项声明 `users` / `groups`，改写请求携带
   `user` / `groups` 上下文，"谁在查询"决定哪个策略项生效；
2. **资源层级 + 通配**：策略按 `catalog → schema → table → column` 四级资源声明，
   每级支持 `*`；
3. **决策引擎（PDP）**：策略匹配、优先级、主体特异性收敛到一个无依赖的
   `PolicyEngine`，改写引擎降级为执行点（PEP）；
4. **独立边界**：`io.sqlmask.policy` 子系统只依赖 snakeyaml 与错误模型，零
   Calcite / Spring 依赖，将来可整体拆成 Maven 模块或独立服务。

安全立场与行过滤一致：**宁可拒绝、不可放过**。策略引用了不存在的资源、旧格式与
新格式混用、策略项缺主体等歧义配置一律显式失败。

## 2. 关键决策与方案取舍

### 2.1 独立形态

- **A. 仓库内独立子系统 + PDP 接口（选定）**：`io.sqlmask.policy` 分层
  （model / match / store），构建上仍是单 jar。真正决定"能否单独出去"的是边界与
  接口，不是构建拓扑；拆 Maven 模块或拆服务可在接口稳定后随时做，属于机械改动。
- B. 立即拆 Maven 多模块（`sqlmask-policy` + `sqlmask-engine`）：隔离最彻底，
  但当前单 pom + shade fat jar，拆分牵动全部源码路径与打包配置，收益主要是
  编译隔离，推迟。
- C. 独立策略服务（独立进程，REST 决策接口）：Ranger Policy Admin + PDP 的完整
  形态，需要持久化与部署，成本最高；作为远期形态，本设计保证决策接口可以原样
  暴露为 HTTP。

### 2.2 引入主体维度

- **A. 引入 user / groups 主体（选定）**：这是参考 Ranger 的意义所在；没有主体
  维度，本次只是换一种配置格式。匿名请求（未带主体）仍被 `*` 通配命中，保证
  旧行为可表达。
- B. 维持无主体、只做资源通配与优先级：保留为 v1 失败时的回退方案，模型上
  `Subject` 是决策参数，去掉它不伤结构。

### 2.3 v1 范围

只做 **dataMask 策略**与 **rowFilter 策略**（对应现有两种改写能力）。明确不做
（见 §10）：access 策略（allow/deny 拒绝查询）、掩码类型模板注册表、validity
时间表、自定义 condition、DB 持久化与版本化、审计、`isExcludes` / `isRecursive`。

## 3. Ranger 概念映射

| Ranger 概念 | sql-mask v1 对应 |
|---|---|
| 资源层级（service → db → table → column） | 隐式固定四级：`catalog → schema → table → column`，每级支持 `*` |
| dataMaskPolicy 的 policyItem（user/group → maskInfo） | `dataMaskItem { users, groups, udf, arguments }` |
| rowFilterPolicy 的 policyItem（user/group → filterExpr） | `rowFilterItem { users, groups, filterExpr }` |
| policyPriority（override） | `priority` 字段，高者优先 |
| policy isEnable | `enabled` 字段 |
| PolicyEngine（PDP） | `PolicyEngine`：按列+主体查掩码，按表+主体查行过滤 |
| 改写引擎 | 执行点（PEP）：lineage 来源列 / 基表引用 → 查询 PDP → 生成 SQL |
| serviceDef（资源与掩码类型定义） | 不做；掩码只有 CUSTOM 形态（udf + 参数） |
| accessPolicy、审计、validity、condition、tag | 不做（§10） |

## 4. 策略模型

### 4.1 policies.yaml

策略与表结构分离，独立文件 `policies.yaml`（CLI `--policies`，REST 字段
`policyYaml`）：

```yaml
policies:
  - name: mask-customer-phone
    enabled: true
    priority: 0
    resources:
      - catalog: crm
        schema: public
        table: customer
        column: [phone, email]      # 标量、列表或 "*"，见 §4.3
    dataMaskItems:
      - groups: ["*"]               # 或 users: [...]；至少一个非空
        udf: mask_phone
        arguments: [3, 4]

  - name: filter-archived-orders
    enabled: true
    priority: 0
    resources:
      - catalog: crm
        schema: public
        table: orders
    rowFilterItems:
      - groups: ["*"]
        filterExpr: "status <> 'archived'"
```

字段规则：

- `policies` 是必填顶层键（可为空列表：`policies: []`，此时无任何策略生效）；
- `name` 必填且唯一；`enabled` 缺省 `true`；`priority` 缺省 `0`；
- 一个策略**只能有一种 items**：`dataMaskItems` 或 `rowFilterItems`，两者同时出现
  或都缺失 → `CONFIG_ERROR`（对齐 Ranger 的 policyType 三选一）；
- `dataMaskItems`：`udf` 必填；`arguments` 缺省 `[]`，必须是有序标量
  （字符串/数字/布尔），校验规则与现有 `MaskingPolicy` 一致；
- `rowFilterItems`：`filterExpr` 必填非空白；表达式内容校验（白名单）在引擎侧
  构建期执行（§6.3），策略子系统只做非空与类型检查；
- 主体选择器 `users` / `groups`：字符串列表，`*` 是唯一通配符；两者都缺省或都为
  空 → `CONFIG_ERROR`（要表达"所有人"必须显式写 `groups: ["*"]`，与行过滤 v2
  "不发明隐式默认"的立场一致）。

### 4.2 资源匹配规则

- 资源必须声明到策略类型要求的层级：dataMask 策略必须含 `column` 级；rowFilter
  策略必须到 `table` 级且**不得**声明 `column`（声明了 → `CONFIG_ERROR`）；
- `catalog` / `schema` / `table` 三级必须显式出现（值可为 `"*"`）；
- 请求侧永远是解析后的具体值（来自血缘的具体列、语句中的具体表），`*` 只出现在
  策略声明侧；
- 资源标识符沿用现有规范化：未加引号的标识符折叠为小写（与 `ColumnKey` 一致）；
  用户与组名**不折叠**、大小写敏感；
- 通配展开校验（引擎侧，§6.2）：策略资源必须至少命中一个已声明表/列，否则
  `CONFIG_ERROR`（消息带策略名）——策略手误静默失效是安全风险，fail-closed。

### 4.3 Java 模型与包布局

```
io.sqlmask.policy.model
  Subject(String user, List<String> groups)              // user 可为 null（匿名）
  SubjectSelector(Set<String> users, Set<String> groups) // 含 "*"；不可同时为空
  PolicyResource(String catalog, String schema, String table, String column)
                                                         // column 为 null 表示表级（rowFilter）
  DataMaskItem(SubjectSelector selector, String udf, List<Object> arguments)
  RowFilterItem(SubjectSelector selector, String filterExpr)
  PolicyType { DATA_MASK, ROW_FILTER }
  Policy(String name, boolean enabled, int priority, PolicyType type,
         List<PolicyResource> resources, List<DataMaskItem> dataMaskItems,
         List<RowFilterItem> rowFilterItems)
  MaskInstruction(String policyName, String udf, List<Object> arguments)
  RowFilterHit(String policyName, String expr)

io.sqlmask.policy.match
  PolicyIndex(List<Policy>)      // 构建期排序索引：priority desc → 声明顺序 asc
  PolicyEngine                   // PDP，见 §5.4

io.sqlmask.policy.store
  PolicyYamlLoader               // snakeyaml 解析 + 结构校验，路径化诊断
```

依赖边界：`io.sqlmask.policy.**` 只依赖 `org.yaml:snakeyaml` 与
`io.sqlmask.error.SqlMaskException`（后者已核实为纯 Java）。**不依赖** Calcite、
Spring、picocli，也不依赖 `io.sqlmask.metadata`（`TableMetadata` 引用了 Calcite，
旧格式转换因此放在引擎侧，见 §6.2）。

现有 `MaskingPolicy` / `PolicyRegistry` / `PolicySelector` 在适配完成后退役，
掩码指令统一为 `MaskInstruction`（三字段同构，`OutputRewrite` / `RewritePlan`
改用它）。

## 5. 决策语义（确定性）

### 5.1 主体匹配与特异性

- `user` 精确匹配 > `group` 精确匹配（多组同时命中按字典序最小者）> `*` 通配；
- 同一策略内多个 items 命中同一资源：按上述特异性，同特异性按 items 声明顺序，
  取第一个；
- 匿名请求（`user` 与 `groups` 均未提供）：仅 `*` 通配命中；user / group 精确
  条件一律不命中。旧格式转换出的策略全部是 `*` 主体，行为与现状逐字节一致。

### 5.2 资源匹配

- 逐级匹配：策略资源级值 `*` 匹配任意请求值，否则规范化后相等才匹配；
- 多个策略命中同一资源：`enabled: false` 跳过 → `priority` 高者优先 → 声明顺序
  靠前者优先。

### 5.3 冲突解决：掩码取唯一，行过滤 AND 叠加

- **掩码**：一个输出列对一个主体最多应用一条掩码指令（UDF 调用每输出列一次，
  不可叠加），按 §5.1/§5.2 取唯一命中，未命中 → 该列不脱敏（与现状一致）；
- **多来源输出列**（一个输出表达式有多个来源列，如 `a.x + b.y`）：对每个来源列
  分别查询 PDP，在**有掩码命中的来源列**中按规范化
  `catalog.schema.table.column` 字典序取最小者应用其指令——与现状
  `PolicySelector` 规则一致；`priority` 只解决"同一列被多个策略命中"的冲突，
  不参与跨列选择；
- **行过滤**：谓词可以安全组合——命中同一表+主体的**所有** rowFilterItem 按
  §5.2 排序后以 `AND` 叠加（多条件交集是更严格的过滤，符合 fail-closed）；
  一个都不命中 → 该表不过滤。每个引用位置（含自连接）注入相同的组合条件。

### 5.4 决策接口（PDP）

```java
public final class PolicyEngine {
  /** 列级掩码决策：命中返回指令，未命中返回 empty。 */
  public Optional<MaskInstruction> maskFor(
      String catalog, String schema, String table, String column, Subject subject);

  /** 表级行过滤决策：返回按 §5.2 排序的全部命中项，组合（AND）由调用方完成。 */
  public List<RowFilterHit> rowFiltersFor(
      String catalog, String schema, String table, Subject subject);
}
```

纯函数、无状态：同样的策略集 + 资源 + 主体永远得到同样的决策。将来拆独立服务时，
这两个方法 1:1 映射为 `POST /policy/decision` 类接口。

## 6. 与现有引擎的集成（PEP 适配）

### 6.1 改写管线

```
metadata.yaml（表结构） + policies.yaml（策略） + SQL + (user, groups)
  -> 策略装载：PolicyYamlLoader（新）或 LegacyPolicyAdapter（旧，§6.2）
  -> PolicyResourceResolver（引擎侧）：资源解析校验 + "*" 对声明列展开核对（§4.2）
  -> PolicyIndex / PolicyEngine（PDP）
  -> Calcite 解析
  -> 行过滤表引用替换：每张受控表的注入条件 = PDP 按表+主体命中的 exprs AND 组合
  -> Calcite 校验 + CTE 展开 + 血缘分析
  -> 最外层包装：输出列来源列 → PDP maskFor → UDF 包装
  -> 方言渲染输出
```

行过滤替换器与列包装器的现有管线、AST 隔离、`originalSql` 契约全部不变；变化
仅在"条件从哪来"（原来是表上的静态字段，现在是 PDP 决策）与"掩码指令从哪来"
（原来是 `PolicySelector` 精确查表，现在是 PDP 决策）。

### 6.2 旧格式兼容与两套来源互斥

- 只提供 `--metadata`（或 `metadataYaml`）：行为与现状**逐字节一致**——
  `LegacyPolicyAdapter`（引擎侧，`io.sqlmask.config`）把 `policies` / `columns` /
  `rowFilter` 转换为等价策略集（主体 = `groups: ["*"]`，priority 0，enabled），
  现有全部 golden 测试原样钉死；
- 提供 `--policies`（或 `policyYaml`）时，`metadataYaml` 中的策略内容必须为空：
  `policies` 键必须是 `policies: {}`，`columns` 不得出现非空列表，任何表不得带
  非空 `rowFilter`，否则 `CONFIG_ERROR`（消息指明冲突字段）——两份策略来源并存
  是歧义，显式拒绝；
- `policies.yaml` 与 `metadata.yaml` 是两个文件、两种模式，不做自动合并。

### 6.3 filterExpr 白名单校验保留在引擎侧

条件 AST 白名单（行过滤 v2 §2.3）依赖 Calcite 解析，保留在引擎侧构建期逐条执行；
策略子系统只校验非空。错误消息区分来源：旧格式的消息带**表名**前缀（保持逐字节
不变），新格式的消息带**策略名**前缀（如
`policy 'filter-archived-orders': filterExpr: ...`）。

## 7. API / CLI / 页面

- **CLI**：新增 `--policies <path>`、`--user <name>`、`--groups g1,g2`
  （逗号分隔，可重复出现，合并去重保序）；退出码约定不变（0 成功 / 1 处理失败 /
  2 用法错误）；
- **REST `POST /api/rewrite`**：请求体新增可选字段 `policyYaml`、`user`、
  `groups`（字符串数组）；互斥规则见 §6.2；
- **REST `POST /api/policies/parse`**：请求 `{ "policyYaml": "..." }`；成功返回
  结构化策略清单（name / enabled / priority / type / resources / item 摘要），
  失败返回 400 `CONFIG_ERROR`（路径化诊断），供页面校验回填；
- **页面**：执行区新增主体输入（用户输入框 + 组列表输入，留空 = 匿名）；新增
  「策略」页签：编辑 `policies.yaml`，点「校验并应用」调用
  `/api/policies/parse` 回填错误或摘要；执行改写时若策略页签有内容则随请求发送
  `policyYaml` 与主体。

## 8. 错误行为清单

| 场景 | 行为 |
|---|---|
| policies.yaml 解析失败、结构违规（缺 name、items 双缺/双全、主体空、资源层级不符） | `CONFIG_ERROR`（路径化消息） |
| 策略资源解析不到任何已声明表/列（含 `*` 展开为空） | `CONFIG_ERROR`（消息带策略名） |
| `policyYaml` 与 `metadataYaml` 策略内容同时非空 | `CONFIG_ERROR`（指明冲突字段） |
| 新格式 `filterExpr` 违反白名单/类型校验失败 | `CONFIG_ERROR`（消息带策略名前缀） |
| 旧格式一切行为（含错误消息） | 与现状逐字节一致 |
| 策略集为空（`policies: []`）或全部 `enabled: false` | 合法；PDP 恒返回未命中，改写按"无策略"路径原样输出 |
| `--user`/`--groups` 未提供 | 合法（匿名主体）；仅 `*` 通配策略项生效 |

## 9. 测试策略

- **策略子系统纯单测**（不引 Calcite）：主体特异性与匿名语义、资源通配与规范化、
  priority / 声明顺序 / enabled 跳过、掩码唯一命中、行过滤多命中排序、YAML 解析
  的路径化错误、`policies: []` 与空文件边界；
- **引擎集成**：旧 YAML 全量回归逐字节不变（现有 golden 即回归网）；同一策略用
  新格式表达 → 与旧格式输出一致；`user` / `groups` 区分生效（同列对不同主体
  掩码不同 / 不掩码）；多 rowFilter 策略 AND 叠加；互斥规则错误路径；
- **server 测试**：`/api/rewrite` 新字段、`/api/policies/parse` 成功与 400；
- **页面**：手动验收清单（主体输入、策略页签校验回填、YAML ⇄ 表单往返）。

## 10. 明确不做（接口留位）

access 策略（allow/deny 查询拒绝，`PolicyEngine` 预留 access 类决策方法）、
掩码类型模板注册表（serviceDef）、validity 时间表、自定义 condition、tag 策略、
DB 持久化与策略版本化、审计日志、`isExcludes` / `isRecursive`、策略热更新
（文件 watch）、多策略文件合并、Maven 多模块拆分与独立服务化（决策接口已按
1:1 映射 HTTP 设计）。
