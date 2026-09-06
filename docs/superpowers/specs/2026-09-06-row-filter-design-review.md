# 行过滤设计方案评审总结

评审对象：[`2026-09-06-row-filter-design.md`](./2026-09-06-row-filter-design.md)

评审结论：**总体技术路线可行，但当前设计稿不宜直接进入实现。**

在 Calcite 校验前将受控基表替换为带 `WHERE` 的派生表，可以复用现有校验、CTE 展开、血缘分析和最外层脱敏包装流程，无需推翻。不过，当前方案在名称解析、CTE 作用域、遍历边界、AST 所有权和 API 契约上存在缺口。其中部分问题可能导致静默漏过滤或改变原查询绑定，必须先修订。

## 1. 阻塞问题

### 1.1 CTE 名称遮蔽未进入表解析规则

`RowFilterRewriter` 若只按一至三段表名匹配声明表，可能把同名 CTE 引用误认成受过滤基表。

```sql
WITH customer AS (
  SELECT id FROM crm.public.other_table
)
SELECT id FROM customer;
```

若元数据中存在带 `rowFilter` 的 `crm.public.customer`，主查询的 `FROM customer` 必须绑定 CTE，不能注入基表过滤。

设计需要规定：

- rewriter 维护嵌套 CTE 词法作用域；
- 当前可见 CTE 名优先于声明基表；
- CTE item 只能看到语义上已可见的前序 item；
- 内层 CTE 遮蔽外层同名 CTE；
- CTE 名称不得参与声明表的短名匹配。

相关实现还需同步检查 `CteExpander`：当前作用域通过 `push()` 入栈，但查找使用 `descendingIterator()`，可能优先找到外层作用域；递归检测又只按裸名称记录，合法的嵌套同名 CTE 可能被误判为递归。

参考：

- `src/main/java/io/sqlmask/sql/CteExpander.java:38-42`
- `src/main/java/io/sqlmask/sql/CteExpander.java:84-103`
- `src/main/java/io/sqlmask/sql/CteExpander.java:269-285`
- `src/test/java/io/sqlmask/lineage/LineageAnalyzerTest.java:140-147`

### 1.2 短表名歧义时不能静默跳过过滤

设计当前规定：一段名命中多个声明表时不替换，交由 validator 报歧义。该假设不可靠。

当前 catalog reader 将多个 schema path 按顺序交给 Calcite；Calcite 可能选择路径中的第一个匹配，而不是报告所有匹配之间的歧义。

例如：

```text
a.public.customer  配置 rowFilter
b.public.customer  未配置 rowFilter
```

```sql
SELECT * FROM customer;
```

若 rewriter 因多候选而跳过，validator 又选择第一个表并成功，查询可能读取受控表却没有过滤，形成 fail-open。

应统一规定：

```text
0 个候选：按现有未声明表流程处理
1 个候选：按该声明决定是否注入
>1 个候选：显式抛 VALIDATION_ERROR
```

结果不得依赖 YAML 表声明顺序。“替换器永远不是错误的第一个来源”这一规则应删除；凡影响行级安全决策的名称歧义，都必须主动失败。

参考：

- `src/main/java/io/sqlmask/sql/SqlValidatorFactory.java:78-96`
- `src/main/java/io/sqlmask/dialect/PostgresqlDialectAdapter.java:277-289`
- `tpcds/EXPECTED.md:91-98`

### 1.3 未知 FROM 形态必须 fail-closed

设计只点名拒绝 `TABLESAMPLE`、`LATERAL`、`UNNEST`，但 Calcite 的 FROM 树还可能出现其他包装器或方言节点。未知节点若默认跳过，可能让受控表未经筛选进入结果。

应采用白名单：

- 裸 `SqlIdentifier`；
- `AS`；
- `SqlJoin`；
- 派生查询；
- 明确支持的集合查询与 `ORDER BY` 包装。

遇到其他 FROM 形态时：

- 能证明子树不含受控基表时，可保持现有行为；
- 包含或可能包含受控基表、但无法安全替换时，抛 `UNSUPPORTED_STATEMENT`；
- 不允许静默跳过。

`TABLESAMPLE`、`LATERAL`、`UNNEST` 等节点必须先分类再递归 operand，不能先改写其内部表引用，再决定是否拒绝。

### 1.4 无别名派生表替换会破坏全限定列引用

原 SQL 可以合法使用完整限定符：

```sql
SELECT crm.public.customer.id
FROM crm.public.customer;
```

按当前方案替换后：

```sql
SELECT crm.public.customer.id
FROM (
  SELECT * FROM crm.public.customer WHERE active
) AS customer;
```

派生表只暴露关系名 `customer`，原来的 `crm.public.customer.id` 不再绑定该 FROM 项。

设计必须明确选择一种处理策略：

1. 作用域感知地同步重写所有绑定该 FROM 项的限定列引用；
2. 对无法安全重写的情况报 `UNSUPPORTED_STATEMENT`；
3. 使用其他不会隐藏原关系限定名的注入方式。

仅用表名最后一段作为别名不能保持语义。

### 1.5 替换后不能继续遍历新构造的派生表

注入结果内部仍含原基表：

```sql
SELECT * FROM customer WHERE ...
```

如果 visitor 继续进入刚构造的节点，会反复包装同一表直至栈溢出。

必须规定：

- 只遍历输入树原有节点；
- 命中基表后构造替代节点并立即返回；
- 当前遍历不进入新构造的过滤派生表；
- 最好同时保证重复调用 rewriter 不会再次包装。

### 1.6 写入语句只能改写 source query

`INSERT` 和 CTAS 的目标表绝不能进入行过滤遍历。否则目标表若也在元数据中配置了 `rowFilter`，可能被误当成读取关系。

实现边界应固定为：

```text
识别写语句
→ 处理既有 pass-through 情况
→ 提取 sourceQuery
→ 仅将 sourceQuery 传给 RowFilterRewriter
→ 校验、血缘和脱敏
→ 用 composer 与原 target 重新组合
```

`RowFilterRewriter` 不应接收完整 DML/DDL 节点。

参考：

- `src/main/java/io/sqlmask/rewrite/RewriteEngine.java:81-105`
- `src/main/java/io/sqlmask/dialect/PostgresqlDialectAdapter.java:197-225`

## 2. 高优先级问题

### 2.1 `originalSql` 必须保留真实输入

设计提出在发生行过滤时，把替换后的 SQL 存入 `originalSql`。这会破坏 API/UI 契约。

row-filter-only 场景可能变成：

```text
originalSql  = 过滤后的 SQL
rewrittenSql = 过滤后的 SQL
unchanged()  = true
```

后果包括：

- 真正的用户输入丢失；
- 明明发生改写却被报告为 unchanged；
- 页面可能隐藏原始语句；
- 审计与调用方对比失真。

应保持：

```text
originalSql  = 用户输入或当前既有定义下的原始 snapshot
rewrittenSql = 最终输出
unchanged()  = originalSql.equals(rewrittenSql)
masked       = 是否应用列脱敏
rowFiltered  = 是否至少注入一次行过滤
```

内部如需保存已注入过滤、但尚未脱敏的 SQL，应使用局部变量或新增 `filteredSql` / `validationInputSql`，不能复用公开字段。

参考：

- `src/main/java/io/sqlmask/rewrite/RewriteEngine.java:28-39`
- `src/main/java/io/sqlmask/sql/ValidatedSql.java:9-24`
- `README.md:52-60`
- `src/main/resources/static/index.html:619-623`

### 2.2 Calcite `SqlNode.clone()` 不能当作真正深拷贝

设计要求每次注入使用谓词深拷贝是正确的，但 Calcite 1.42 的 `SqlCall.clone(pos)` 可能复用内部 operands，仅复制根节点不足以隔离 validator 的原地修改。

应选择：

- 实现递归 AST copier，覆盖 `SqlCall`、`SqlNodeList`、`SqlIdentifier`、literal 等节点；或
- 缓存规范化谓词文本，每次注入重新 parse。

Registry 还应遵循：

```text
parsedTemplate  = 未经 validator 修改的模板
validationCopy  = parsedTemplate 的真正深拷贝
用 validationCopy 做配置校验
缓存 parsedTemplate
每次注入再深拷贝 parsedTemplate
```

不能缓存已经参与校验的谓词节点。

另外，`CteExpander` 当前可能在多次 CTE 引用时复用同一个 `cte.body()`；每次展开也应深拷贝完整查询树，避免形成共享 DAG。

参考：

- `pom.xml:19-20`
- `pom.xml:43-51`
- `src/main/java/io/sqlmask/sql/CteExpander.java:59-60`
- `src/main/java/io/sqlmask/sql/CteExpander.java:215-224`

### 2.3 查询遍历必须覆盖所有表达式中的子查询

组件描述主要强调处理 SELECT 的 FROM 和 JOIN 两侧，但支持范围还承诺 WHERE、SELECT、HAVING 等位置的子查询。例如：

```sql
SELECT a.id
FROM other a
JOIN other b
  ON EXISTS (
    SELECT 1 FROM customer c WHERE c.id = a.id
  );
```

若只遍历 JOIN 左右输入，JOIN condition 中的 `customer` 会漏过滤。

建议拆分为：

- `rewriteQuery`：处理 SELECT、WITH、集合操作、ORDER BY wrapper；
- `rewriteExpression`：递归发现表达式中的子查询；
- `rewriteFromItem`：只处理具有关系绑定语义的 FROM 节点。

至少覆盖：

- SELECT list；
- WHERE；
- HAVING；
- GROUP BY；
- ORDER BY；
- JOIN ON；
- OFFSET/FETCH；
- window 定义；
- 集合操作 operands。

### 2.4 `AS` 节点必须保留完整 operand

以下语法中的 `AS` 不只有 source 和 alias：

```sql
FROM crm.public.customer AS c(customer_id, customer_status)
```

替换时如果只保存 alias，会丢失列别名列表；如果先给新派生表加 alias，再保留原 `AS`，还可能产生双重 `AS`。

应先解构原 `AS`：

- operand 0：原表；
- operand 1：关系别名；
- operand 2 以后：列别名列表。

只替换 operand 0，并原样保留其余 operands、parser position 与显式别名结构。

参考：

- `src/main/java/io/sqlmask/sql/CteExpander.java:168-181`
- `src/main/java/io/sqlmask/sql/CteExpander.java:210-224`

### 2.5 “只允许本表列与常量”不能只靠 validator

当前 validator 会通过 `UnknownFunctionTable` 宽松接受未知标量函数。因此以下条件可能通过：

```yaml
rowFilter: "is_allowed(active)"
rowFilter: "random() < 0.5"
rowFilter: "current_user = owner"
rowFilter: "current_timestamp < expires_at"
```

它们依赖 UDF、随机数、用户、会话或时间，与“静态、无状态、确定性”目标冲突。

应对谓词 AST 实施白名单，明确：

允许：

- 当前表的列引用；
- 字面量；
- 布尔、比较和必要的算术运算；
- `IS NULL`；
- `IS [NOT] DISTINCT FROM`；
- 经明确审查的纯函数白名单（如确有需要）。

禁止：

- 子查询；
- 动态参数；
- 任意未知函数与 UDF；
- 用户或会话函数；
- 时间和随机函数；
- 聚合与窗口函数；
- 序列访问。

若不准备实施白名单，应收窄文档承诺，不能继续声称谓词只含本表列与常量且执行语义确定。

参考：

- `src/main/java/io/sqlmask/dialect/UnknownFunctionTable.java:23-41`
- `src/main/java/io/sqlmask/dialect/UnknownFunctionTable.java:60-83`
- `src/main/java/io/sqlmask/sql/SqlValidatorFactory.java:46-56`

### 2.6 根级集合操作与现有支持范围冲突

设计承诺支持 `UNION`、`INTERSECT`、`EXCEPT` 各分支，但当前顶层分类器与 `isQuery()` 不接受根级集合操作，语句会在 rewriter 运行前被拒绝。

如纳入 MVP，需要同步修改：

- `classify()`；
- `isQuery()`；
- 写入 source query 提取；
- `ORDER BY(setop)`；
- CTE 展开、血缘和脱敏包装对 setop 输出列的处理。

若不准备修改，应从 MVP 中删除根级集合操作承诺，明确仅支持现有 parser 已接受的形态。

参考：

- `src/main/java/io/sqlmask/dialect/PostgresqlDialectAdapter.java:63-98`
- `tpcds/EXPECTED.md:38-40`
- `tpcds/EXPECTED.md:164-168`

### 2.7 两段表名不是已验证的既有语义

设计声称 `schema.table` 会按声明的 `catalog.schema` 组合解析，但当前 schema tree 和 schema path 未证明这一行为成立。即使自定义 matcher 找到声明表，过滤派生表内部若仍保留原来的两段 identifier，后续 validator 仍可能失败。

应先通过当前 adapter 验证一、二、三段名的真实解析行为。若支持两段名，考虑：

- 将命中后的内层表引用规范化为声明表完整三段名；或
- 扩展 catalog reader；
- 确保 quoted identifier 和大小写语义不被破坏。

参考：

- `src/main/java/io/sqlmask/metadata/YamlCalciteSchemaFactory.java:27-34`
- `src/main/java/io/sqlmask/sql/SqlValidatorFactory.java:86-96`
- `src/test/java/io/sqlmask/dialect/PostgresqlDialectAdapterTest.java:90-101`

### 2.8 Registry 错误必须显式映射为 `CONFIG_ERROR`

现有 adapter 的 parse/validate 错误分别为 `PARSE_ERROR` 和 `VALIDATION_ERROR`；它们不会自然变成 `CONFIG_ERROR`。

Registry 构建边界应捕获解析、校验及 AST 规则检查异常，并统一包装为：

```text
CONFIG_ERROR: table '<catalog.schema.table>': row filter ...
```

同时保留 cause。已知但不支持的查询结构应显式产生 `UNSUPPORTED_STATEMENT`，不能依赖 `IllegalArgumentException` 或 `UnsupportedOperationException`，否则 API 可能返回 `INTERNAL_ERROR`。

参考：

- `src/main/java/io/sqlmask/dialect/PostgresqlDialectAdapter.java:52-60`
- `src/main/java/io/sqlmask/dialect/PostgresqlDialectAdapter.java:107-133`
- `src/main/java/io/sqlmask/server/ApiExceptionHandler.java:31-35`

## 3. 中优先级问题

### 3.1 YAML 示例按当前 loader 无法直接加载

设计示例省略了根级 `policies`，而当前 loader 要求其为 mapping。需要二选一：

- 示例补充 `policies: {}` 并明确即使只有 row filter 也必填；
- 或修改 loader，把缺失 `policies` 视为空映射。

参考：

- `src/main/java/io/sqlmask/config/YamlConfigLoader.java:138-141`

### 3.2 应使用独立 `TableKey`

给 `TableMetadata` 增加 `rowFilter` 会改变公开 record 构造器，需列出 loader、helper、测试和潜在 Java API 的迁移范围。

另外，不应直接复用当前 `TableMetadata.tableKey()`：它通过空 column 构造 `ColumnKey`，而 `ColumnKey.normalize()` 禁止空值，可能抛 `IllegalArgumentException`。

建议新增三字段 `TableKey(catalog, schema, table)`，统一管理：

- 规范化；
- equals/hashCode；
- 显示名称；
- quoted identifier 与大小写策略。

参考：

- `src/main/java/io/sqlmask/metadata/TableMetadata.java:18-28`
- `src/main/java/io/sqlmask/metadata/ColumnKey.java:17-30`

### 3.3 row-filter-only 写入会扩大 composer 使用范围

现有无脱敏命中的写语句可直接返回原文；加入行过滤后，即使 `masked=false`，也必须调用 composer 重建写语句。

因此需回归：

- INSERT target column-list；
- CTAS output column-list；
- `IF NOT EXISTS`；
- 已支持的 CREATE/INSERT 修饰；
- 已明确 unsupported 的写语句形态；
- 方言节点不会被静默丢失。

参考：

- `src/main/java/io/sqlmask/rewrite/RewriteEngine.java:97-101`
- `src/main/java/io/sqlmask/dialect/PostgresqlDialectAdapter.java:197-225`

### 3.4 前端和 API 需要完整 round-trip

除增加输入框外，还需同步：

- 初始和样例状态；
- 新表默认对象；
- YAML 生成；
- YAML/API 导入；
- `ConfigController.TableDto`；
- rewrite 响应 DTO；
- `rowFiltered` badge；
- 空白 `rowFilter` 的往返语义。

参考：

- `src/main/resources/static/index.html:238-260`
- `src/main/resources/static/index.html:296-317`
- `src/main/resources/static/index.html:382-408`
- `src/main/resources/static/index.html:539-550`
- `src/main/resources/static/index.html:579-624`
- `src/main/java/io/sqlmask/server/ConfigController.java:35-60`

### 3.5 字节级兼容需要真正的 golden test

设计承诺未配置任何 `rowFilter` 时输出逐字节不变，但现有部分测试会归一化空白，无法发现换行、空格、引号和分号变化。

应增加不做任何 normalization 的 golden 对比，覆盖：

- 普通 SELECT；
- WITH；
- 已脱敏 SELECT；
- INSERT；
- CTAS；
- 未命中列策略的直通语句；
- TPC-DS 预期输出。

参考：

- `src/test/java/io/sqlmask/integration/SqlMaskIntegrationTest.java:28-30`
- `src/test/java/io/sqlmask/rewrite/SqlRewriteServiceTest.java:52-54`

## 4. 建议写入设计的安全不变量

1. **严格名称解析**：短名多候选时显式失败，不依赖搜索路径顺序。
2. **CTE 优先**：可见 CTE 永远遮蔽声明基表，内层作用域遮蔽外层。
3. **只处理读源**：INSERT/CTAS target 永不进入 rewriter。
4. **一次注入**：新构造的过滤派生表不参与当前遍历。
5. **真正深拷贝**：缓存未校验模板；校验、注入和 CTE 展开分别使用独立 AST。
6. **fail-closed**：无法证明可安全改写的 FROM 形态不得静默跳过。
7. **绑定保持**：替换前后的列引用必须绑定到同一个逻辑 FROM 项。
8. **确定性谓词**：拒绝动态参数、会话、时间、随机、UDF 和非白名单函数。
9. **输入保留**：`originalSql` 始终保留真实输入语义。
10. **解析一致**：rewriter 的表解析结果必须与 validator 的最终绑定一致。

## 5. 必须补充的测试

### 名称与作用域

- CTE 与受过滤基表同名；
- 嵌套 CTE 同名及内层遮蔽；
- 一段表名跨 catalog/schema 多命中；
- 调换 YAML 声明顺序后仍报相同歧义；
- 两段 `schema.table`；
- quoted identifier 和大小写敏感名称。

### 绑定与 AST

- 无别名三段表配完整限定列引用；
- 普通 alias 不产生双重 `AS`；
- `AS c(x, y)` 保留列别名列表；
- 单个原表只包装一层；
- 重复调用 rewriter 不重复包装；
- 自连接两边各有独立谓词树；
- 同一 CTE 多次引用时 body 与谓词均不共享可变节点。

### 遍历覆盖

- SELECT list、WHERE、HAVING 中的子查询；
- JOIN ON 中的 `EXISTS` / `IN` 子查询；
- GROUP BY、ORDER BY、OFFSET/FETCH 和 window 表达式中的子查询；
- 根级及嵌套 `UNION`、`INTERSECT`、`EXCEPT`；
- 禁止 FROM 包装器命中受控表时 fail-closed。

### 写入

- 目标表有 `rowFilter`、源表无；
- 目标与源为同名不同 schema；
- CTAS 名与已声明受控表同名；
- source 是集合操作；
- `INSERT ... VALUES` 保持既有直通或失败行为；
- row-filter-only、mask-only、二者叠加；
- target/output column-list 与 `IF NOT EXISTS` 保留。

### 配置与结果契约

- 未知 UDF、随机、当前用户、当前时间和动态参数被拒绝；
- 所有 rowFilter 配置错误统一为 `CONFIG_ERROR`；
- 只有 row filter、没有 policies 的完整 YAML；
- row-filter-only 时：

  ```text
  originalSql  = 原输入
  rewrittenSql = 含过滤的输出
  rowFiltered  = true
  masked       = false
  unchanged()  = false
  ```

- 无任何 rowFilter 时做字节级输出回归。

## 6. 推荐实施顺序

1. 定义统一、严格的表名解析和 CTE scope 规则；
2. 修复 `CteExpander` 的嵌套作用域、递归键和多引用 AST 共享；
3. 决定全限定列引用的绑定保持方案；
4. 设计 query/expression/from-item 三类遍历边界；
5. 实现一次注入和真正深拷贝；
6. 明确根级集合操作是否进入 MVP；
7. 收紧 rowFilter AST 白名单并统一配置错误映射；
8. 保持 `originalSql` 契约，增加 `rowFiltered` 独立状态；
9. 接入 INSERT/CTAS source 与 composer 回归；
10. 接入配置、API、页面和字节级回归测试。

## 7. 最终判断

核心的“校验前派生表注入”路线可以保留。设计修订的重点不是更换总体架构，而是补齐以下边界：

- 名称解析必须严格且与 validator 一致；
- CTE 作用域必须正确；
- 未知语法必须 fail-closed；
- 替换必须保持列绑定；
- 新节点不能重复遍历；
- 所有可变 AST 必须真正隔离；
- API 必须保留原始输入；
- 谓词约束必须与“静态、确定性”目标一致。

完成这些修订并补齐安全回归测试后，该方案才适合作为直接编码的实施规格。

## 8. 代码级核实记录（第二轮复核，2026-09-06）

上述主要结论已逐条对照源码与 Calcite 1.42 字节码独立复核，全部成立。关键证据：

| 结论 | 核实证据 |
| --- | --- |
| 1.1 CteExpander 作用域查找外层优先 | `CteExpander.java:275` 用 `scopes.descendingIterator()`；`ArrayDeque.push` = `addFirst`，`iterator()` 才是内层优先，`descendingIterator()` 实际**外层优先**，与方法 Javadoc"innermost first"相反。嵌套同名 CTE 会静默绑定到外层；`inProgress`（`:91`）按裸名记录，合法的内层同名 CTE 被误判为递归直接抛错 |
| 1.1 CTE body 多引用共享节点 | `CteExpander.java:217`：`derivedTable` 直接复用 `cte.body()`，同一 CTE 被 N 处引用时 N 个派生表共享同一棵树 |
| 1.2 多候选短名 fail-open | 字节码核实：`SqlValidatorUtil.getTableEntry` 遍历 `getSchemaPaths()`，`if (entry != null) return entry;`——**首个路径命中即返回，不做跨路径歧义检测**；`SqlValidatorFactory.java:86-98` 的 `MultiSchemaPathCatalogReader` 按声明顺序提供全部 `catalog.schema` 路径。另 `tpcds/EXPECTED.md:91,98` 已记载 Calcite 1.42 对歧义引用静默解析（E4 已知偏差），README 中"命中多个时报歧义错误"并无实现支撑 |
| 2.1 UI 会隐藏原始语句 | `index.html:619-623`：`unchanged` 为 true 时不渲染「查看原始语句」折叠块。按设计稿 §5.3，row-filter-only 语句 `originalSql == rewrittenSql` → `unchanged()==true` → 原始输入在页面上不可见，API 侧亦丢失真实输入 |
| 2.2 `SqlBasicCall.clone` 不深拷贝 | 字节码核实（calcite-core-1.42.0）：`SqlBasicCall.clone(SqlParserPos)` 实现为 `getOperator().createCall(quantifier, pos, operandList)`——**原样传入自身 operandList，子节点全部共享**。设计稿 §5.2 的"深拷贝"若用 `clone()` 实现则完全无效，必须递归复制或每次注入重新 parse |
| 2.5 未知函数放行 | `SqlValidatorFactory.java:48-53` 将 `UnknownFunctionTable` 挂在算子链末位；`UnknownFunctionTable.java:60-72` 对任意未知函数名生成为任意参数的透明标量函数。`random()`/`current_*`/自定义 UDF 均可通过校验，设计稿 §2.3"只允许本表列与常量"仅靠 validator 无法成立 |
| 2.6 根级集合操作被拒 | `PostgresqlDialectAdapter.java:63-98`：`classify()` 对根级 `UNION/INTERSECT/EXCEPT` 走 `default -> unsupported`，`isQuery()` 只认 `SELECT/WITH/ORDER_BY`；`tpcds/EXPECTED.md:39-40` 实测"根级 INTERSECT/EXCEPT 非零退出"。设计稿 §3.1 的"UNION 各分支"承诺超出当前可解析范围 |
| 2.7 两段名解析无既有语义 | README 仅记载一段名解析（README.md:174-175）；`schemaPaths`（`PostgresqlDialectAdapter.java:281-290`）只生成 `[catalog, schema]` 路径，`schema.table` 形式落到根级查找，与声明表的对应关系未经任何测试证明。设计稿 §5.2 将其表述为"与既有语义一致"不成立 |
| 2.8 异常映射 | `PostgresqlDialectAdapter.java:118-123` 将校验异常统一包为 `VALIDATION_ERROR`；`ApiExceptionHandler.java:31-35` 将未识别 `RuntimeException` 映射为 `INTERNAL_ERROR`。Registry 若不显式捕获包装，配置错误会以错误码漏出 |
| 3.1 `policies` 必填 | `YamlConfigLoader.java:138-141`：`requireMapping(policiesNode, ...)`，缺失即 `CONFIG_ERROR`。设计稿 §6 YAML 示例按现状无法加载 |
| 3.2 `tableKey()` 是死代码且有缺陷 | 全仓库无调用；`TableMetadata.java:26-28` 以空列名调 `ColumnKey.of`，而 `ColumnKey.normalize`（`ColumnKey.java:26-31`）对空白标识符直接抛 `IllegalArgumentException`——该方法一旦被复用必抛异常。新增 `TableKey` 的建议成立 |
| 3.5 既有测试归一化空白 | `SqlMaskIntegrationTest.java:28-30` 的 `flat()` 将连续空白折叠后比较，字节级回归承诺需要新的 golden 测试支撑 |

另补充一点评审稿未展开的佐证：`LineageAnalyzerTest.java:140-147`（`cteShadowsTableName`）证明当前管线中 CTE 遮蔽基表的行为**由 validator 保障**；而行过滤替换发生在 validator 之前，rewriter 若不自行实现等价的 CTE 作用域解析，恰好绕过这道既有保障——这正是 1.1 被列为阻塞问题的原因。

## 9. 复核结论

维持第 7 节判断，且置信度升级：第 1 节四项阻塞问题（1.1、1.2、1.4、1.5）与高优先级 2.1、2.2、2.5、2.6 均有源码/字节码级实证，不是推测。设计稿应先按第 6 节顺序修订（重点是：严格名称解析 + CTE 作用域、fail-closed 白名单、真正的深拷贝与 `originalSql` 契约），再进入实现。
