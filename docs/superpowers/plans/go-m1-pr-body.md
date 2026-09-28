# PR 标题

Go 版 M1(下):表达式链完善、语句层、契约差分护栏与 M1 收口

# PR 正文(创建页粘贴)

完成 Go 版移植 M1 的后半程(spec: docs/superpowers/specs/2026-09-27-go-port-design.md,计划: docs/superpowers/plans/2026-09-27-go-port-m1-parser.md,T1–T5 已随主线)。

## 内容(12 提交)

- **T6 表达式链**:七级优先级链完整落地;`!=` 按 conformance 档拒绝(pg/trino 拒、mysql/hive/sparksql 接受,与 Java 逐字文案);ROLLUP 表达式语境拒绝、GROUP BY 语境接受(jar 实测对齐)
- **T7–T8 查询层**:SELECT/FROM 全覆盖(含 s.t.* 与派生表列改名)、集合运算(INTERSECT 优先)、ORDER BY/LIMIT/OFFSET/FETCH、TOP(n) 五方言拒绝
- **T9 语句层**:WITH/INSERT/CTAS/INSERT OVERWRITE + engine.Classify 逐字镜像 Java(含首词 UNSUPPORTED_STATEMENT 分路、CTAS 类型化列清单、变体检查回位 compose 钩子)
- **T10–T12 差分护栏**:Java CLI runner + 契约文件(110 条:63 Java 语料 + 47 方言/拒绝语料)零 Mismatch,离线 Recorded 门禁 + CONTRACT_LIVE_JAVA=1 实时门禁;jar 实测驱动的 watchlist 清账(前缀串 N'/x'/U&/_charset、<=>&^~、OFFSET..LIMIT 与 LIMIT start,count 的 conformance 门控、首词残片、表名段数无上限)
- **T13 收口**:--parse 调试入口、语料结构快照测试、README-go、逐差异档案 docs/superpowers/plans/m1-differential-notes.md

## 验收

- `go build ./... && go vet ./... && go test ./... -count=1` 9 包全绿(零第三方依赖)
- 契约差分 110/110 与 Java 版解析期判定一致(live 实测 137s)
- 全分支终审(含内联提交深审)+ 修复波次再审 7/7 落实:MERGE-READY

## 已知留档(不阻塞)

jar 接受但语料零命中的构造(FROM LATERAL/UNNEST、GROUPING SETS、无条件 JOIN 等)按 spec 决策 5 兜底条款留档,详见 m1-differential-notes.md;错误文案与 Java 可异(语义等价标准),错误码逐字一致。
