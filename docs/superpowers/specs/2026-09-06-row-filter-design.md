# 行过滤（Row Filter）设计方案（v2）

> 修订记录：v1 为初版设计。v2 按两轮评审全面修订：
> [`2026-09-06-row-filter-design-review.md`](./2026-09-06-row-filter-design-review.md)（评审一，P1–P9）、
> [`2026-09-06-row-filter-design-code-review.md`](./2026-09-06-row-filter-design-code-review.md)（评审二，
> 1.1–1.6 / 2.1–2.8 / 3.1–3.5，含源码与字节码级复核）。主要变化：严格名称解析（多候选显式失败）、
> CTE 作用域规则与 CteExpander 同车修复、FROM 形态 fail-closed 白名单、全限定列引用显式拒绝、
> 一次注入与真正的 AST 隔离、`originalSql` 契约保持、谓词 AST 白名单、根级集合操作与两段名
> 承诺收窄。配套测试清单见
> [`2026-09-06-row-filter-test-design.md`](./2026-09-06-row-filter-test-design.md)（下称**测试设计**），
> 其决策表 D1–D6 与本稿一致。

## 1. 背景与目标

在现有脱敏改写（原始查询作内层、最外层对输出列调用脱敏 UDF）的基础上，增加行级过滤：
为 YAML 中声明的表配置一个静态过滤条件，改写时把条件注入到语句中所有引用该表的位置，
使不满足条件的行不进入查询结果，也不进入 `INSERT ... SELECT` / `CTAS` 写入的数据。

行过滤与列脱敏正交、可叠加。服务保持无状态与确定性：同样的配置 + SQL 永远产出同样的
输出，不引入用户、会话或运行时参数。

**安全立场**：行过滤是行级安全机制，一切"宁可拒绝、不可放过"。凡无法证明可安全改写的
形态一律显式失败（fail-closed），不允许静默跳过导致受控表被无过滤读取（fail-open）。

```text
YAML（表结构 + 列策略 + 行过滤条件）+ PostgreSQL SQL
  -> Calcite 解析
  -> 行过滤表引用替换（新增，校验前）
  -> Calcite 校验 + CTE 展开 + 血缘分析
  -> 行过滤已在内层生效；最外层按列策略包装 UDF
  -> PostgreSQL SQL
```

## 2. 关键决策与方案取舍

### 2.1 过滤条件的声明维度

- **A. 表级静态条件（选定）**：条件内联写在表声明上（`rowFilter`），凡查询该表一律注入。
  不需要用户/角色概念，改写结果完全确定，与现有无状态设计一致。
- B. 命名过滤策略 + 绑定（对齐列策略的 `policies`/`columns` 两段式）：行过滤条件不含
  UDF 参数、也不会跨表复用（条件引用的是本表列），多一层间接没有对应收益；引入
  多角色时可再升级为 `rowFilters: [{role, condition}]`，表级内联结构不阻碍该演进。
- C. 按角色/用户区分或运行时占位符（如 `dept_id IN (${user.depts})`）：需要定义占位符
  语法、转义规则、参数渲染与 API/CLI 新参数，第一版不做。

### 2.2 谓词注入点

- **A. 校验前的 SqlNode 表引用替换（选定）**：把语句中每个命中过滤的基表引用替换为
  派生表 `(SELECT * FROM t WHERE <条件>) AS <别名>`。保持现有"内层快照渲染、外层文本
  包装"的输出路径不变；替换后的树交给 Calcite 校验器，条件的类型检查、未知列报错
  全部复用现有机制；CTE 主体、各层子查询、JOIN 天然覆盖。
- B. RelNode 级包装（RelShuttle 替换 LogicalTableScan）：语义上最稳健，但最终 SQL 需要
  经 RelToSqlConverter 对整条语句重新渲染，改变所有查询（包括与行过滤无关的查询）的
  输出路径，且与文本级外层包装混用两种表示，回归风险大。
- C. 在最外层包装上加 WHERE：语义错误——外层只能看到输出列（过滤列可能未被输出），
  且过滤发生在 DISTINCT/GROUP BY/聚合/分页之后，与"行不进入结果"的目标相悖。

### 2.3 过滤条件表达式：AST 白名单（评审二 2.5 / 测试设计 D1）

条件必须只含**静态、确定性**的构造。仅靠"禁子查询 + 校验器"不够——校验器算子链末位
的 `UnknownFunctionTable` 会放行任意未知函数，`CURRENT_USER`、`random()` 等也能通过
校验，与无状态、确定性目标矛盾。因此对谓词 AST 实施白名单，registry 构建期逐节点检查：

**允许**：

- 被过滤表自身的列引用（单段名，且必须是该表声明的列）；
- 字面量（字符串、数值、布尔、日期/时间字面量）；
- 布尔逻辑 `AND` / `OR` / `NOT`；
- 比较 `=` `<>` `<` `<=` `>` `>=`；
- 算术 `+` `-` `*` `/` `%`；
- `IS NULL` / `IS NOT NULL`、`IS [NOT] DISTINCT FROM`；
- `IN`（常量值列表）。

**禁止（一律 `CONFIG_ERROR`）**：子查询（标量 / `IN (...)` / `EXISTS`）、函数调用（含
未知 UDF 与一切已知函数）、`CAST`、聚合与窗口、会话/用户/时间/随机函数、动态参数
（`?`）、序列访问（`NEXTVAL`/`CURRVAL`）以及任何其他运算。

三值逻辑按 SQL 语义自然生效：`region = 'north'` 会排除 `region IS NULL` 的行，文档提示
可用 `IS NOT DISTINCT FROM` 表达"NULL 也保留"。

## 3. MVP 范围

### 3.1 支持

- 表声明新增可选 `rowFilter` 字符串字段（空/空白视为未配置）；
- 注入覆盖所有基表引用位置：主查询 FROM、JOIN 两侧、逗号连接、用户派生表与子查询
  内部、CTE 主体、**嵌套位置**的 `UNION`/`INTERSECT`/`EXCEPT` 各分支、WHERE/SELECT
  列表/HAVING/GROUP BY/ORDER BY/窗口定义/OFFSET/FETCH/JOIN ON 中的子查询内部、
  `INSERT ... SELECT` / `CTAS` 的源查询；
- 同一表被引用多次（含自连接、CTE 定义 + 主查询引用）：每个引用位置各自注入一份
  相同条件，注入节点互不共享子树；
- 与列脱敏叠加：行过滤在内层生效（先于聚合、去重、排序、分页和写入），脱敏 UDF
  仍在最外层；过滤谓词始终使用底层明文列，与现有"`WHERE` 使用明文列"语义一致；
- 只配置了行过滤、未命中任何列策略的语句：输出注入后的语句（Calcite 渲染）；
- 配置中没有任何 `rowFilter` 时：rewriter 恒等返回，不做任何遍历与替换，输出与现状
  逐字节一致（字节级回归见 §11）；
- **CteExpander 同车修复**（评审二 P3，既有缺陷，行过滤管线踩在其上，见 §7.3）。

### 3.2 不支持（显式失败）

- 谓词违反 §2.3 白名单：`CONFIG_ERROR`（配置期）；
- **一段名命中多个声明表**：`VALIDATION_ERROR`（§5.2，不依赖 YAML 声明顺序）；
- **受控表以三段全名作列限定前缀**（如 `crm.public.customer.id`）：`UNSUPPORTED_STATEMENT`
  （§5.4，绑定无法保持，显式拒绝）；
- **未知 FROM 形态且子树引用受控表**（`TABLESAMPLE`、`LATERAL`、`UNNEST` 及其他
  未识别包装节点）：`UNSUPPORTED_STATEMENT`（§7.2，fail-closed）；
- 两段名 `schema.table`：不匹配、不注入，维持现状由校验器报错
  （测试设计 D2：现状即 `Object 'public' not found`，不为行过滤发明新解析行为）；
- **根级集合操作**：维持既有 `UNSUPPORTED_STATEMENT`——当前 `classify()`/`isQuery()`
  在 rewriter 之前就拒绝（评审二 2.6 实证），本期不扩（测试设计 D4）；嵌套在 CTE 体/
  子查询内的集合操作不受影响；
- `UPDATE`/`DELETE`、`WITH RECURSIVE`、`INSERT ... VALUES` 藏子查询等既有失败行为
  全部保持不变；`INSERT ... VALUES`（纯字面量）仍原样直通——没有表引用，无行可过滤；
- 写语句目标表永不进入行过滤遍历（§7.4）。

## 4. 核心语义与示例

### 4.1 过滤 + 脱敏叠加

```sql
-- customer 声明 rowFilter: "status = 'active'"，phone 绑定脱敏策略
-- 输入
SELECT c.id, c.phone
FROM crm.public.customer AS c
WHERE c.id < 100
ORDER BY c.id
LIMIT 10;
```

```sql
-- 输出
SELECT r.id, mask_phone(r.phone, 3, 4) AS phone
FROM (
  SELECT c.id, c.phone
  FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS c
  WHERE c.id < 100
  ORDER BY c.id
  FETCH NEXT 10 ROWS ONLY
) AS r;
```

行过滤在 `ORDER BY`/`LIMIT` 之前生效：被过滤的行不参与排序计数，也不会占据分页额度。

### 4.2 无别名引用与自连接

```sql
-- customer 声明 rowFilter: "status = 'active'"
-- 输入（自连接：一处带别名 a，一处无别名，默认可用表名 customer 限定引用）
SELECT a.id, customer.id
FROM crm.public.customer a
JOIN crm.public.customer ON a.manager_id = customer.id;
```

```sql
-- 输出（带别名的保留别名；无别名的取表名作派生表别名，限定引用继续解析）
SELECT a.id, customer.id
FROM (SELECT * FROM crm.public.customer WHERE status = 'active') AS a
JOIN (SELECT * FROM crm.public.customer WHERE status = 'active') AS customer
  ON a.manager_id = customer.id;
```

**边界（测试设计 D5）**：单段限定（`customer.id`）与显式别名限定不受影响；但
`SELECT crm.public.customer.id FROM crm.public.customer` 这类把受控表**三段全名作列
限定前缀**的写法在注入后不再绑定（派生表只暴露别名），今日可行、注入后回归，故在
注入发生时显式拒绝（`UNSUPPORTED_STATEMENT`），消息指引改用别名或单段限定。

### 4.3 写入语句

```sql
-- orders 声明 rowFilter: "region = 'north'"
-- 输入
INSERT INTO archive SELECT * FROM crm.public.orders;
```

```sql
-- 输出（目标表不过滤，源查询被过滤；无列策略时不加外层包装）
INSERT INTO archive SELECT * FROM (SELECT * FROM crm.public.orders WHERE region = 'north') AS orders;
```

`CTAS` 同理对源查询过滤；目标表名、目标列清单、`IF NOT EXISTS` 修饰原样保留。
row-filter-only 写语句即使 `masked=false` 也必须经 composer 重建（§7.4），并回归
目标列清单、输出列清单、`IF NOT EXISTS`、已支持/已拒绝的写语句形态不被静默改变。

## 5. 处理流程

### 5.1 管线顺序

每条语句：`parse` → **快照 originalSql**（替换前）→ **行过滤替换（新增）** →
`validate`（内部快照 = 注入后 SQL，用作包装内层）→ `CteExpander` 内联 → 血缘分析 →
命中列策略则外层包装，否则直通。

替换必须在 `validate` 之前：注入后的树才能自然进入校验、血缘与渲染全链路。
`CteExpander` 在替换之后执行；注入的派生表内层 FROM 保留原引用拼写，且注入仅发生在
"该名字在当前词法作用域不是可见 CTE"时——与修复后的 CteExpander 同一套作用域规则，
二者对同一引用的绑定结论一致，不存在"展开成 CTE 体"的错绑路径。

### 5.2 严格名称解析（评审 P1/P2/P8、评审二 1.1/1.2/2.7）

rewriter 的解析必须**先于且严格于**校验器——校验器的 catalog reader 沿搜索路径
首个命中即返回（字节码实证，无跨路径歧义检测），把歧义决策推迟给校验器会构成
fail-open。规则：

- **CTE 优先**：rewriter 自带嵌套 CTE 词法作用域。`WITH` 进入时压入新作用域，item 名字
  自登记起可见（item 体引用自身名视作 CTE 引用，最终由既有递归检测拒绝）；所有外层
  作用域同样可见（内层同名 CTE 遮蔽外层由展开器保证，rewriter 只需判定"是否 CTE 名"）。
  **可见 CTE 名永远胜过同名声明基表**，CTE 名不参与声明表短名匹配；
- **一段名**：在声明表中按名字找候选——0 个候选：不替换，走既有未声明表流程；
  1 个候选：该表配置了 `rowFilter` 则注入；**多于 1 个：抛 `VALIDATION_ERROR`**，消息
  列出全部候选（规范化全名排序），结果与 YAML 声明顺序无关；
- **三段名**：逐段精确匹配声明表（含大小写语义）；
- **两段名**：不匹配、不注入（D2）——现状解析不了两段表名（`Object 'public' not found`），
  注入后报错只是重复现状，为其发明匹配行为属于无依据的行为扩张；
- **标识符语义**：解析器已把未加引号标识符折叠为小写、带引号标识符保留原拼写，与声明
  名的匹配必须是**逐段精确比较**——quoted `"Customer"` 不得匹配声明的 `customer`
  （测试设计 F6：只有小写表配了过滤时，`"Customer"` 引用不得注入）；
- v1 的"替换器永远不是错误的第一个来源"原则**删除**：凡影响行级安全决策的名称歧义，
  rewriter 必须主动失败。

### 5.3 结果记录契约（评审 P6、评审二 2.1 / 测试设计 D6）

- `originalSql` **保持真实输入语义**：读语句 = 替换**前**的校验前渲染快照；写语句 =
  用户语句文本（现状）。注入后的 SQL 只存在于内部（校验输入/包装内层），绝不回写
  公开字段。理由链：若 `originalSql` 变成注入后文本，row-filter-only 语句
  `originalSql == rewrittenSql` → `unchanged()==true` → 前端隐藏「查看原始语句」折叠块
  → 用户原始输入在页面与 API 两侧都不可见，审计失真；
- `rewrittenSql` = 最终输出；`unchanged()` = 文本比较（row-filter-only 自然为 false）；
- `masked` 语义不变（是否加了外层脱敏包装）；新增 `rowFiltered` = 注入次数 > 0；
- 展示层"原样输出"标记仅在 `!masked && !rowFiltered` 时展示。

### 5.4 安全不变量（评审二 §4，实现与评审均以此为准）

1. **严格名称解析**：短名多候选显式失败，不依赖搜索路径顺序与 YAML 声明顺序；
2. **CTE 优先**：可见 CTE 永远遮蔽声明基表，内层作用域遮蔽外层；
3. **只处理读源**：INSERT/CTAS target 永不进入 rewriter；
4. **一次注入**：新构造的过滤派生表不参与当前遍历；
5. **真正隔离**：缓存未经校验器触碰的模板；校验、注入、CTE 展开分别使用独立 AST
   （注入用"模板文本 + 原引用"重新 parse 实现，见 §7.1）；
6. **fail-closed**：无法证明可安全改写的 FROM 形态不得静默跳过；
7. **绑定保持**：替换前后的列引用必须绑定到同一个逻辑 FROM 项——无法保持的形态
   （三段全名列限定）显式拒绝；
8. **确定性谓词**：拒绝动态参数、会话、时间、随机、UDF 和白名单外运算（§2.3）；
9. **输入保留**：`originalSql` 始终保留真实输入语义（§5.3）；
10. **解析一致**：rewriter 的表解析结论必须与校验器最终绑定一致；不一致的可能性
    （quoted 大小写、两段名）一律落在"不注入"一侧，由校验器按现状报错。

## 6. YAML 配置扩展

```yaml
metadata:
  tables:
    - catalog: crm
      schema: public
      name: customer
      rowFilter: "status = 'active' AND region = 'north'"
      columns:
        - name: id
          type: bigint
        # ...

policies: {}   # 仍为必填段（D3）：只有行过滤的配置也要显式声明空映射
```

规则：

- `rowFilter` 可选字符串；空串/纯空白视为未配置；非字符串类型在配置加载时报
  `CONFIG_ERROR`，消息带 YAML 路径（如 `metadata.tables[0].rowFilter`）；
- `policies` 段保持必填（D3），示例与文档明确"行过滤-only 配置也必须写 `policies: {}`"；
- 条件在**每轮改写开始前**统一校验一次（§7.1），不合法的过滤条件使整轮改写在处理
  任何语句之前失败，与"任意语句失败则整体失败"的既有风格一致。

## 7. 组件与接口

新增包 `io.sqlmask.rowfilter`；`io.sqlmask.sql` 新增深拷贝工具。

### 7.1 `RowFilterRegistry`

按 `LoadedConfig` + 方言 + Schema 构建，每轮 rewrite 一次。对每个声明了 `rowFilter`
的表：

1. 把条件包成 `SELECT * FROM <声明表全名（声明拼写渲染）> WHERE <条件>` 用方言解析，
   失败 → `CONFIG_ERROR`（保留 cause）；
2. **白名单检查**（§2.3）：遍历条件 AST，单段标识符必须命中该表声明列（小写比较，
   同时天然拒绝 `CURRENT_USER` 等会话量的裸标识符形态），运算种类必须落在允许集合，
   子查询/函数/动态参数等一律 `CONFIG_ERROR`；
3. **语义校验用第二次独立 parse**：未知列、类型不匹配、非布尔条件在此报错，包装为
   `CONFIG_ERROR`（消息带 `table '<catalog.schema.table>': row filter ...` 前缀，保留
   cause）。校验器会原地改树，**缓存的模板必须是校验器从未触碰的那棵**——绝不能缓存
   已参与校验的节点；
4. 缓存 `规范化 catalog.schema.table -> 条件模板`，键的规范化与 loader 的重复表检测
   一致；`TableMetadata.tableKey()` 是以空列名构造 `ColumnKey` 的死代码（一调用即抛
   `IllegalArgumentException`），删除之。

### 7.2 `RowFilterRewriter`

`apply(parsed, loaded, registry) -> (node, injections)`；registry 为空时恒等返回、零遍历。
**不可变改写**：输入树不被修改，未变化的子树按引用返回。遍历拆为三类（评审 P5）：

- **rewriteQuery**：SELECT 各操作数（FROM 之外的都走表达式遍历）、`WITH`（压 CTE
  作用域，逐 item 处理）、集合操作 operands、顶层 `ORDER BY` 包装（query + order/
  offset/fetch）；
- **rewriteFromItem**：白名单 {裸标识符、`AS`、`SqlJoin`（两侧 + 条件）、派生 SELECT/
  WITH}。`AS` 只替换 operand 0（原表），别名与**列别名列表**（`AS c(id, phone)`）原样
  保留，不产生双重 `AS`。白名单之外的形态（TABLESAMPLE、LATERAL、UNNEST、未知包装）：
  用同一套解析规则扫描子树——**证明不含受控表引用才原样保留；含或可能含即抛
  `UNSUPPORTED_STATEMENT`**，不允许静默跳过（fail-closed）；
- **rewriteExpression**：递归走任意表达式操作数与 `SqlNodeList`，进入其中的子查询
  （SELECT 列表、WHERE、HAVING、GROUP BY、ORDER BY、窗口、JOIN ON 里的
  EXISTS/IN/标量子查询全部到达）。

表引用替换（§5.2 规则）：命中即注入，注入计数 +1，**立即返回，当前遍历不再进入
新构造的派生表**（其内层 FROM 仍是原基表，再入即无限自我包装）。契约：rewriter 对
每条语句的新解析树恰好调用一次；重复调用同一棵树的幂等性不在契约内（引擎每条语句
重新 parse）。

三段全名列限定守卫：注入发生时，若语句中存在以该表声明路径作列限定前缀的引用，
抛 `UNSUPPORTED_STATEMENT`（§4.2 边界、D5）。

### 7.3 `CteExpander` 同车修复（评审 P3，先行）

- **作用域内层优先**：现实现 `ArrayDeque.push`（addFirst）配 `descendingIterator()`，
  实际外层优先，与 Javadoc "innermost first" 相反——嵌套同名 CTE 静默绑定外层。
  修复为每层 `Scope{completed, inFlight}`、内层优先查找；
- **递归误判**：`inProgress` 按裸名全局记录，外层 CTE 体内定义同名内层 CTE 被误判
  递归。修复为 in-flight 名随作用域走（每层至多一个在展开的 item）；
- **body 共享**：同一 CTE 多处引用复用同一 body 节点，而校验器原地改树——修复为每次
  引用深拷贝完整展开体（复用 `SqlNodeCopier`），同一语句内不存在共享可变子树；
- 既有语义回归（`cteShadowsTableName`、递归拒绝、TPC-DS S2）必须全部保持。

### 7.4 `RewriteEngine` 接线

- `rewrite()` 在构建 schema 后构建一次 registry（失败 → `CONFIG_ERROR`，整轮在任何
  语句处理之前失败）；
- **读语句**：parse → 快照 `originalSql`（替换前，§5.3）→ rewriter.apply → validate →
  血缘 → 包装或直通；`rowFiltered = injections > 0`；
- **写语句**：识别写语句 → pass-through 检查（不变）→ `querySourceOf` 提取源查询 →
  **仅把源查询交给 rewriter**（`RowFilterRewriter` 永不接收完整 DML/DDL 节点，目标表
  永不进入遍历）→ 校验、血缘、脱敏 → composer 与原目标重组。row-filter-only 写语句
  （`masked=false` 且 `rowFiltered=true`）也必须走 composer 重建，且重建用的源文本是
  **校验前**渲染快照——校验后 unparse 会重复 ORDER BY/FETCH（TPC-DS EXPECTED.md
  缺陷 #3）；composer 回归范围：INSERT 目标列清单、CTAS 输出列清单、`IF NOT EXISTS`、
  已支持的修饰、已明确 unsupported 的形态不被静默改变；
- `StatementRewrite` record 增加 `rowFiltered`；`SqlMaskService`/controller/CLI 透传。

## 8. 错误处理

错误码映射显式化，不依赖 `RuntimeException` 逃逸成 `INTERNAL_ERROR`：

| 场景 | 错误码 |
| --- | --- |
| `rowFilter` 字段类型非字符串；条件解析失败；违反 §2.3 白名单；语义校验失败（未知列/类型/非布尔） | `CONFIG_ERROR`（registry 统一包装，带表名前缀，保留 cause） |
| 一段名命中多个声明表 | `VALIDATION_ERROR`（候选列表排序展示，与 YAML 顺序无关） |
| 受控表三段全名作列限定前缀；未知 FROM 形态含受控表引用 | `UNSUPPORTED_STATEMENT` |
| 两段名、未声明表、其余既有错误 | 与现状一致（不注入，校验器报错） |
| 根级集合操作、`UPDATE`/`DELETE` 等 | 与现状一致（`classify()` 先于 rewriter 拒绝） |

## 9. Web API 与页面

完整 round-trip（评审二 3.4），缺一处即配置在页面与 YAML 间丢失：

- `POST /api/rewrite`：`statements[]` 每项新增 `"rowFiltered": true|false`；
- `POST /api/config/parse`：返回的 `tables[]` 每项新增 `"rowFilter"` 字段
  （未配置为空），`ConfigController.TableDto` 同步；
- 页面：表编辑器为每张表增加可选「行过滤」输入框（占位提示如 `status = 'active'`），
  同步覆盖：初始/样例状态、新增表的默认对象、YAML 生成、YAML/API 导入回填；
- 结果列表标记：在「已脱敏 / 原样输出」基础上增加「已行过滤」；「查看原始语句」
  折叠块与"原样输出"标记按 §5.3 语义调整（row-filter-only 必须仍能看到原始语句）；
- 空白 `rowFilter` 的往返语义：加载时归一为未配置，页面与 YAML 导出不产生空串字段。

## 10. CLI

stdout 保持纯 SQL 输出，格式不变；不新增命令行参数。

## 11. 测试重点

规范用例清单以[测试设计](./2026-09-06-row-filter-test-design.md)为准（A–H 组、TDD
顺序、P0–P3 优先级）。设计层面的强制要求：

- **A 组先行**：CteExpander 三个缺陷的 RED 测试（嵌套同名遮蔽、同名非递归误判、
  body 不共享）+ 既有语义回归锁；
- **名称与作用域**：CTE 与受控基表同名（外层引用 CTE 不注入、CTE 体内引用基表注入）、
  嵌套同名 CTE、一段名多候选（含调换 YAML 声明顺序结果一致）、quoted/大小写敏感
  标识符（F6：`"Customer"` 不注入）、两段名维持现状报错；
- **绑定与 AST**：无别名三段表 + 单段/别名限定列引用可用；三段全名列限定被拒；
  `AS c(x, y)` 列别名保留；单个表引用只包一层；自连接两边独立谓词树；同一 CTE 多次
  引用不共享可变节点；
- **遍历覆盖**：SELECT list / WHERE / HAVING / JOIN ON / GROUP BY / ORDER BY /
  OFFSET/FETCH / 窗口中的子查询；嵌套集合操作分支；未知 FROM 形态 fail-closed；
- **写入**：目标表配 rowFilter 不过滤；目标与源同名不同 schema；CTAS 名与受控表同名；
  row-filter-only / mask-only / 叠加三态；row-filter-only 写语句经 composer 后目标列
  清单与 `IF NOT EXISTS` 无损；
- **契约断言**：row-filter-only 时 `originalSql` = 原输入、`rewrittenSql` 含过滤、
  `masked=false`、`rowFiltered=true`、`unchanged()=false`；谓词含 `current_user` /
  未知 UDF / 动态参数 / CAST 被拒；
- **字节级回归（评审二 3.5）**：未配置任何 rowFilter 时，对普通 SELECT、WITH、已脱敏
  SELECT、INSERT、CTAS、未命中策略的直通语句与 TPC-DS 预期输出做**不做任何空白
  归一化**的 golden 对比（现有 `flat()` 归一化空白，发现不了字节差异）。

## 12. 后续待办（本期明确不做）

- 按用户/角色区分过滤条件与运行时占位符（§2.1 路线 C）；
- 过滤条件中的子查询（需处理跨表过滤传播与环检测）；
- 谓词白名单扩展（`CAST`、`LIKE`、`BETWEEN`、经审定的纯函数）；
- 作用域感知地改写受控表的全限定列引用（替代 §4.2 的显式拒绝）；
- 两段表名解析、根级集合操作（需连带 `classify()`、包装输出列、CTE 展开与血缘）；
- `TABLESAMPLE` / `LATERAL` / `UNNEST` 位置的被过滤表；
- 除 PostgreSQL 外的方言。
