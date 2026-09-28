# M1 逐差分档案(parser / lexer 与 Java 版行为对齐记录)

M1 全分支终审交付物。逐条记录 Go 移植过程中发现并处置的每一处与 Java 版
(mask-sqlparser fork,Apache Calcite JavaCC 文法)的行为分歧。每项五要素:
**输入形态 / Java 行为(jar 实测)/ Go 初始行为 / 根因 / 处置**。处置分三类:

- **修复**:已改码对齐,带回归测试;
- **留档**:维持 Go 行为,差异记录在案(spec 决策 5 兜底条款,语料零命中);
- **契约记录**:行为已对齐或错误码等价,由 `testdata/contract/parse-verdicts.json`
  差分护栏锁定。

证据出处:`.superpowers/sdd/2026-09-27-go-port-m1-parser/` 下 progress.md
(控制者裁定账本)与各 task-*.md 报告(jar 探针批次、语料扫描记录)。契约
门禁:`go test ./contract/ -count=1`(离线 110 条)与
`CONTRACT_LIVE_JAVA=1 go test ./contract/ -timeout 30m`(实时对 jar,终审
实测 110/110 对齐)。

---

## 词法层(lexer,Task 4 + fix round 1 + T11)

### D1. 块注释嵌套 —— 处置:修复

- **输入形态**:`/* /* x */ */ 1`
- **Java 行为**(JavaCC 实测):不嵌套——第一个 `*/` 终结注释,其后散落的
  `*/` 成为合法 COMMENT_END token,解析期才报错。
- **Go 初始行为**:按简报嵌套(整体不产 token),接受面反向。
- **根因**:简报规定与 JavaCC 词法器实际文法不符;Java 自己的 splitter 与
  lexer 在嵌套注释上不对称,Go 两侧各自对齐(镜像其不对称)。
- **处置**:`skipBlockComment` 去掉深度计数,第一个 `*/` 返回。散落 `*/` 的
  种别差异(Go `Op(*)+Op(/)` vs Java COMMENT_END token)留档——错误码一致,
  契约记录按错误码比对。测试:`TestComments`。

### D2. `//` 行注释 —— 处置:修复

- **输入形态**:`1 // x\n2`、`a/b//c`
- **Java 行为**:`SINGLE_LINE_COMMENT: ("//"|"--")`,`//` 是行注释。
- **Go 初始行为**:按简报只认 `--`,`//` 被切成两个 `Op(/)`。
- **根因**:简报只规定 `--`,漏了 fork 文法的双前缀行注释。
- **处置**:`skipTrivia` 增加 `//` 分支,与 `--` 共用 `skipLineComment`。
  测试:`TestComments`(含文件尾无换行形态)。

### D3. 数字词法(`.5` / `1.` / `1.2.3`)—— 处置:修复

- **输入形态**:`.5`、`1.`、`1.2.3`、`1.e5`、`1..2`、`.5e3`、`1.day`、`a.5`
- **Java 行为**(JavaCC probe 实测):`.5` 是 DECIMAL_NUMERIC_LITERAL(文法
  含 `"."(["0"-"9"]+)`);`1.` 是 Number("1.");`1.2.3` 产两个 Number(1.2)、
  (.3)——词法层无上下文、恒最长匹配。
- **Go 初始行为**:按简报模式 `D+ [. D+]`:`.5` 词法错、`1.` 切成
  Number(1)+Op(.)、`1.2.3` 在第二个 `.` 报词法错——Java 接受的常见输入
  Go 拒绝。
- **根因**:简报数字规则窄于 JavaCC DECIMAL/APPROX 实际文法(即简报自列的
  "决策 8 风险清单")。
- **处置**:`scanNumberTail` 对齐现规则 `D+ .? D*`(整数位后小数点无条件
  消费)与 `. D+`;指数 `e/E [+-]? D+` 必须有数字(`1e`→Ident、`1e10`→
  Number 维持)。11 例表驱动全部先经 Java probe 锁定。测试:`TestNumbers`。

### D4. Tab 按 8 制表位 —— 处置:修复

- **输入形态**:`SELECT\t1`(及错误消息中的行列定位)
- **Java 行为**(SimpleCharStream 实测):Tab 按 8 制表位推进,`1`@1:9。
- **Go 初始行为**:按简报"Tab 记 1 列"(Calcite 语义),`1`@1:8。
- **根因**:简报取了错误的位置模型来源——词法器是 JavaCC SimpleCharStream
  而非 Calcite;位置虽属"可异文案",但 M2/M3 对 Java 排错时位置一致价值大。
- **处置**:`read()` 按 `column--; column += 8 - column%8` 推进(tabSize=8)。
  测试:`TestPositions`。

### D5. 前缀串 `N'…'` / `x'…'` / `U&'…'` / `_charset'…'` —— 处置:修复(留档 M3)

- **输入形态**:`N'x'`、`x'ff'`、`U&'…'`、`_latin1'x'`、`[方括号标识符]`
- **Java 行为**(jar 实测):均为单一 token(PREFIXED/BINARY/UNICODE_STRING、
  BRACKET_QUOTED_IDENTIFIER)。
- **Go 初始行为**:拆成 Ident+String 等(简报词法面未列),接受面出现差异。
- **根因**:sqlmask 自定义 token 族未进简报词法面。
- **处置**:`lexPrefixedString` 按 jar 实测产单 String token(T11 落地)。
  **留档**:`N'x'` 的 unparse 折算(Java 改写为 `_ISO-8859-1'x'`)属 M3,
  需按原文重写前缀。README「已知口径」与 token.go 包注释同口径。

### D6. 运算符 `<=>` `&` `^` `~` —— 处置:修复(白名单快照契约记录)

- **输入形态**:`SELECT 1 <=> 2`、`1 & 2`、`1 ^ 2`、`1 ~ 2`
- **Java 行为**(jar 实测):fork 词法表确有这些 token,解析五方言均接受
  (mysql/hive/sparksql 校验亦过,pg/trino 到校验期才拒);`|` 与一元 `~1`
  解析拒绝,故不收。
- **Go 初始行为**:白名单外字符,词法错——Java 接受 Go 拒绝。
- **根因**:简报 21 项运算符白名单窄于 fork 词法表。
- **处置**:opTable 扩 4 项;`testdata/tokens.json` 仍为简报白名单快照,
  超集关系由 `TestOperatorRoundTrip` 锁定,且超出部分**恰好**等于扩展集
  `{"<=>", "&", "^", "~"}`(终审补的真实断言,防扩展集静默增减)。

### D7. 词法错 vs 解析期错的构造性分歧族 —— 处置:留档(错误码契约记录)

- **输入形态**:未闭合字符串 `'abc`、引号标识符内裸换行 `"a\nb"`、
  BackTick 方言下 `"` 开头、白名单外字符 `: ! | { } [ ] -> ..` 等
- **Java 行为**:词法层放行(回退为合法 token),解析期报 PARSE_ERROR。
- **Go 初始行为**:本词法器直接报词法错(TokenKind 无种别可承载回退)。
- **根因**:Go 词法器单遍扫描无回退;两侧错误码同为 PARSE_ERROR、同在
  解析管线早期,对外边界等价,仅消息与位置不同(M1 标准允许)。
- **处置**:留档(token.go 包注释「已知口径」段 + task-4-report §5);
  契约按错误码比对。空输入 EOF 位置 Go {1,1} vs Java {0,0} 同族留档
  (Ruling 13)。`/*+ hint` 维持跳过为 trivia(Ruling 3:良构 hint 全接受,
  病态 hint 体/位置为已知缺口)。

---

## 表达式层(parser/expr.go,Task 6)

### D8. `!=` 按 conformance 拒绝 —— 处置:修复

- **输入形态**:`1 != 2`(五方言逐一)
- **Java 行为**(jar 实测):postgresql/trino(Default conformance)解析期
  拒绝,文案逐字 `Bang equal '!=' is not allowed under the current SQL
  conformance level`;mysql/hive/sparksql(MYSQL_5/LENIENT)接受(unparse
  规范化为 `<>`)。
- **Go 初始行为**:五方言均接受(多接受 pg/trino 方向)。
- **根因**:简报未给 conformance 位;差异源自 Java SqlConformanceEnum。
- **处置**:`dialect.Profile` 新增 `Conformance` 字段(Default/MySQL5/
  Lenient,对齐 Java validatorConformance;pg/trino=Default、mysql=MySQL5、
  hive/sparksql=Lenient),谓词层 Default 档拒 `!=`,message 逐字。
  测试:`TestBangEqualConformance`。该字段 M2 校验器续用。

### D9. ROLLUP 表达式语境拒绝 —— 处置:修复

- **输入形态**:`SELECT ROLLUP(1)`、`rollup(a)`(裸词/调用/小写)
- **Java 行为**(jar 实测):PARSE_ERROR,文案逐字
  `Incorrect syntax near the keyword 'ROLLUP'`;同族 `CUBE(1)`/`GROUPING(1)`/
  `SETS(1)` 解析接受(校验期才失败)。
- **Go 初始行为**:ROLLUP 当普通函数接受。
- **根因**:fork 词法中 ROLLUP 为保留 token,仅 GROUP BY 的
  GroupingElementList 产生式可达;表达式语境本就不可达,Go 的通用函数文法
  多放行。
- **处置**:`parseIdentOrCall` 入口对未引号 Ident `ROLLUP`/`LATERAL` 显式
  拒绝(message 逐字,含位置);引号标识符不受影响。测试:
  `TestParseExprRejects`、`TestGroupingSetsFamilyExpressionContext`。
  GROUP BY 语境的 ROLLUP 见 D10。

---

## 查询层(parser/select.go,Task 7/8)

### D10. GROUP BY ROLLUP 接受 —— 处置:修复(偏离简报,决策 5 兜底条款)

- **输入形态**:`GROUP BY ROLLUP (c_email_address)`(语料
  `mask-engine/tpcds/queries/tpcds_deep_cases.sql` [D9] 实际使用)
- **Java 行为**(jar 实测 + 端到端 exit 0):解析接受——fork
  GroupingElementList 含 ROLLUP 保留产生式。
- **Go 初始行为**:按任务简报拒绝。
- **根因**:简报"拒绝"与 fork 文法及真实语料矛盾;拒绝将直接造成 T10/T11
  契约差分 Mismatch。
- **处置**:`parseGroupByList` 按 `ast.FunctionCall` 接受(仅 GROUP BY 语境;
  表达式语境拒绝见 D9)。命中决策 5 兜底条款「差分证明 Java 接受且语料
  需要,再按协议补」——偏离简报已经控制者裁定。**M2 交接**:校验器需按
  名字识别该编码的 ROLLUP/CUBE 分组元素语义。

### D11. GROUP BY `()` 拒绝 / ast.GroupingSet 删除 —— 处置:修复(以 jar 为准)

- **输入形态**:`GROUP BY ()`
- **Java 行为**(jar 实测):pg/mysql 均 PARSE_ERROR。
- **Go 初始行为**:T11 实现者中断前按早期探针结论(接受)引入了
  `ast.GroupingSet` 节点。
- **根因**:中断前 ast 注释记载的探针结论与 jar 实际输出矛盾。
- **处置**:控制者裁定以 jar 为准——`ast.GroupingSet` 删除,空括号分组集
  走通用文法自然拒绝。契约记录(110 条内含相关判定)。

### D12. OFFSET..LIMIT 与 LIMIT start,count 门控 —— 处置:修复(T11 收口)

- **输入形态**:`OFFSET 1 ROWS LIMIT 1`、`LIMIT 1, 2`(及与 OFFSET 同现)
- **Java 行为**(jar 实测):fork 分支受 `isOffsetLimitAllowed`/
  `isLimitStartCountAllowed` 门控——`OFFSET n LIMIT m` 仅 Lenient 档接受
  (pg/MySQL5 拒,文案 `'OFFSET start LIMIT count' is not allowed under the
  current SQL conformance level`);`LIMIT start, count` 在 MySQL5/Lenient
  接受且其后可随 OFFSET(文案同族 `'LIMIT start, count' ...`)。
- **Go 初始行为**:一律拒绝(组合面按简报收窄,T8 记 watchlist)。
- **根因**:简报组合面未列这两个 fork 门控分支;D8 落地的 Conformance 字段
  使门控有了落点。
- **处置**:T11 按 jar 实测落地门控:OFFSET..LIMIT 仅 Lenient;LIMIT
  start,count 在 MySQL5/Lenient 接受。AST 归一 `Limit=count, Offset=start`。
  终审同步修正 select.go 文件头与 parseOrderAndTail 注释(T8 时代
  "OFFSET 之后不接 LIMIT……记 T11 watchlist" 的反向描述已按现行为改写)。

### D13. 表名段数上限 —— 处置:修复

- **输入形态**:`SELECT * FROM a.b.c.d.e`(5 段)
- **Java 行为**(jar 实测):解析接受——复合标识符在解析期不限段数,语义
  校验属后续阶段。
- **Go 初始行为**:按简报 TableName 1–4 段,超 4 段在第 5 段 PARSE_ERROR。
- **根因**:简报 1–4 段限制与 JavaCC 复合标识符文法不符。
- **处置**:`parseTableIdentifier` 段数无上限(5 段专测);终审同步把
  select.go 文件头与 parseTableRefPrimary 注释中残留的"1–4 段"改为
  "段数无上限"。

### D14. `t.*` / `s.t.*` 星项编码 —— 处置:契约记录(编码约定落实)

- **输入形态**:`SELECT *`、`SELECT t.*`、`SELECT s.t.*`
- **Java 行为**:解析接受。
- **Go 初始行为**:T7 已实现多段星限定符,但 `s.t.*` 无直接测试,编码约定
  只散在实现里。
- **根因**:简报 Interfaces 未钉死多段限定符的承载方式。
- **处置**:T11 统一编码并由契约锁定——`ast.SelectItem.Star` 恒 true:
  裸 `*` 时 `StarQualifier` 空,`t.*`/`s.t.*` 时为限定段(每段一个
  IdentPart,不含星号);表达式项 `Star=false` 且 `StarQualifier` 必空。
  该编码约定本批已补进 `ast/ast.go` 的 SelectItem 文档注释(select.go
  parseSelectItem 内的"编码约定(ast.SelectItem 文档)"引用由此落实)。

### D15. FROM LATERAL / UNNEST / ARRAY —— 处置:留档(spec 决策 5)

- **输入形态**:`FROM LATERAL (SELECT 2) d`、`FROM UNNEST(x)`、`ARRAY[...]`
- **Java 行为**(jar 实测):解析接受(fork 文法含 LATERAL/UNNEST 产生式)。
- **Go 初始行为**:FROM 表引用位置/表达式语境均 PARSE_ERROR(文案与 T6
  表达式层同族 `Incorrect syntax near the keyword 'LATERAL'`)。
- **根因**:spec 决策 5「LATERAL/UNNEST 先不解析」;语料静态扫描零命中。
- **处置**:留档——按兜底条款「语料零命中不修」,语料外输入边界反向时由
  差分护栏外的人工复核归因。GROUPING SETS(GROUP BY 位置,报错于 SETS 位)
  同属决策 5 留档族。

---

## 语句层(parser/stmt.go,Task 9 + T11)

### D16. 首词残片 `COMMIT x` 族 —— 处置:修复(T11 收口)

- **输入形态**:`COMMIT x`、`BEGIN FOO`、`DISCARD x`;对照组:裸
  `COMMIT`/`BEGIN WORK`/`COMMIT TRANSACTION`/`DISCARD ALL`
- **Java 行为**(jar 实测):残片形态(首词认识、后续不可解析)PARSE_ERROR;
  良构形态解析通过、classify 报 UNSUPPORTED_STATEMENT。
- **Go 初始行为**:残片形态落入"认识但不实现"分路报 UNSUPPORTED_STATEMENT
  (T9 首词分路只看词、不看后续形态)——错误码与 Java 反向。
- **根因**:首词 12 词分路先于文法形态判定。
- **处置**:T11 落地 `firstWordTailShape` 尾形护栏:`COMMIT x`/`BEGIN FOO`/
  `DISCARD x` → PARSE_ERROR;裸词/WORK/TRANSACTION/ALL → UNSUPPORTED。
  契约 110 条内含 8 条新 parse 判定锁定。

### D17. 认识但未实现的首词语句 —— 处置:修复(Task 9 裁定)+ 契约记录

- **输入形态**:`UPDATE t SET a=1`、`DELETE FROM t`、`MERGE`、`TABLE t`、
  `SET x=1`、`DESCRIBE t`、`CALL p(1)`;对照组 `GRANT`/`EXPLAIN`/`ALTER`
- **Java 行为**(jar 实测):前者解析到 classify 报 UNSUPPORTED_STATEMENT;
  后者 fork 文法即拒(PARSE_ERROR)。
- **Go 初始行为**:简报决策为统一 PARSE_ERROR——与 Java 前半族错误码反向。
- **根因**:简报"认识但不实现"未区分 Java 的两段行为(解析 ok→classify 拒)。
- **处置**:Task 9 裁定镜像 Java 两段:首词在 javaCCKeyword 表内(729 词
  机械提取)→ UNSUPPORTED_STATEMENT(折算 classify,契约口径与
  engine.Classify 一致);表外首词 → PARSE_ERROR。语料实含 UPDATE/DELETE
  各 1 条,契约锁定。DESCRIBE 的 kind 命名差异留档(语料零命中)。

### D18. CTAS 列清单与变体检查回位 —— 处置:修复(Task 9 裁定)

- **输入形态**:`CREATE TABLE t (a INT) AS SELECT 1` / `(a, b)` 拒;
  `CREATE OR REPLACE TABLE t AS SELECT 1`(变体)
- **Java 行为**(jar 实测):CTAS 列清单必须带类型(`(a INT)` 接受、
  `(a,b)` 拒);变体检查在 composeWriteStatement(validate 之后),
  classify 阶段放行变体 CTAS。
- **Go 初始行为**:`(a,b)` 接受、`(a INT)` 拒(恰反向);变体检查误前置到
  Classify。
- **根因**:简报未钉死列清单类型化;变体检查的 Java 落点在 compose。
- **处置**:CTAS 列清单改为「名 类型」对(AST 孓名弃类型);变体检查回位
  compose——新增 `engine.CheckCreateTableVariantForCompose` 导出钩子供 M3,
  Classify 不再前置拒(最终错误码不变)。`CREATE TABLE IF NOT EXISTS` 解析
  记录 `ast.CreateTable.IfNotExists`(jar 实测接受)。CTAS 垃圾类型跳读
  (Java 放行、Go 拒)留档,语料零命中。
