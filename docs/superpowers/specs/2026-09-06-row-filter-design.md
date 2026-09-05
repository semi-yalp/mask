# 行过滤（Row Filter）设计方案

## 1. 背景与目标

在现有脱敏改写（原始查询作内层、最外层对输出列调用脱敏 UDF）的基础上，增加行级过滤：
为 YAML 中声明的表配置一个静态过滤条件，改写时把条件注入到语句中所有引用该表的位置，
使不满足条件的行不进入查询结果，也不进入 `INSERT ... SELECT` / `CTAS` 写入的数据。

行过滤与列脱敏正交、可叠加。服务保持无状态与确定性：同样的配置 + SQL 永远产出同样的
输出，不引入用户、会话或运行时参数。

```text
YAML（表结构 + 列策略 + 行过滤条件）+ PostgreSQL SQL
  -> Calcite 解析
  -> 行过滤表引用替换（新增，校验前）
  -> Calcite 校验 + 血缘分析
  -> 行过滤已在内层生效；最外层按列策略包装 UDF
  -> PostgreSQL SQL
```

## 2. 关键决策与方案取舍

本次需求澄清未获得用户回复，以下决策按"与现有无状态、确定性改写设计最一致"的
原则选定，全部可推翻重来，见 §12 后续待办。

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

### 2.3 过滤条件表达式限制

条件必须是布尔表达式，且只允许引用**被过滤表自身的列**与常量：

- 不允许子查询（标量、`IN (...)`、`EXISTS` 等）：避免跨表过滤传播以及 A 的条件引用
  B、B 的条件又引用 A 的环状复杂度。配置期检测到表达式树内出现子查询直接
  `CONFIG_ERROR`；
- 过滤条件按可信配置对待（由配置维护者编写），但输出仍统一经 Calcite 渲染，不做
  字符串拼接，字面量转义与标识符引号规则与现有输出一致；
- 三值逻辑按 SQL 语义自然生效：`region = 'north'` 会排除 `region IS NULL` 的行，
  文档提示可用 `IS NOT DISTINCT FROM` 表达"NULL 也保留"。

## 3. MVP 范围

### 3.1 支持

- 表声明新增可选 `rowFilter` 字符串字段（空/空白视为未配置）；
- 注入覆盖所有基表引用位置：主查询 FROM、JOIN 两侧、逗号连接、用户派生表与子查询
  内部、CTE 主体、`UNION`/`INTERSECT`/`EXCEPT` 各分支、WHERE/SELECT 列表/HAVING 中
  子查询内部、`INSERT ... SELECT` / `CTAS` 的源查询；
- 同一表被引用多次（含自连接、CTE 定义 + 主查询引用）：每个引用位置各自注入一份
  相同条件（深拷贝，互不影响）；
- 与列脱敏叠加：行过滤在内层生效（先于聚合、去重、排序、分页和写入），脱敏 UDF
  仍在最外层；过滤谓词始终使用底层明文列，与现有"`WHERE` 使用明文列"语义一致；
- 只配置了行过滤、未命中任何列策略的语句：输出替换后的语句（Calcite 渲染）；
- 配置中没有任何 `rowFilter` 时：不做任何替换，输出与现状逐字节一致，既有测试
  与 TPC-DS 用例全部原样通过。

### 3.2 不支持

- 过滤条件包含子查询：`CONFIG_ERROR`（配置期失败，见 §2.3）；
- 被过滤的表出现在 `TABLESAMPLE`、`LATERAL`、`UNNEST` 等非常规表引用位置：
  `UNSUPPORTED_STATEMENT`，不静默跳过（安全优先）；
- `UPDATE`/`DELETE`、`WITH RECURSIVE`、`INSERT ... VALUES` 藏子查询等既有失败行为
  全部保持不变；`INSERT ... VALUES`（纯字面量）仍原样直通——没有表引用，无行可过滤。

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

## 5. 处理流程

### 5.1 管线顺序

每条语句：`parse` → **行过滤替换（新增）** → `validate`（快照文本 = 替换后的 SQL）→
`CteExpander` 内联 → 血缘分析 → 命中列策略则外层包装，否则直通。

替换必须在 `validate` 之前：`validate()` 的原始 SQL 快照被用作内层查询与直通输出，
替换后的树才能自然进入快照、校验、血缘与渲染全链路。`CteExpander` 在替换之后执行，
CTE 主体替换在前，内联出的派生表自然携带过滤条件。

### 5.2 表引用解析与替换规则

- 解析规则与既有语义一致（README）：3 段名精确匹配规范化 `catalog.schema.table`；
  2 段名按声明的 `catalog.schema` 组合解析 `schema.table`；1 段名跨所有声明组合解析。
  解析到**唯一**声明表且该表配置了 `rowFilter` 才替换；
- 解析不到、命中多个声明表、或该表未配置过滤：不替换，交给校验器按既有规则报错
  （歧义、未声明表等），替换器永远不是错误的第一个来源；
- 别名：原引用带别名（`AS c`）则保留原别名节点；无别名则以引用书写形式的表名
  （最后一段，保留原始大小写节点）作派生表别名；
- 每次注入使用条件节点的**深拷贝**：Calcite 校验器会原地修改节点，共享节点会串味；
- 替换统计注入次数（>0 即本语句发生了行过滤），用于结果标记。

### 5.3 结果记录语义

- `StatementRewrite` 新增 `rowFiltered` 布尔字段；`masked` 语义不变（是否加了外层
  脱敏包装）；
- `originalSql` 维持既有契约："校验前渲染的语句文本"。发生行过滤时它就是替换后的
  SQL（与写入语句路径已使用语句文本的现状并列）；`unchanged()` 仍是文本比较，
  展示层"原样输出"标记仅在 `!masked && !rowFiltered` 时展示；
- `rowFilter` 替换发生在校验前的解析树上，未命中过滤的语句不重渲染——输出与现状
  逐字节一致。

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
```

规则：

- `rowFilter` 可选字符串；空串/纯空白视为未配置；非字符串类型在配置加载时报
  `CONFIG_ERROR`；
- 条件在**每轮改写开始前**统一校验一次（见 §7），不合法的过滤条件使整轮改写在处理
  任何语句之前失败，与"任意语句失败则整体失败"的既有风格一致。

## 7. 组件与接口

新增包 `io.sqlmask.rowfilter`，两个类：

- **`RowFilterRegistry`**：按 `LoadedConfig` + 方言 + Schema 构建。对每个声明了
  `rowFilter` 的表：把条件包成 `SELECT * FROM <声明表全名> WHERE <条件>` 用方言解析，
  表达式树内出现子查询直接 `CONFIG_ERROR`；随后走一次与语句相同的校验（未知列、
  类型、非布尔条件在此报错），校验异常包装为 `CONFIG_ERROR`（消息带表名与条件上下文）。
  构建成功后缓存 `规范化表名 -> 条件 SqlNode`，供逐语句替换复用；
- **`RowFilterRewriter`**：`apply(SqlNode parsed, LoadedConfig loaded, RowFilterRegistry registry)`
  递归遍历语句树——对每个 `SqlSelect`（含各层子查询、CTE 主体、集合操作分支）处理其
  FROM：表引用（裸标识符或 `AS` 包装）按 §5.2 解析，命中则替换为程序化构造的
  `SqlSelect(SELECT * FROM <原引用> WHERE <条件深拷贝>)` + 别名包装；`SqlJoin` 两侧
  递归。被过滤表出现在 `TABLESAMPLE`/`LATERAL`/`UNNEST` 操作数位置时抛
  `UNSUPPORTED_STATEMENT`。registry 为空或零命中时原样返回。

`RewriteEngine` 接线：`rewrite()` 在构建 schema 后构建一次 registry（条件解析/校验
失败 → `CONFIG_ERROR`，整轮失败）；`rewriteOne()` 在 `parse` 之后、`validate` 之前对
读语句与写语句的源查询调用替换器。写入语句路径中，替换后若无列策略命中，输出
`composeWriteStatement(原节点, 替换后源渲染文本)` 而非原语句文本。

## 8. 错误处理

不新增错误码，全部复用：

| 场景 | 错误码 |
| --- | --- |
| rowFilter 字段类型非字符串 / 条件解析失败 / 含子查询 / 未知列 / 类型不匹配 / 非布尔条件 | `CONFIG_ERROR` |
| 被过滤表用于 TABLESAMPLE / LATERAL / UNNEST | `UNSUPPORTED_STATEMENT` |
| 其余（解析、校验、血缘、改写） | 与现状一致 |

配置期错误消息统一带 `table '<catalog.schema.table>': row filter ...` 前缀。

## 9. Web API 与页面

- `POST /api/rewrite`：`statements[]` 每项新增 `"rowFiltered": true|false`；
- `POST /api/config/parse`：返回的 `tables[]` 每项新增 `"rowFilter"` 字段（未配置为空）；
- 页面：表编辑器为每张表增加可选「行过滤」输入框（占位提示如 `status = 'active'`），
  YAML 源码 ⇄ 表单双向同步包含该字段；结果列表每条语句的标记在「已脱敏 / 原样输出」
  基础上增加「已行过滤」。

## 10. CLI

stdout 保持纯 SQL 输出，格式不变；不新增命令行参数。

## 11. 测试重点

- **RowFilterRegistry**：条件解析失败、含子查询、引用未知列、非布尔条件（如 `1 + 1`）、
  空白条件视为未配置、多表各配条件互不干扰、错误消息带表名前缀；
- **RowFilterRewriter**：别名保留、无别名默认表名别名、限定列引用可解析、自连接多处
  注入、CTE 主体与 CTE 引用后内联、嵌套派生表、UNION 分支、标量/IN/EXISTS 子查询内部、
  逗号连接、registry 为空时语法树引用相等（零改动）、条件深拷贝（多处注入互不影响）；
- **引擎集成**：过滤 + 脱敏叠加输出形态、仅过滤无策略、`WITH ... SELECT`、
  `INSERT ... SELECT`（masked=false 且 rowFiltered=true 时 rewrittenSql 含过滤）、
  CTAS、`INSERT ... VALUES` 直通且 rowFiltered=false、`unchanged()` 语义、
  TABLESAMPLE/LATERAL 失败；
- **配置加载与 API**：YAML `rowFilter` 加载与类型校验、`/api/config/parse` 回读、
  `/api/rewrite` 的 `rowFiltered` 字段；
- **回归**：未配置任何行过滤时，既有全部测试与 TPC-DS 用例输出不变。

## 12. 后续待办（本期明确不做）

- 按用户/角色区分过滤条件与运行时占位符（§2.1 路线 C）；
- 过滤条件中的子查询（需处理跨表过滤传播与环检测）；
- TABLESAMPLE / LATERAL / UNNEST 位置的被过滤表；
- 除 PostgreSQL 外的方言。
