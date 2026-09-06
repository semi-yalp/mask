# 行过滤设计方案评审（独立代码评审）

- 评审对象：[`2026-09-06-row-filter-design.md`](./2026-09-06-row-filter-design.md)
- 评审日期：2026-09-06
- 评审方式：通读设计文档与全部相关代码（RewriteEngine、PostgresqlDialectAdapter、CteExpander、SqlValidatorFactory、YamlConfigLoader、LineageAnalyzer、SqlRewriteService、前端页面、TPC-DS 记录），反汇编 Calcite 1.42 关键方法（`SqlBasicCall.clone`、`SqlValidatorUtil.getTableEntry`），并用临时测试实证名称解析行为（测试已删除，工作区无残留）。

## 结论

**核心路线正确、值得做，但设计稿有两处与代码现实相反的关键假设，直接实现会产生"该过滤而未过滤"的静默安全漏洞。必须修订后再进入实现。**

- 阻断性问题 2 项（P1 短名多候选 fail-open、P2 CTE 作用域缺失），均为实证；
- 既有代码缺陷 1 项需同车修复（P3 CteExpander 作用域 bug，实证）；
- 重要问题 6 项（P4–P9）需在实现规格中落实或显式决策。

## 一、路线判断：注入点选对了

§2.2 选 A（校验前 SqlNode 表引用替换）并否决 B/C，论证与代码事实吻合：

- 现有输出路径从不经过 RelToRelConverter/RelToSqlConverter——非脱敏语句返回 `validated.originalSql()`（校验前 unparse 快照，`SqlRewriteService.java:48`），脱敏语句在其外做文本包装（`buildWrapper`）。选 B（RelNode 级包装）确实会改变所有查询的输出路径，否决理由成立；
- §5.1 的管线顺序（替换 → validate → CteExpander → 血缘 → 包装）与 `PostgresqlDialectAdapter.validate()` 的实现（unparse 快照 → expand → validate）严丝合缝，替换后的树自然流入全链路；
- 写语句只改源查询、`composeWriteStatement` 重组目标，与 `querySourceOf` / `RewriteEngine` 现状对齐；§7 特意指出"替换后无策略命中也要 compose"是正确的——否则过滤会被 `RewriteEngine.java:100` 的直通路径丢掉；
- 零配置零改动承诺机制上成立（rewriter 原样返回同一节点 → unparse 结果不变）；
- §2.3 对三值逻辑（NULL 行被 `=` 排除）的提示、错误码克制复用、§11 测试清单覆盖面均不错。

血缘层面确认了一点设计稿未提但成立的事实：注入的派生表对血缘透明——`LineageAnalyzer` 基于 `getColumnOrigins`，Filter/派生表不改变列来源指向基表，行过滤 + 脱敏叠加（§3.1）在机制上没有障碍。

## 二、阻断性问题（均有实证）

### P1. "命中多个声明表交给校验器报歧义"是错误前提——实际静默绑定第一个，构成 fail-open

设计 §5.2 规定多候选"不替换，交给校验器按既有规则报错（歧义）"，并上升为原则："替换器永远不是错误的第一个来源"。**该前提与实现相反**：

- 字节码层面：`SqlValidatorUtil.getTableEntry` 沿搜索路径迭代，`entry != null` 即返回，无任何跨路径歧义检测；
- 运行层面（临时测试实证）：声明 `a.public.customer` 与 `b.public.customer` 后，`SELECT phone FROM customer` **校验直接通过**，静默绑定第一个候选；
- README 声称的"命中多个时报歧义错误"在代码中不存在，测试从未覆盖；`tpcds/EXPECTED.md` E4 已记录 Calcite 静默消歧倾向。

后果：rewriter 见多候选跳过注入 → validator 静默绑定（按 schema 注册顺序）→ 只要被绑定的表未配 `rowFilter`，受控表就被无过滤读取，且结果依赖 YAML 声明顺序。

**修订要求**：

```text
0 个候选：走现有未声明表流程
1 个候选：按该声明决定是否注入
>1 个候选：主动抛错（不依赖搜索路径顺序，结果与 YAML 声明顺序无关）
```

"替换器永远不是错误的第一个来源"原则对行级安全不成立，应删除。

### P2. 表名解析规则没有 CTE 作用域——同名 CTE 会被误注入基表过滤

现有管线中"CTE 遮蔽基表"由 CteExpander 内联 + validator 保障（`LineageAnalyzerTest.cteShadowsTableName` 可证）。行过滤 rewriter 运行在两者**之前**，而 §5.2/§7 的解析规则只按 1/2/3 段名匹配声明表。`WITH customer AS (SELECT ... FROM 其他表) SELECT * FROM customer` 中主查询的 `FROM customer` 会被误判为受控基表并注入过滤。

更隐蔽的错绑路径：注入产物 `(SELECT * FROM customer WHERE status='active') AS customer` 的内层 `FROM customer` 随后被 CteExpander 展开成 CTE 体——过滤条件最终作用在 CTE 输出上。CTE 恰有同名列时静默错绑；没有时碰巧报错。

**修订要求**：rewriter 自带词法作用域——可见 CTE 名优先于声明表、内层 WITH 遮蔽外层、CTE item 只见前序 item、CTE 名不参与声明表短名匹配。

### P3. CteExpander 作用域实现本身有 bug——行过滤管线踩在它上面，需同车修复

既有缺陷，非设计引入，但 §5.1 让替换后的树继续经 CteExpander 内联，行过滤测试矩阵必然命中。两处均实证：

| 缺陷 | 位置 | 实证结果 |
| --- | --- | --- |
| `lookup()` 用 `descendingIterator()` 配合 `push`（=addFirst），实际**外层作用域优先**，与注释 "innermost first" 相反 | `CteExpander.java:274-286` | 嵌套同名 CTE 时内层引用绑到外层体，合法 SQL 报 `Column 'tag' not found` |
| `inProgress` 按裸名记录，外层 CTE 体内定义同名内层 CTE 被误判递归 | `CteExpander.java:91-99` | 合法 SQL 报 `LINEAGE_UNKNOWN: recursive CTE 'x'` |

另有隐患：`derivedTable()`（`CteExpander.java:215-224`）对同一 CTE 的多处引用**复用同一个 body 节点**，而 validator 原地修改节点——同一语句内共享可变子树，注入的谓词节点会进入该共享，风险放大。rewriter 实现作用域时不能照抄此处语义。

## 三、重要问题

### P4. "深拷贝"无机制定义，且 registry 缓存模板会被校验污染

- `SqlBasicCall.clone(pos)` 是浅拷贝（字节码核实：直接把原 `operandList` 传给 `createCall`，子节点全共享）。§5.2 只写"深拷贝"，实现者若用 `clone()` 则形同虚设；
- §7 让 registry "走一次与语句相同的校验"后缓存 SqlNode——但校验器原地改树（`ValidatedSql` 快照机制存在的全部理由），缓存的是被污染的树。

**修订要求**：缓存未校验的原始模板；校验与每次注入分别用独立深拷贝（递归 copier，或缓存条件文本、每次注入重新 parse）。

### P5. 表达式子查询遍历机制未定义，且未规定"不再入新节点"

§3.1 承诺覆盖 WHERE/SELECT 列表/HAVING 中的子查询，§7 组件描述却只说"对每个 SqlSelect 处理其 FROM"。JOIN ON 里的 EXISTS、SELECT 列表里的标量子查询都需要**表达式级遍历**才能到达。应拆成 query / from-item / expression 三类遍历，并列出覆盖位置清单（SELECT list、WHERE、HAVING、GROUP BY、ORDER BY、JOIN ON、window 定义、集合操作 operands）。

同时必须写明：命中替换后**不得进入新构造的派生表**——其 FROM 仍引用原基表，再入即无限自我包装。

### P6. `originalSql` 语义重定义破坏 API/UI 契约

§5.3 让 `originalSql` 在行过滤时等于替换后 SQL。可证后果链：row-filter-only 语句 `originalSql == rewrittenSql` → `unchanged()==true` → 前端以 `s.unchanged` 控制「查看原始语句」折叠块（`index.html:619`）→ **用户原始输入在页面和 API 两侧都不可见**，审计无法对比工具改了什么。且写路径今天 `originalSql` = 用户原文（`RewriteEngine.java:87,100`），重定义后读写两条路径语义分裂。

**修订要求**：`originalSql` 保持真实输入语义，替换后文本用内部字段承载；`rowFiltered` 独立标记保留。

### P7. "静态、只含本表列与常量"没有执行机制支撑

§2.3 的约束只靠禁子查询 + validator。但 validator 算子链末位挂着 `UnknownFunctionTable`——任意未知函数放行为透明标量函数。`current_user = owner`（CURRENT_USER 是已知算子，与列比较可通过校验）、`is_allowed(status) = true`（未知函数取首参类型）都能通过，与"无状态、确定性"卖点直接矛盾。

**修订要求**：二选一——谓词 AST 白名单（本表列、字面量、比较/布尔/算术、IS NULL、IS [NOT] DISTINCT FROM；显式禁动态参数、聚合/窗口、会话/时间/随机函数）；或收窄文档承诺。

### P8. 两段名 `schema.table` 的"既有语义"不存在

实证：`SELECT phone FROM public.customer` **今天就报 `VALIDATION_ERROR: Object 'public' not found`**——搜索路径机制解析不了两段名；README 只记载一段名规则；全部测试只用一段/三段名。§5.2 称两段名解析"与既有语义一致（README）"不成立。

**修订要求**：二选一——rewriter 不做两段名匹配（保持现状报错，最简）；或注入时把内层引用规范化为声明表三段全名（原本报错的语句将开始通过，属行为扩张，须显式决策并写明）。

### P9. 根级集合操作承诺与现状矛盾

§3.1 把"UNION/INTERSECT/EXCEPT 各分支"列入支持，但 `classify()`/`isQuery()`（`PostgresqlDialectAdapter.java:63-98`）在 rewriter 之前就拒绝根级集合操作（TPC-DS EXPECTED.md 实测：根级 INTERSECT/EXCEPT 非零退出）。嵌套在 CTE 体/子查询里的集合操作可用（S2 用例）。

**修订要求**：改写为"嵌套位置的集合操作"，或明确扩 `classify` 的连带改动（包装输出列、CTE 展开、血缘都要跟着动）。

## 四、中低优先级

| 项 | 说明 |
| --- | --- |
| FROM 形态改白名单 | §3.2 点名 TABLESAMPLE/LATERAL/UNNEST 是黑名单思路；babel parser 还能产出其他 FROM 包装节点，未知节点静默跳过即 fail-open。建议白名单 {裸标识符、AS、JOIN、派生 SELECT、WITH}，配置了任何 rowFilter 时遇到其他形态直接 `UNSUPPORTED_STATEMENT`（宁误拒不放过） |
| 无别名 + 多段限定列引用会破 | `SELECT crm.public.customer.id FROM crm.public.customer` 今天能过，注入后派生表只暴露别名，三段列引用不再绑定。不漏数据但属回归，应文档化为不支持或做作用域感知改写 |
| `AS c(id, phone)` 列别名 | AS 的 operands 为 [表, 别名, 列名...]，需明确只替换 operand 0、其余原样保留，避免丢列别名或双重 AS |
| §6 YAML 示例缺 `policies` | `requireMapping` 对缺失节点抛 `CONFIG_ERROR`（`YamlConfigLoader.java:207-211`），示例按现状加载不了；补 `policies: {}` 或改 loader |
| registry 键 | 勿复用 `TableMetadata.tableKey()`——它用空列名构造 `ColumnKey`，而 `normalize` 拒绝空白，一调用即抛（现为死代码）。新建三段 `TableKey` |
| 写路径渲染文本要指明 | §7 的"替换后源渲染文本"必须是**校验前** unparse——校验后 unparse 会重复 ORDER BY/FETCH（EXPECTED.md 缺陷 #3） |
| "逐字节一致"缺测试牙齿 | 现有测试 `flat()` 归一化空白，发现不了字节差异；需不做归一化的 golden 对比 |
| 前端/API round-trip | §9 之外还需覆盖 `TableDto`/`ConfigController` 字段、YAML 双向同步含空值往返 |

## 五、§11 测试清单需补的用例

- CTE 与受控基表同名：外层引用 CTE 不注入、CTE 体内引用基表注入；
- 一段名多候选：含调换 YAML 声明顺序结果一致；
- 两段名、带引号/大小写敏感标识符；
- 谓词含 `current_user` / 未知 UDF / 动态参数被拒；
- 无别名三段表配三段限定列引用；
- `AS c(x, y)` 列别名列表保留；
- 单个表引用只包一层（无自我重入）；
- 目标表配了 rowFilter 的 INSERT/CTAS（目标不被过滤）；
- row-filter-only 契约断言：`originalSql`=原输入、`rewrittenSql` 含过滤、`unchanged=false`、`masked=false`。

## 六、最终意见

保留 §2.2-A 的总体路线与 §5.1 的管线顺序。修订要求汇总：

1. **P1、P2 必须先回写进设计**（严格名称解析 + CTE 作用域规则），这是安全边界；
2. **P3 作为同车修复项**（CteExpander 作用域与 body 共享）；
3. **P4/P5/P6 落成明确机制**（真深拷贝、三类遍历边界、`originalSql` 契约）；
4. **P7/P8/P9 显式决策**（收窄承诺或扩大范围并写清连带改动）。

完成上述修订并补齐安全回归测试后，该设计方可作为直接编码的实施规格。
