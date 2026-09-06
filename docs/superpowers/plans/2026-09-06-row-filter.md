# 行过滤（Row Filter）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有脱敏改写服务上实现行级过滤：YAML 表声明可配置静态过滤条件，改写时注入为派生表谓词，覆盖所有基表引用位置并与列脱敏叠加。

**Architecture:** 校验前的 SqlNode 表引用替换（`(SELECT * FROM t WHERE cond) AS alias`），registry 缓存白名单校验后的条件模板；rewriter 不可变改写 + CTE 词法作用域 + fail-closed FROM 白名单；引擎接线保持 `originalSql` 真实输入语义并新增 `rowFiltered` 标记。

**Tech Stack:** Java 17+ / Apache Calcite 1.42 / Spring Boot / JUnit 5 / Maven。

**Spec:** `docs/superpowers/specs/2026-09-06-row-filter-design.md`（v2，含十项安全不变量 §5.4）。测试用例清单：`docs/superpowers/specs/2026-09-06-row-filter-test-design.md`（A–H 组）。

## 工作区现状（重要）

工作区已有**未提交**的 WIP，本计划从它继续，不要重写已存在的文件：

- 已完成且**全绿**：CteExpander 作用域修复（`sql/CteExpander.java` + `CteExpanderTest`）、`SqlNodeCopier`、`TableMetadata.rowFilter` 字段、`YamlConfigLoader` rowFilter 加载与测试、`rowfilter/RowFilterRegistry` 及其测试（C 组）、`rowfilter/RowFilterRewriter` 主体与大部分测试（D 组）；
- **11 个失败测试**集中在 `RowFilterRewriterTest`（Task 1–7 逐一修复，根因已定位）；
- **未开始**：引擎接线（Task 8）、API/DTO（Task 9）、前端（Task 10）、CLI 测试（Task 11）、golden 回归与收尾（Task 12）。

开始前先跑一次基线：`mvn -q test` 应只有 `RowFilterRewriterTest` 的 11 个失败，其余全绿。若不是，停下来报告。

## Global Constraints

- 错误码只用既有枚举（`SqlMaskException.Code`）：CONFIG_ERROR / PARSE_ERROR / VALIDATION_ERROR / UNSUPPORTED_STATEMENT / LINEAGE_UNKNOWN / REWRITE_ERROR / IO_ERROR；已知不支持结构**禁止**让 `RuntimeException` 逃逸成 INTERNAL_ERROR；
- 行级安全 fail-closed：无法证明可安全改写就显式失败，宁误拒不放过；
- rewriter 不可变改写：输入树不修改，未变化子树按引用返回；空 registry 恒等返回、零遍历；
- `originalSql` 始终保留真实输入语义（读语句 = 替换前快照，写语句 = 用户语句文本）；
- 标识符匹配一律**逐段精确比较**（解析器已把未加引号标识符折叠为小写、quoted 保留原拼写）；禁止 equalsIgnoreCase；
- 不新增 Maven 依赖、不改 `pom.xml`、不新增命令行参数、CLI stdout 格式不变；
- 代码风格与现有一致：final 类、2 空格缩进、Javadoc 说明"为什么"；测试 JUnit 5；
- 每个任务结束：`mvn -q test` 全绿后按给定信息 commit（中文 conventional commit，与仓库历史一致）；
- commit 只 add 本任务列出的文件——工作区还有其他人未提交的 WIP 文件，**禁止** `git add -A`。

---

### Task 1: 修复 SqlWithItem 重建（copy-on-write 按 operand 位置）

三个红测试同根因：`rewriteWith` 手工传参 `item.name, item.columnList, item.recursive, newQuery` 与 `SqlWithItem.getOperandList()` 顺序不符，`SqlSelect` 落到期望 `SqlLiteral` 的槽位抛 `ClassCastException`（`forwardCteReferenceStillFailsDownstream`、`cteBodyIsFilteredButItsReferencesAreNot`），并导致嵌套 WITH 改写产物损坏（`nestedWithShadowsOuterCteName` 校验报 `Column 'id' not found`）。

**Files:**
- Modify: `src/main/java/io/sqlmask/rowfilter/RowFilterRewriter.java`（`rewriteWith`，约 120–148 行）
- Test: `src/test/java/io/sqlmask/rowfilter/RowFilterRewriterTest.java`（已有红测试，不改断言）

**Interfaces:**
- Consumes: `SqlWithItem.getOperandList()`、`getOperator().createCall(...)`（与 `CteExpander.rewriteCall` 相同的 copy-on-write 手法）
- Produces: `rewriteWith(SqlWith, Context, Deque<Set<String>>)` 签名暂不变（Task 3 会把 `Deque<Set<String>>` 换成 `Deque<Scope>`）

- [ ] **Step 1: 确认红测试与根因**

Run: `mvn -q test -Dtest=RowFilterRewriterTest`
Expected: FAIL，其中 `forwardCteReferenceStillFailsDownstream` 与 `cteBodyIsFilteredButItsReferencesAreNot` 为 `ClassCastException: SqlSelect cannot be cast to SqlLiteral`。

- [ ] **Step 2: 重写 `rewriteWith`，按 operand 位置替换**

把现有 `rewriteWith`（含 `newItems`/`item.getOperator().createCall(item.getFunctionQuantifier(), ..., item.name, item.columnList, item.recursive, newQuery)` 手工参数）整体替换为：

```java
  private SqlNode rewriteWith(SqlWith with, Context context, Deque<Set<String>> cteScopes) {
    Set<String> scope = new HashSet<>();
    cteScopes.push(scope);
    try {
      SqlNodeList newItems = rewriteWithItems(with.withList, context, cteScopes, scope);
      SqlNode newBody = rewriteQuery(with.body, context, cteScopes);
      if (newItems == with.withList && newBody == with.body) {
        return with;
      }
      return with.getOperator().createCall(with.getFunctionQuantifier(),
          with.getParserPosition(), newItems, newBody);
    } finally {
      cteScopes.pop();
    }
  }

  /**
   * Rewrites WITH items in order; each item's name joins the visible scope
   * before its own body is rewritten (self-references therefore bind the CTE
   * here and fail downstream exactly as they do today). Items are rebuilt by
   * operand position — never by hand-written argument lists, which must match
   * getOperandList() order exactly or the tree is silently corrupted.
   */
  private SqlNodeList rewriteWithItems(SqlNodeList items, Context context,
      Deque<Set<String>> cteScopes, Set<String> scope) {
    SqlNode[] rewritten = new SqlNode[items.size()];
    boolean changed = false;
    for (int i = 0; i < items.size(); i++) {
      SqlWithItem item = (SqlWithItem) items.get(i);
      scope.add(item.name.getSimple());
      SqlNode newItem = rewriteWithItem(item, context, cteScopes);
      rewritten[i] = newItem;
      changed |= newItem != items.get(i);
    }
    return changed
        ? new SqlNodeList(java.util.Arrays.asList(rewritten), items.getParserPosition())
        : items;
  }

  private SqlNode rewriteWithItem(SqlWithItem item, Context context, Deque<Set<String>> cteScopes) {
    SqlNode newQuery = rewriteQuery(item.query, context, cteScopes);
    if (newQuery == item.query) {
      return item;
    }
    List<SqlNode> operands = item.getOperandList();
    SqlNode[] rebuilt = operands.stream()
        .map(op -> op == item.query ? newQuery : op)
        .toArray(SqlNode[]::new);
    return item.getOperator().createCall(
        item.getFunctionQuantifier(), item.getParserPosition(), rebuilt);
  }
```

- [ ] **Step 3: 跑测试**

Run: `mvn -q test -Dtest=RowFilterRewriterTest`
Expected: `forwardCteReferenceStillFailsDownstream`、`cteBodyIsFilteredButItsReferencesAreNot`、`nestedWithShadowsOuterCteName` 由异常/失败变为**新的失败形态**（forward 那条会因 Task 3 的语义决策而调整——本步只要求不再抛 `ClassCastException`，允许它以"期望异常未抛出"失败）；其余保持现状。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/sqlmask/rowfilter/RowFilterRewriter.java
git commit -m "fix: 行过滤 rewriter 的 WITH item 按 operand 位置重建，修复树损坏"
```

---

### Task 2: 标识符精确匹配 + quoted 大小写用例修复夹具

三个红测试（`quotedCaseSensitiveNameDoesNotMatchLowercaseDeclaration`、`quotedLowercaseNameMatchesDeclaration`、`unquotedFoldsToLowercaseBeforeMatching`）目前死在夹具上：`QUOTED_YAML` 同时声明 `crm.public."Customer"` 与 `crm.public.customer`，而 loader 按折叠后键判重复（README 明确该行为），抛 `duplicate table 'crm.public.Customer'`。同时 `nameMatches` 的 `equalsIgnoreCase` 违反精确匹配约束（quoted `"Customer"` 会误配声明的 `customer`）。

**Files:**
- Modify: `src/main/java/io/sqlmask/rowfilter/RowFilterRewriter.java`（`nameMatches`，约 309–311 行）
- Test: `src/test/java/io/sqlmask/rowfilter/RowFilterRewriterTest.java`（改夹具引用 + 新增 1 个用例）

**Interfaces:**
- Consumes: 解析器大小写语义（未加引号折叠小写、quoted 原样）
- Produces: `nameMatches(String reference, String declared)` 严格相等；Task 7 的 fail-closed 扫描沿用同一匹配口径

- [ ] **Step 1: 修改测试夹具**

删除 `QUOTED_YAML` 常量（若存在），把三个 quoted 用例改为：

```java
  @Test
  void quotedCaseSensitiveNameDoesNotMatchLowercaseDeclaration() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f, "SELECT id FROM \"Customer\"");
    assertEquals(0, result.injections(),
        "\"Customer\" is a different table from customer; it stays unfiltered "
            "and validation rejects it as undeclared");
    SqlMaskException e = assertThrows(SqlMaskException.class,
        () -> adapter.validate(result.node(), f.schema()));
    assertEquals(SqlMaskException.Code.VALIDATION_ERROR, e.getCode());
  }

  @Test
  void quotedLowercaseNameMatchesDeclaration() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f, "SELECT id FROM \"customer\"");
    assertEquals(1, result.injections());
    adapter.validate(result.node(), f.schema());
  }

  @Test
  void unquotedFoldsToLowercaseBeforeMatching() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result = apply(f, "SELECT id FROM Customer");
    assertEquals(1, result.injections(),
        "unquoted Customer folds to customer and hits the filtered table");
    adapter.validate(result.node(), f.schema());
  }

  @Test
  void quotedDeclaredTableWithoutFilterIsUntouched() {
    // REW-8：精确匹配带引号的声明（该表未配过滤）→ 零改动
    Fixture f = fixture("""
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: "Customer"
              columns:
                - {name: id, type: bigint}
        policies: {}
        """);
    RowFilterRewriter.Result result = apply(f, "SELECT id FROM \"Customer\"");
    assertEquals(0, result.injections());
    adapter.validate(result.node(), f.schema());
  }
```

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -q test -Dtest=RowFilterRewriterTest#quotedCaseSensitiveNameDoesNotMatchLowercaseDeclaration+quotedLowercaseNameMatchesDeclaration+unquotedFoldsToLowercaseBeforeMatching+quotedDeclaredTableWithoutFilterIsUntouched`
Expected: quotedCaseSensitive 红（现 `equalsIgnoreCase` 误注入 → `assertEquals(0, ...)` 失败）；其余可能绿。

- [ ] **Step 3: 改 `nameMatches` 为严格相等**

```java
  /**
   * Strict per-segment match: the parser already folds unquoted identifiers
   * to lower case and keeps quoted ones verbatim, so exact equality against
   * the declared spelling is the whole contract. equalsIgnoreCase would make
   * a quoted "Customer" match a declared customer — binding the rewriter to
   * a table the validator will never resolve.
   */
  private static boolean nameMatches(String reference, String declared) {
    return reference.equals(declared);
  }
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=RowFilterRewriterTest`
Expected: 上述 4 个用例全绿；`injectsIntoUniqueUnqualifiedReference` 等既有用例不回归。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/sqlmask/rowfilter/RowFilterRewriter.java src/test/java/io/sqlmask/rowfilter/RowFilterRewriterTest.java
git commit -m "fix: 行过滤表名逐段精确匹配，quoted 大小写用例改用单表夹具"
```

---

### Task 3: CTE 作用域三分模型 + 后位同名兄弟守卫

现模型只有"可见/不可见"。安全分析（spec §5.4 #10）：item 体内引用**同 WITH 里尚未完成的后位兄弟名**时，PostgreSQL（目标引擎）绑定基表，而 Calcite 管线（展开器+校验器的位置作用域）可能绑定 CTE——两种引擎绑定不同。若注入：谓词可能作用在 CTE 输出上（列不存在→报错，或错绑）；若不注入：PostgreSQL 读**未过滤**的基表 → fail-open。唯一安全动作是显式拒绝。另外把 `forwardCteReferenceStillFailsDownstream`（对已声明过滤表的同名兄弟，期望异常的语义已过时）替换为两个明确用例（REW-11）。

**Files:**
- Modify: `src/main/java/io/sqlmask/rowfilter/RowFilterRewriter.java`（Scope 模型、`bindingOf`、`rewriteTableReference` 一段名分支）
- Test: `src/test/java/io/sqlmask/rowfilter/RowFilterRewriterTest.java`（替换 1 个用例 + 新增 2 个）

**Interfaces:**
- Produces: `private enum Binding { CTE, PENDING_SIBLING, NONE }`；`bindingOf(String, Deque<Scope>)`；Scope 内部类（本任务引入，Task 7 的 fail-closed 扫描复用 `Context.loaded`）

- [ ] **Step 1: 替换/新增测试（先写失败测试）**

删除 `forwardCteReferenceStillFailsDownstream`，新增：

```java
  @Test
  void forwardReferenceToUndeclaredNameKeepsCurrentFailure() {
    // REW-11：前向引用未声明名 → 与现状一致失败，行过滤不得改变它
    Fixture f = fixture(THREE_TABLE_YAML);
    assertThrows(SqlMaskException.class, () -> {
      RowFilterRewriter.Result result = apply(f,
          "WITH a AS (SELECT id FROM b), b AS (SELECT id FROM crm.public.other) SELECT * FROM a");
      adapter.validate(result.node(), f.schema());
    });
  }

  @Test
  void laterSiblingSharingAFilteredTableNameIsRejected() {
    // 同 WITH 内，靠前的 item 体引用靠后的同名兄弟；该名字同时是声明过的
    // 受过滤基表。PostgreSQL 绑定基表（该引用处 CTE 不可见），Calcite 管线
    // 的位置作用域可能绑定 CTE——注入或不注入都可能错绑/漏过滤，拒绝。
    Fixture f = fixture(THREE_TABLE_YAML);
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> apply(f,
        "WITH a AS (SELECT id FROM customer), customer AS (SELECT id FROM crm.public.other) "
            + "SELECT * FROM a"));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode(), () -> e.getMessage());
  }
```

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -q test -Dtest=RowFilterRewriterTest#laterSiblingSharingAFilteredTableNameIsRejected+forwardReferenceToUndeclaredNameKeepsCurrentFailure`
Expected: `laterSibling...` 红（当前模型把它当不可见→注入）；forward 那条绿（Task 1 后 b 未声明不注入、校验失败）。

- [ ] **Step 3: 实现 Scope 三分模型**

`RowFilterRewriter` 中：把 `Deque<Set<String>>` 全部替换为 `Deque<Scope>`，新增：

```java
  /**
   * One WITH clause's lexical scope. {@code all} lists every item the clause
   * declares; {@code completed} holds the items whose bodies are already
   * rewritten. A name that is in {@code all} but not yet {@code completed} is
   * a later sibling: PostgreSQL binds it to a base table, while the Calcite
   * pipeline may bind the CTE — a filtered-table name in that position can
   * neither be injected nor skipped safely, so the caller rejects it.
   */
  private static final class Scope {
    final Set<String> all = new HashSet<>();
    final Set<String> completed = new HashSet<>();
  }

  private enum Binding { CTE, PENDING_SIBLING, NONE }

  /** Innermost scope wins; the first scope declaring the name decides. */
  private static Binding bindingOf(String name, Deque<Scope> scopes) {
    for (Scope scope : scopes) {
      if (scope.all.contains(name)) {
        return scope.completed.contains(name) ? Binding.CTE : Binding.PENDING_SIBLING;
      }
    }
    return Binding.NONE;
  }
```

`apply` 里 `new ArrayDeque<>()` 的泛型改为 `new ArrayDeque<Scope>()`；`rewriteWith` 按 Task 1 结构改用 `Scope`（`scope.all` 预登记全部 item 名 → 逐 item 改写体 → 改写完 `scope.completed.add(name)`；主 query 改写时全部 completed）；`isVisibleCte` 删除，`rewriteTableReference` 的一段名分支改为：

```java
    if (names.size() == 1) {
      Binding binding = bindingOf(names.get(0), cteScopes);
      if (binding == Binding.CTE) {
        return reference;
      }
      if (binding == Binding.PENDING_SIBLING) {
        // the name is declared as a later sibling in this very WITH clause:
        // engines may disagree on what it binds to, so a filtered table in
        // that slot must fail instead of guessing
        for (TableMetadata table : context.loaded.tables()) {
          if (table.rowFilter() != null && nameMatches(names.get(0), table.name())) {
            throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
                "reference '" + names.get(0) + "' is both a later WITH item of the same clause "
                    + "and a row-filtered base table; engines may bind it differently, so the "
                    + "statement is rejected");
          }
        }
        return reference;
      }
      // ... 既有候选解析（0/1/多）不变
```

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=RowFilterRewriterTest`
Expected: 全绿（`cteNameShadowsFilteredBaseTable`、`nestedWithShadowsOuterCteName`、`cteBodyIsFilteredButItsReferencesAreNot` 必须仍绿——主 query 改写时兄弟全部 completed，不受影响）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/sqlmask/rowfilter/RowFilterRewriter.java src/test/java/io/sqlmask/rowfilter/RowFilterRewriterTest.java
git commit -m "feat: 行过滤 CTE 作用域三分模型，后位同名受过滤表显式拒绝"
```

---

### Task 4: 派生表/CTE 位置的集合操作注入

红测试 `injectsIntoNestedSetOperationBranches`：`FROM (SELECT ... UNION ALL SELECT ...) t` 的 FROM item 是 AS 包着的 UNION 调用，`rewriteFromItem` 白名单缺集合操作分支 → 默认透传 → 0 注入。

**Files:**
- Modify: `src/main/java/io/sqlmask/rowfilter/RowFilterRewriter.java`（`rewriteFromItem`）
- Test: 既有红测试，不改断言

**Interfaces:**
- Consumes: `rewriteQuery` 已能处理 UNION/INTERSECT/EXCEPT operands

- [ ] **Step 1: 跑测试确认红**

Run: `mvn -q test -Dtest=RowFilterRewriterTest#injectsIntoNestedSetOperationBranches`
Expected: FAIL（expected 2 but 0）。

- [ ] **Step 2: rewriteFromItem 增加集合操作委托**

在 `rewriteFromItem` 的 `case SELECT: case WITH:` 处扩展：

```java
      case SELECT:
      case WITH:
      case UNION:
      case INTERSECT:
      case EXCEPT:
        // set operations are query nodes: a derived table may be a UNION of
        // SELECTs and each branch must be reached
        return rewriteQuery(from, context, cteScopes);
```

- [ ] **Step 3: 跑测试**

Run: `mvn -q test -Dtest=RowFilterRewriterTest`
Expected: `injectsIntoNestedSetOperationBranches` 绿；其余不回归。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/sqlmask/rowfilter/RowFilterRewriter.java
git commit -m "fix: 行过滤覆盖派生表位置的集合操作分支"
```

---

### Task 5: `AS c(id, phone)` 列别名断言修正

红测试 `keepsColumnAliasList`：功能已实现（只替换 operand 0、列别名保留），失败是断言对 unparser 空格敏感（Calcite 渲染列别名括号内侧有空格，`flat()` 折叠后仍可能多出空格）。

**Files:**
- Test: `src/test/java/io/sqlmask/rowfilter/RowFilterRewriterTest.java`（`keepsColumnAliasList` 断言）

- [ ] **Step 1: 改为空白不敏感 + 结构双断言**

```java
  @Test
  void keepsColumnAliasList() {
    Fixture f = fixture(TWO_TABLE_YAML);
    RowFilterRewriter.Result result =
        apply(f, "SELECT id FROM crm.public.customer AS c(id, phone)");
    assertEquals(1, result.injections());
    String rendered = flat(adapter.unparse(result.node()));
    // unparser 的列别名括号内侧留有空格，与关系别名之间也无空格约定；
    // 用去空格比较锁定结构：派生表 AS c 且列别名列表原样保留
    String squeezed = rendered.replace(" ", "");
    assertTrue(squeezed.contains("ASc(id,phone)"), () -> rendered);
    adapter.validate(result.node(), f.schema());
  }
```

- [ ] **Step 2: 跑测试**

Run: `mvn -q test -Dtest=RowFilterRewriterTest#keepsColumnAliasList`
Expected: PASS（校验通过本身就是列别名未丢的语义证明）。

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/sqlmask/rowfilter/RowFilterRewriterTest.java
git commit -m "test: 列别名断言对 unparser 空格不敏感"
```

---

### Task 6: 受过滤表三段全名列限定守卫（REW-24 / D5）

红测试 `rejectsFullyQualifiedColumnReferenceOfFilteredTable`：`SELECT crm.public.customer.id FROM crm.public.customer` 注入后派生表只暴露别名，绑定必破。显式拒绝。

**Files:**
- Modify: `src/main/java/io/sqlmask/rowfilter/RowFilterRewriter.java`（`Context` 加集合、`rewriteExpression` 收集、`apply` 末尾检查、`injectIfFiltered` 登记）
- Test: 既有红测试，不改断言

**Interfaces:**
- Consumes: `io.sqlmask.metadata.ColumnKey.normalize(part, kind)`（既有规范化）
- Produces: `Context.injectedKeys` / `Context.qualifiedColumnRefs`（`Set<String>`，规范化 `catalog.schema.table` 键，Task 8 引擎不需要感知）

- [ ] **Step 1: 跑测试确认红**

Run: `mvn -q test -Dtest=RowFilterRewriterTest#rejectsFullyQualifiedColumnReferenceOfFilteredTable`
Expected: FAIL（Expected exception not thrown）。

- [ ] **Step 2: 实现收集与检查**

`Context` 增加字段：

```java
    final java.util.Set<String> injectedKeys = new HashSet<>();
    final java.util.Set<String> qualifiedColumnRefs = new HashSet<>();
```

新增辅助（放在 `nameMatches` 旁）：

```java
  /** Normalized catalog.schema.table key, shared by injected tables and column qualifiers. */
  private static String qualifiedKey(List<String> parts) {
    return ColumnKey.normalize(parts.get(0), "catalog") + "."
        + ColumnKey.normalize(parts.get(1), "schema") + "."
        + ColumnKey.normalize(parts.get(2), "table");
  }
```

`rewriteExpression` 入口（null 判断之后）收集 4 段及以上的列限定引用（3 段列限定 `schema.table.column` 今日本就解析不了，交给校验器按现状报错，不在此扩行为）：

```java
    if (node instanceof SqlIdentifier id && id.names.size() >= 4) {
      // expression position: only column references can be multi-part here;
      // after injection the derived table exposes the alias only, so a
      // fully qualified reference to a filtered table cannot keep binding
      context.qualifiedColumnRefs.add(qualifiedKey(id.names));
      return node;
    }
```

`injectIfFiltered` 在 `context.injections++` 处同时登记：

```java
    context.injections++;
    context.injectedKeys.add(qualifiedKey(List.of(table.catalog(), table.schema(), table.name())));
```

`apply` 在 `rewriteQuery` 之后、返回之前：

```java
    SqlNode rewritten = rewriteQuery(parsed, context, new ArrayDeque<>());
    if (context.injections > 0) {
      for (String ref : context.qualifiedColumnRefs) {
        if (context.injectedKeys.contains(ref)) {
          throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
              "column reference qualified with the full name of a row-filtered table ('" + ref
                  + "') cannot keep its binding through the filter injection; "
                  + "use an alias or a single-part qualifier");
        }
      }
    }
    return new Result(rewritten, context.injections);
```

（需 `import io.sqlmask.metadata.ColumnKey;`。）

- [ ] **Step 3: 跑测试**

Run: `mvn -q test -Dtest=RowFilterRewriterTest`
Expected: `rejectsFullyQualifiedColumnReferenceOfFilteredTable` 绿；`selfJoinInjectsIndependentlyAtBothSites`（含 `customer.id` 单段限定）等不回归。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/sqlmask/rowfilter/RowFilterRewriter.java
git commit -m "feat: 受过滤表三段全名列限定显式拒绝，防止注入后绑定破坏"
```

---

### Task 7: FROM 形态 fail-closed 白名单（REW-25/26）

两个红测试：`rejectsTablesampleOnFilteredTable`（TABLESAMPLE 包着受控表被静默透传）与既有 `allowsUnnestWithoutFilteredTable`（必须保持不误伤）。白名单外的 FROM/查询位置形态：扫描子树，**证明不含受控表引用**才放行，否则 `UNSUPPORTED_STATEMENT`。

**Files:**
- Modify: `src/main/java/io/sqlmask/rowfilter/RowFilterRewriter.java`（`rewriteFromItem` 与 `rewriteQuery` 的 default 分支、新增扫描方法）
- Test: `src/test/java/io/sqlmask/rowfilter/RowFilterRewriterTest.java`（新增 REW-26 合成节点用例）

**Interfaces:**
- Consumes: `Context.loaded`（声明表清单）、`nameMatches`
- Produces: `containsFilteredTableReference(SqlNode, Context)` — 子树是否存在指向受过滤声明表的引用

- [ ] **Step 1: 新增测试（先写失败测试）**

```java
  @Test
  void unknownFromShapeAroundFilteredTableIsRejected() {
    // REW-26：白名单外的合成 FROM 形态——含受控表子树必须拒绝而非静默跳过
    Fixture f = fixture(TWO_TABLE_YAML);
    // 用方言支持的 LATERAL 形态表达"白名单外包装"：TABLESAMPLE/LATERAL/UNNEST
    // 各有独立用例，这里覆盖包装节点是未知 SqlCall 的路径
    SqlNode parsed = parse("SELECT id FROM crm.public.customer TABLESAMPLE BERNOULLI (5)");
    SqlMaskException e = assertThrows(SqlMaskException.class, () -> apply(f, parsed));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, e.getCode(), () -> e.getMessage());
  }
```

（REW-26 的"真正合成节点"在实现细节上与 TABLESAMPLE 走同一条 default 分支；若执行时想再加固，可手工 `new SqlBasicCall(未知算子, ...)`，此处以可解析形态覆盖同一路径即可。）

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -q test -Dtest=RowFilterRewriterTest#rejectsTablesampleOnFilteredTable+unknownFromShapeAroundFilteredTableIsRejected`
Expected: 两条 FAIL（Expected exception not thrown）。

- [ ] **Step 3: 实现 fail-closed 扫描**

`RowFilterRewriter` 新增（`rewriteTableReference` 旁）：

```java
  /**
   * Whether any table reference inside the subtree points at a declared table
   * with a configured row filter. Used by the fail-closed whitelist: FROM
   * shapes the rewriter does not understand may contain such references, and
   * skipping them silently would read filtered tables unfiltered.
   */
  private boolean containsFilteredTableReference(SqlNode node, Context context) {
    if (node == null) {
      return false;
    }
    if (node instanceof SqlIdentifier id) {
      List<String> names = id.names;
      for (TableMetadata table : context.loaded.tables()) {
        if (table.rowFilter() == null) {
          continue;
        }
        if (names.size() == 1 && nameMatches(names.get(0), table.name())) {
          return true;
        }
        if (names.size() == 3 && nameMatches(names.get(0), table.catalog())
            && nameMatches(names.get(1), table.schema())
            && nameMatches(names.get(2), table.name())) {
          return true;
        }
      }
      return false;
    }
    if (node instanceof SqlCall call) {
      for (SqlNode operand : call.getOperandList()) {
        if (containsFilteredTableReference(operand, context)) {
          return true;
        }
      }
    }
    if (node instanceof SqlNodeList list) {
      for (SqlNode item : list) {
        if (containsFilteredTableReference(item, context)) {
          return true;
        }
      }
    }
    return false;
  }
```

`rewriteFromItem` 的 `default:` 分支改为：

```java
      default:
        // fail-closed: unknown FROM wrappers (TABLESAMPLE, LATERAL, UNNEST,
        // anything the babel parser produces) may hide a filtered table;
        // rewriting is only skipped when the subtree provably contains none
        if (containsFilteredTableReference(from, context)) {
          throw new SqlMaskException(SqlMaskException.Code.UNSUPPORTED_STATEMENT,
              "unsupported FROM construct around a row-filtered table (kind " + from.getKind()
                  + "); row filtering cannot be applied safely");
        }
        return from;
```

`rewriteQuery` 的 `default:` 分支同样改为该检查（消息措辞 "unsupported query construct"）。

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=RowFilterRewriterTest`
Expected: `rejectsTablesampleOnFilteredTable`、`unknownFromShapeAroundFilteredTableIsRejected`、`allowsUnnestWithoutFilteredTable` 全绿；`dmlAndDdlNodesAreNeverTouched` 保持绿（INSERT/CTAS 目标名撞受控表也不拒——default 分支扫描的是 from-item/query 子树，写语句根本不进 rewriter；该测试喂的是完整写语句，`rewriteQuery` default 的扫描对象是其操作数，其中的 `customer` 目标名会命中受过滤表名…… **注意**：若该用例因此转红，把 `rewriteQuery` default 的扫描范围限制为"来自读语句管线"不可行，正确做法是保持 rewriter 的契约——**只接收源查询**（spec §7.4）——并把 `dmlAndDdlNodesAreNeverTouched` 的期望改为：完整 DML/DDL 节点喂入时 default 分支直接原样返回（引擎契约保证），即该用例断言维持 `assertSame`、但扫描检查仅存在于 `rewriteFromItem`。`rewriteQuery` 的 default 保留检查，但该用例的 INSERT 根节点走 default → 需要豁免：**在 `apply` 入口只允许查询类根节点**：`rewriteQuery` 的 default 改为直接 `return node`（引擎已把写语句挡在管线外，rewriteQuery 的 default 只剩根级意外形态，交给校验器），fail-closed 检查保留在 `rewriteFromItem`（FROM 位置才是表引用的藏身处）。）

按上面注意项执行：`rewriteQuery` 的 default 保持 `return node;` 不加扫描；`rewriteFromItem` 的 default 加 fail-closed 扫描。重跑本步。

- [ ] **Step 5: 跑测试**

Run: `mvn -q test -Dtest=RowFilterRewriterTest`
Expected: 全绿（含 `dmlAndDdlNodesAreNeverTouched`）。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/sqlmask/rowfilter/RowFilterRewriter.java src/test/java/io/sqlmask/rowfilter/RowFilterRewriterTest.java
git commit -m "feat: FROM 形态 fail-closed 白名单，未识别包装含受控表即拒绝"
```

---

### Task 8: 引擎接线 + `rowFiltered` 契约（E 组）

**Files:**
- Modify: `src/main/java/io/sqlmask/rewrite/RewriteEngine.java`（record、`rewrite()`、`rewriteOne()`）
- Test: Create `src/test/java/io/sqlmask/integration/RowFilterIntegrationTest.java`

**Interfaces:**
- Consumes: `RowFilterRegistry.build(loaded, dialect, schema)`、`RowFilterRegistry.isEmpty()`、`RowFilterRewriter.apply(node, loaded, registry) -> Result(SqlNode node, int injections)`、`DialectAdapter.unparse(SqlNode)`
- Produces: `StatementRewrite(int ordinal, String originalSql, String rewrittenSql, boolean masked, boolean rowFiltered)` — Task 9/10/11 依赖该五元组

- [ ] **Step 1: 写集成测试（先失败）**

新建 `src/test/java/io/sqlmask/integration/RowFilterIntegrationTest.java`：

```java
package io.sqlmask.integration;

import io.sqlmask.error.SqlMaskException;
import io.sqlmask.rewrite.RewriteEngine;
import io.sqlmask.rewrite.RewriteEngine.StatementRewrite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end row-filter contracts through the engine: injection composes with
 * masking, row-filter-only statements still carry the user's originalSql, and
 * passthrough statements are byte-identical to today.
 */
class RowFilterIntegrationTest {

  private static final String YAML = """
      metadata:
        tables:
          - catalog: crm
            schema: public
            name: customer
            rowFilter: "status = 'active'"
            columns:
              - {name: id, type: bigint}
              - {name: phone, type: varchar}
              - {name: status, type: varchar}
          - catalog: crm
            schema: public
            name: orders
            rowFilter: "region = 'north'"
            columns:
              - {name: id, type: bigint}
              - {name: region, type: varchar}
              - {name: amount, type: decimal(10,2)}

      columns:
        - catalog: crm
          schema: public
          table: customer
          column: phone
          policy: phone_mask

      policies:
        phone_mask:
          udf: mask_phone
          arguments: [3, 4]
      """;

  private final RewriteEngine engine = new RewriteEngine();

  private static String flat(String sql) {
    return sql.replaceAll("\\s+", " ").trim();
  }

  @Test
  void filterComposesWithMasking() { // ENG-1/11/15
    StatementRewrite s = one(engine, YAML, "SELECT id, phone FROM crm.public.customer");
    assertTrue(s.masked());
    assertTrue(s.rowFiltered());
    assertFalse(s.unchanged());
    String out = flat(s.rewrittenSql());
    assertTrue(out.contains("mask_phone(r.phone, 3, 4) AS phone"), () -> out);
    assertTrue(out.contains("(SELECT * FROM crm.public.customer WHERE status = 'active') AS customer"),
        () -> out);
  }

  @Test
  void rowFilterOnlyKeepsOriginalSqlContract() { // ENG-2/14（D6 契约锁）
    StatementRewrite s = one(engine, YAML, "SELECT id FROM crm.public.customer");
    assertFalse(s.masked());
    assertTrue(s.rowFiltered());
    assertFalse(s.unchanged());
    assertEquals(flat("SELECT id FROM crm.public.customer"), flat(s.originalSql()),
        "originalSql must stay the user's input, not the injected text");
    assertTrue(flat(s.rewrittenSql()).contains("WHERE status = 'active'"), () -> s.rewrittenSql());
  }

  @Test
  void statementWithoutAnyHitIsUntouched() { // ENG-3
    StatementRewrite s = one(engine, YAML, "SELECT 1");
    assertFalse(s.masked());
    assertFalse(s.rowFiltered());
    assertTrue(s.unchanged());
  }

  @Test
  void perStatementFlagsAreIndependent() { // ENG-10
    List<StatementRewrite> all = engine.rewrite(YAML, """
        SELECT id FROM crm.public.customer;
        SELECT phone FROM crm.public.customer;
        SELECT 1;
        """, "postgresql");
    assertTrue(all.get(0).rowFiltered() && !all.get(0).masked());
    assertTrue(all.get(1).rowFiltered() && all.get(1).masked());
    assertFalse(all.get(2).rowFiltered() && all.get(2).masked());
  }

  @Test
  void insertSelectFiltersSourceWithoutMasking() { // ENG-5
    StatementRewrite s = one(engine, YAML, "INSERT INTO archive SELECT * FROM crm.public.orders");
    assertFalse(s.masked());
    assertTrue(s.rowFiltered());
    assertEquals(
        flat("INSERT INTO archive SELECT * FROM"
            + " (SELECT * FROM crm.public.orders WHERE region = 'north') AS orders"),
        flat(s.rewrittenSql()));
  }

  @Test
  void insertValuesPassthroughIsUnfiltered() { // ENG-7
    StatementRewrite s = one(engine, YAML, "INSERT INTO archive VALUES (1, 'x')");
    assertFalse(s.rowFiltered());
    assertFalse(s.masked());
    assertTrue(s.unchanged());
  }

  @Test
  void insertSourceRendersFromPreValidationSnapshot() { // ENG-13（缺陷#3 不复发）
    StatementRewrite s = one(engine, YAML,
        "INSERT INTO archive SELECT id FROM crm.public.orders ORDER BY id LIMIT 3");
    String out = flat(s.rewrittenSql());
    assertEquals(1, countOf(out, "ORDER BY"), () -> out);
    assertEquals(1, countOf(out, "FETCH NEXT"), () -> out);
    assertTrue(out.contains("WHERE region = 'north'"), () -> out);
  }

  @Test
  void existingFailureModesAreUnchanged() { // ENG-8/9
    SqlMaskException update = assertThrows(SqlMaskException.class,
        () -> one(engine, YAML, "UPDATE crm.public.customer SET id = 1"));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, update.getCode());
    SqlMaskException recursive = assertThrows(SqlMaskException.class, () -> one(engine, YAML,
        "WITH t AS (SELECT id FROM crm.public.customer) SELECT * FROM t, t t2"));
    assertTrue(recursive.getCode().name().equals("UNSUPPORTED_STATEMENT")
        || recursive.getCode().name().equals("LINEAGE_UNKNOWN"), () -> recursive.getMessage());
    SqlMaskException hidden = assertThrows(SqlMaskException.class,
        () -> one(engine, YAML, "INSERT INTO archive VALUES ((SELECT max(id) FROM crm.public.orders))"));
    assertEquals(SqlMaskException.Code.UNSUPPORTED_STATEMENT, hidden.getCode());
  }

  private static int countOf(String text, String token) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(token, index)) >= 0) {
      count++;
      index += token.length();
    }
    return count;
  }

  private static StatementRewrite one(RewriteEngine engine, String yaml, String sql) {
    List<StatementRewrite> statements = engine.rewrite(yaml, sql, "postgresql");
    assertEquals(1, statements.size());
    return statements.get(0);
  }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `mvn -q test -Dtest=RowFilterIntegrationTest`
Expected: 编译失败（`StatementRewrite` 还是四参 record）。

- [ ] **Step 3: 扩展 record 并接线**

`RewriteEngine.StatementRewrite` 改为：

```java
  /**
   * Result of one statement. {@code originalSql} is the user's input semantics:
   * for read statements the dialect rendering captured before any row-filter
   * injection, for write statements the statement text as written. {@code
   * rewrittenSql} is the final SQL; {@code masked} whether an outer masking
   * wrapper was added; {@code rowFiltered} whether at least one row-filter
   * predicate was injected.
   */
  public record StatementRewrite(int ordinal, String originalSql, String rewrittenSql,
      boolean masked, boolean rowFiltered) {

    public boolean unchanged() {
      return originalSql.equals(rewrittenSql);
    }
  }
```

`rewrite(...)` 在 `DialectAdapter dialect = createDialect(dialectName);` 之后构建：

```java
    RowFilterRegistry rowFilters = RowFilterRegistry.build(loaded, dialect, schema);
    RowFilterRewriter rowFilterRewriter = new RowFilterRewriter(dialect);
```

并把两者连同 `loaded` 传入 `rewriteOne(...)`（签名按需扩展）。`rewriteOne` 读语句路径：

```java
    SqlNode parsed = dialect.parse(statementText, ordinal);
    String originalSql = dialect.unparse(parsed); // before any injection: the user's input
    RowFilterRewriter.Result filtered =
        rowFilterRewriter.apply(parsed, loaded, rowFilters);
    ValidatedSql validated = dialect.validate(filtered.node(), schema);
    RewritePlan plan = RewritePlan.of(analyzer.analyze(validated), selector);
    String rewritten = rewriteService.rewrite(validated, plan, dialect);
    return new StatementRewrite(ordinal, originalSql, rewritten,
        plan.requiresWrapper(), filtered.injections() > 0);
```

写语句路径（pass-through 判断与 `querySourceOf` null 检查不变）：

```java
      RowFilterRewriter.Result filtered =
          rowFilterRewriter.apply(source, loaded, rowFilters);
      ValidatedSql validated = dialect.validate(filtered.node(), schema);
      RewritePlan plan = RewritePlan.of(analyzer.analyze(validated), selector);
      if (!plan.requiresWrapper()) {
        if (filtered.injections() == 0) {
          return new StatementRewrite(ordinal, statementText, statementText, false, false);
        }
        // row-filter-only write: the composer must rebuild the statement from
        // the pre-validation rendering — never from a post-validation unparse,
        // which duplicates ORDER BY/FETCH
        return new StatementRewrite(ordinal, statementText,
            dialect.composeWriteStatement(parsed, validated.originalSql()), false, true);
      }
      String wrappedSource = rewriteService.rewrite(validated, plan, dialect);
      String rewritten = dialect.composeWriteStatement(parsed, wrappedSource);
      return new StatementRewrite(ordinal, statementText, rewritten, true,
          filtered.injections() > 0);
```

- [ ] **Step 4: 跑集成测试 + 全量**

Run: `mvn -q test -Dtest=RowFilterIntegrationTest` 然后 `mvn -q test`
Expected: RowFilterIntegrationTest 全绿；全量绿（已核实测试代码不直接构造 `StatementRewrite`，record 扩参不影响既有测试编译；controller/CLI 测试的响应断言在 Task 9/11 各自扩展）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/sqlmask/rewrite/RewriteEngine.java src/test/java/io/sqlmask/integration/RowFilterIntegrationTest.java
git commit -m "feat: 引擎接入行过滤，StatementRewrite 增加 rowFiltered 与真实输入契约"
```

---

### Task 9: API/DTO（F 组）

**Files:**
- Modify: `src/main/java/io/sqlmask/server/ConfigController.java`（`TableDto` 增加 `rowFilter`）
- Test: `src/test/java/io/sqlmask/server/ConfigControllerTest.java`、`src/test/java/io/sqlmask/server/RewriteControllerTest.java`

**Interfaces:**
- Consumes: `TableMetadata.rowFilter()`（null = 未配置）
- Produces: `TableDto(String catalog, String schema, String name, String rowFilter, List<ColumnDto> columns)`；`/api/rewrite` 的 `statements[].rowFiltered` 由 record 序列化自动携带

- [ ] **Step 1: 写失败测试**

`ConfigControllerTest` 增加：

```java
  @Test
  void parsesRowFilterIntoEditorShape() throws Exception {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              rowFilter: "status = 'active'"
              columns:
                - {name: id, type: bigint}
            - catalog: crm
              schema: public
              name: orders
              columns:
                - {name: id, type: bigint}
        policies: {}
        """;
    mockMvc.perform(post("/api/config/parse")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"metadataYaml\": " + objectMapper.writeValueAsString(yaml) + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tables[0].rowFilter").value("status = 'active'"))
        .andExpect(jsonPath("$.tables[1].rowFilter").value(""));
  }
```

（`objectMapper`/`mockMvc`/`jsonPath` 的既有引用方式以该测试文件当前写法为准，缺则补 `@Autowired ObjectMapper objectMapper` 与静态导入。）

`RewriteControllerTest` 增加（夹具 YAML 与 RowFilterIntegrationTest 一致，内联即可）：

```java
  @Test
  void rewriteResponseCarriesRowFilteredFlag() throws Exception {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              rowFilter: "status = 'active'"
              columns:
                - {name: id, type: bigint}
        policies: {}
        """;
    mockMvc.perform(post("/api/rewrite")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(java.util.Map.of(
                "metadataYaml", yaml, "sql", "SELECT id FROM crm.public.customer"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statements[0].rowFiltered").value(true))
        .andExpect(jsonPath("$.statements[0].masked").value(false))
        .andExpect(jsonPath("$.statements[0].unchanged").value(false));
  }
```

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -q test -Dtest=ConfigControllerTest,RewriteControllerTest`
Expected: 两条 FAIL（rowFilter 字段缺失 / rowFiltered 缺失）。

- [ ] **Step 3: 实现**

`ConfigController.toResponse` 的 TableDto 构造改为：

```java
        .map(t -> new TableDto(t.catalog(), t.schema(), t.name(),
            t.rowFilter() == null ? "" : t.rowFilter(),
            t.columns().stream()
                .map(c -> new ColumnDto(c.name(), c.typeDeclaration()))
                .toList()))
```

record 改为：

```java
  public record TableDto(String catalog, String schema, String name, String rowFilter,
      List<ColumnDto> columns) {
  }
```

（`RewriteResponse` 无需改动——`StatementRewrite.rowFiltered` 随 record 序列化。）

- [ ] **Step 4: 跑测试**

Run: `mvn -q test -Dtest=ConfigControllerTest,RewriteControllerTest` 然后 `mvn -q test`
Expected: 全绿（若 `index.html` 相关既有断言依赖 TableDto 形态而失败，仅修 Task 10 时一并处理，此处不动前端）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/sqlmask/server/ConfigController.java src/test/java/io/sqlmask/server/ConfigControllerTest.java src/test/java/io/sqlmask/server/RewriteControllerTest.java
git commit -m "feat: 配置解析与改写 API 暴露 rowFilter 与 rowFiltered"
```

---

### Task 10: 前端 round-trip 与标记（F 组）

**Files:**
- Modify: `src/main/resources/static/index.html`（表单、YAML 生成/导入、结果渲染）

**Interfaces:**
- Consumes: `/api/config/parse` 的 `tables[].rowFilter`（空串 = 未配置）、`/api/rewrite` 的 `statements[].rowFiltered`

- [ ] **Step 1: 表单与状态**

- `SAMPLE_STATE()` 中 customer 表对象加 `rowFilter: "status = 'active'"`（其余表 `rowFilter: ""`）；`$("addTable")` 分支的默认对象 `{ catalog: "", schema: "public", name: "", columns: [] }` 加 `rowFilter: ""`；
- `renderTables()` 的表名 row 之后插入一行：

```js
      <div class="row">
        <input data-edit="table" data-i="${ti}" data-field="rowFilter" value="${esc(t.rowFilter || "")}"
               placeholder="行过滤条件，如 status = 'active'" style="grid-column: 1 / -1">
      </div>
```

- 输入事件 `kind === "table"` 分支已用 `t[field] = el.value` 通配，无需改。

- [ ] **Step 2: YAML 生成与导入**

`generateYaml()` 在 `name:` 行之后加：

```js
      if ((t.rowFilter || "").trim() !== "") {
        L.push(`      rowFilter: ${yamlScalar(t.rowFilter)}`);
      }
```

`fromServerConfig(payload)` 中 tables 映射加 `rowFilter: t.rowFilter || ""`（空白归一为空串，保证 YAML ⇄ 表单往返无空串字段残留——API-3）。

- [ ] **Step 3: 结果标记（API-4/5）**

`render()` 的语句卡片改为：

```js
  resultsEl.innerHTML = statements.map((s) => {
    const tags = [];
    if (s.masked) tags.push("已脱敏（外层包装 UDF）");
    if (s.rowFiltered) tags.push("已行过滤");
    const tag = tags.length ? tags.join(" + ") : "原样输出";
    return `
    <div class="stmt ${s.masked ? "masked" : ""}">
      <div class="stmt-head">
        <span>语句 ${s.ordinal}</span>
        <span class="tag">${tag}</span>
      </div>
      <pre>${esc(s.rewrittenSql)}</pre>
      ${s.unchanged ? "" : `
      <details>
        <summary>查看原始语句</summary>
        <pre>${esc(s.originalSql)}</pre>
      </details>`}
    </div>`;
  }).join("");
```

（row-filter-only 的 `unchanged` 为 false → 原始语句折叠块照常出现，index.html:619 的行为不变。）

- [ ] **Step 4: 验证**

Run: `mvn -q test -Dtest=ConfigControllerTest,RewriteControllerTest`
Expected: 全绿。另做手工核对（API-4）：`java -jar target/sql-mask.jar` 启动后按 (masked,rowFiltered) 四象限各跑一条语句，确认标签分别为 原样输出 / 已行过滤 / 已脱敏 / 已脱敏 + 已行过滤，以及 YAML ⇄ 表单往返 rowFilter 不丢、空白不残留（打包：`mvn -q package -DskipTests`）。

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat: 页面支持行过滤编辑、YAML 往返与双标记展示"
```

---

### Task 11: CLI 行为锁（G 组）

**Files:**
- Test: `src/test/java/io/sqlmask/cli/SqlMaskRunnerTest.java`（或 `SqlMaskApplicationTest`，选更贴近 runner 输出的那个）

**Interfaces:**
- Consumes: `RewriteEngine.join` 输出格式（语句间空行、`;` 结尾）
- Produces: 无（CLI 零改动，只锁行为）

- [ ] **Step 1: 写测试（先失败或直接绿均可——这是行为锁）**

```java
  @Test
  void rowFilterOutputIsPureSqlOnStdoutShape() throws Exception {
    String yaml = """
        metadata:
          tables:
            - catalog: crm
              schema: public
              name: customer
              rowFilter: "status = 'active'"
              columns:
                - {name: id, type: bigint}
                - {name: phone, type: varchar}
        policies: {}
        """;
    Path metadata = Files.createTempFile("rf-meta", ".yaml");
    Path input = Files.createTempFile("rf-sql", ".sql");
    Path output = Files.createTempFile("rf-out", ".sql");
    Files.writeString(metadata, yaml);
    Files.writeString(input, "SELECT id FROM crm.public.customer;\nSELECT 1;\n");
    int exit = new SqlMaskApplication().run(new String[]{
        "--metadata", metadata.toString(), "--input", input.toString(),
        "--output", output.toString()}, System.in, System.out, System.err);
    assertEquals(0, exit);
    String written = Files.readString(output);
    String[] statements = written.trim().split("\n\n");
    assertEquals(2, statements.length, () -> written);
    assertTrue(statements[0].endsWith(";"), () -> written);
    assertTrue(statements[0].contains("WHERE status = 'active'"), () -> written);
    assertTrue(statements[1].contains("SELECT 1;"), () -> written);
  }
```

（可测试入口签名 `int run(String[] args, InputStream in, PrintStream out, PrintStream err)` 已核实；`--output` 写出为 `join 结果 + "\n"`。CLI 产品代码零改动。）

- [ ] **Step 2: 跑测试**

Run: `mvn -q test -Dtest=SqlMaskRunnerTest,SqlMaskApplicationTest`
Expected: PASS（若因签名差异失败，只允许调整测试的调用封装，不允许改 CLI 产品代码）。

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/sqlmask/cli/
git commit -m "test: CLI 行过滤场景输出格式行为锁"
```

---

### Task 12: 字节级 golden 回归 + 文档收尾（H 组）

**Files:**
- Create: `src/test/java/io/sqlmask/regression/GoldenOutputTest.java`
- Create: `src/test/resources/golden/*.sql`（golden 文件，生成后入库）
- Create: `.gitattributes`（golden 与 tpcds 输出的换行锁定）
- Modify: `README.md`（行过滤章节）
- Modify: `src/main/java/io/sqlmask/metadata/TableMetadata.java`（删除死代码 `tableKey()`）

**Interfaces:**
- Consumes: `RewriteEngine.rewrite`（零 rowFilter 配置 → 输出与主线逐字节一致）
- Produces: golden 文件成为后续所有改动的行为基线（GOLD-1/2）

- [ ] **Step 1: 建 `.gitattributes` 锁定换行**

```
src/test/resources/golden/*.sql text eol=lf
```

（Windows 检出会把 LF 转 CRLF，byte 级对比会被换行污染；测试内仍额外做 `\r\n → \n` 归一以双保险——除此之外不做任何空白归一。）

- [ ] **Step 2: 写 golden 测试（先失败）**

```java
package io.sqlmask.regression;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-level behavior lock: with no row filter configured anywhere the output
 * must be byte-identical to the committed golden files. Unlike the whitespace
 * normalizing assertions elsewhere, this catches any spacing, quoting or
 * newline drift. Line endings are the single allowed normalization (Windows
 * checkout converts LF to CRLF).
 */
class GoldenOutputTest {

  private static final Path METADATA = Path.of("src/test/resources/metadata/golden.yaml");

  private record Case(String name, String sql) {
  }

  private static final List<Case> CASES = List.of(
      new Case("plain-select", "SELECT c.id, c.phone\nFROM crm.public.customer AS c\nWHERE c.id < 100;"),
      new Case("with-select", "WITH active AS (SELECT id, phone FROM crm.public.customer)\nSELECT id FROM active WHERE id > 10;"),
      new Case("masked-select", "SELECT phone FROM crm.public.customer;"),
      new Case("order-limit", "SELECT id FROM crm.public.customer ORDER BY id LIMIT 5;"),
      new Case("insert-select", "INSERT INTO archive SELECT id, phone FROM crm.public.customer;"),
      new Case("ctas", "CREATE TABLE IF NOT EXISTS archive AS SELECT id FROM crm.public.customer;"),
      new Case("passthrough", "SELECT 1 AS x;"));

  private static String lf(String s) {
    return s.replace("\r\n", "\n");
  }

  @Test
  void outputMatchesGoldenFilesByteForByte() throws Exception {
    for (Case c : CASES) {
      String expected = lf(Files.readString(
          Path.of("src/test/resources/golden/" + c.name() + ".sql"), StandardCharsets.UTF_8));
      String actual = lf(run(c.sql()));
      assertEquals(expected, actual, () -> c.name());
    }
  }

  private static String run(String sql) {
    List<io.sqlmask.rewrite.RewriteEngine.StatementRewrite> statements =
        new io.sqlmask.rewrite.RewriteEngine().rewrite(readMetadata(), sql, "postgresql");
    return io.sqlmask.rewrite.RewriteEngine.join(statements) + "\n";
  }

  private static String readMetadata() {
    try {
      return Files.readString(METADATA, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
```

（`golden.yaml`：以 `src/test/resources/metadata/integration.yaml` 为起点复制，确保**不含任何 rowFilter**，customer.phone 绑定 mask_phone 等策略，使 masked-select 与 passthrough 两态都有覆盖；如现有 integration.yaml 不满足就新建专用文件。测试内唯一允许的归一是 `\r\n → \n`，除此之外不得做任何空白处理。）

- [ ] **Step 3: 生成 golden 并确认**

临时把 `outputMatchesGoldenFilesByteForByte` 的循环体改为把 `run(c.sql())` 写入 golden 文件（或加一个 `@Disabled("regeneration helper")` 的生成测试运行一次），生成 7 个文件；恢复断言形态。

Run: `mvn -q test -Dtest=GoldenOutputTest`
Expected: PASS（golden 与当前行为一致）。手动抽查 2 个文件：masked-select 的输出含 `mask_phone(...)` 外层包装、passthrough 输出与语句原样（Calcite 渲染）一致。

- [ ] **Step 4: TPC-DS 字节级锁（GOLD-2）**

在 `GoldenOutputTest` 增加第二个测试：读 `tpcds/EXPECTED.md`，对其中标注**成功**的查询文件（至少含 `tpcds/queries/tpcds_masking_test.sql`、`tpcds_common_cases.sql`、`tpcds_oneline.sql`；以 EXPECTED.md 的用例清单为准，把标注成功的文件全部纳入），用 `tpcds/metadata.yaml` 跑 `RewriteEngine`，golden 存 `src/test/resources/golden/tpcds/<名>.sql`，同法生成并锁定。预期失败文件（unsupported/setops 等）不纳入 golden，改由一条断言其非零退出/异常的用例覆盖（沿用 EXPECTED.md 分类）。

Run: `mvn -q test -Dtest=GoldenOutputTest`
Expected: PASS。

- [ ] **Step 5: 删除死代码 `TableMetadata.tableKey()`**

该方法以空列名构造 `ColumnKey`，一旦被调用即抛 `IllegalArgumentException`（全仓库无调用点）。删除方法与可能残留的注释。

Run: `mvn -q test`
Expected: 全量绿（编译通过即证明确为死代码）。

- [ ] **Step 6: README 更新**

`README.md` 的「改写语义」节之后新增「行过滤」小节：YAML 示例（含 `policies: {}` 必填提示）、条件白名单（列/字面量/比较/IS [NOT] DISTINCT FROM/IN 常量列表/算术；禁函数、子查询、会话与时间函数）、注入形态与覆盖范围、错误行为清单（一段名多候选 `VALIDATION_ERROR`、三段全名列限定 `UNSUPPORTED_STATEMENT`、TABLESAMPLE/LATERAL/UNNEST 含受控表拒绝、根级集合操作维持拒绝、两段名维持现状）、`rowFiltered` API 字段与页面标记说明。

- [ ] **Step 7: 全量验证 + Commit**

Run: `mvn -q test` 然后 `mvn -q package`
Expected: 全部通过；打包成功。

```bash
git add .gitattributes src/test/java/io/sqlmask/regression/GoldenOutputTest.java src/test/resources/golden src/test/resources/metadata/golden.yaml src/main/java/io/sqlmask/metadata/TableMetadata.java README.md
git commit -m "test: 行过滤 golden 字节级回归与 TPC-DS 锁定，README 与死代码收尾"
```

---

## 任务依赖与执行顺序

Task 1 → 2 → 3 → 4 → 5 → 6 → 7（D 组收口，1/3/7 之间有源文件耦合，按序执行）；Task 8 依赖 1–7 全绿；Task 9/10/11 依赖 8 的五参 record；Task 12 最后（golden 锁定在所有行为就绪之后）。

## 自审记录（计划完成后核对）

- Spec 覆盖：§2.3 白名单（Task 0 已实现的 Registry + REG-8 已绿）、§5.2 严格解析（Task 2/3）、§5.3 契约（Task 8）、§7.2 三类遍历与 fail-closed（Task 4/7）、§7.3 同车修复（WIP 已绿，A 组行为锁在 CteExpanderTest）、§7.4 接线（Task 8）、§9 API/页面（Task 9/10）、§11 golden（Task 12）、死代码（Task 12 Step 5）；
- E 组其余用例（ENG-4/6/12 等）由 Task 8 的集成测试与既有 RewriteEngine 测试形态覆盖，如执行时发现缺口，按 RowFilterIntegrationTest 的夹具补断言，不新增产品代码；
- 类型一致性：`Result(SqlNode, int)`、五参 `StatementRewrite`、`TableDto` 五字段、`bindingOf/Binding` 在各任务 Interfaces 中前后呼应。
