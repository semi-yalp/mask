# mask-lite-go 与 Java mask-lite 差分记录

- 日期：2026-09-27
- 方法：Java shaded jar（`.worktrees/mask-lite-pg/mask-lite/target/mask-lite-0.1.0-SNAPSHOT.jar`）
  与 Go CLI 对同一 `tpcds-both.yaml` + 99 条 TPC-DS 查询逐一改写，输出逐文件 diff；
  另以探针 SQL 逐构造校准渲染排版。
- 结论：**99/99 文件逐字节一致**（唯一差异是 JVM `println` 的平台行尾 CRLF vs
  Go 的 LF——平台行为差异，非语义差异）。masked=15 / rowFiltered=91 与 Java
  打印值一致。

## 语料驱动落定的渲染归一（Calcite unparse 语义）

| # | 输入形态 | Calcite 实际渲染 | 说明 |
|---|---|---|---|
| 1 | `LIMIT n` / `LIMIT ALL` | `FETCH NEXT n ROWS ONLY` / 省略 | LIMIT 一律转 FETCH |
| 2 | `OFFSET m`（写不写 ROW/ROWS） | `OFFSET m ROWS` | 恒带 ROWS，且在 FETCH 之前 |
| 3 | `LEFT OUTER JOIN` / `JOIN` | `LEFT JOIN` / `INNER JOIN` | OUTER 省略、INNER 显式 |
| 4 | `x BETWEEN 1 AND 2` | `x BETWEEN ASYMMETRIC 1 AND 2` | 非对称恒显式 |
| 5 | `order by … asc`（窗口内/语句级） | ASC 省略，DESC 保留 | |
| 6 | `substring(x FROM a FOR b)` | `SUBSTRING(x, a, b)` | 归一位置参数 |
| 7 | 小写函数名（std/PG 算子表成员） | 规范大写（`COUNT/COALESCE/ROUND/STDDEV_SAMP/RANK/GROUPING…`） | 未知函数保持原拼写（如 `concat`） |
| 8 | 简单 CASE `CASE op WHEN w THEN` | `CASE WHEN op = w THEN` | 归一为 searched 形态 |
| 9 | `CASE … THEN … END`（无 ELSE） | `… ELSE NULL END` | 缺省 ELSE 归一为 NULL |
| 10 | `SUM(x) OVER (...)` 作除法操作数 | 外层加括号 | 窗口函数操作数优先级 |
| 11 | 双引号小写标识符 `"customer"` | 保留双引号 | 引号标记存活到 unparse |

## 解析期括号判别（TPC-DS 语料全部命中）

- `FROM ((SELECT …) EXCEPT (SELECT …)) cool_cust`（q87）：外层括号内容是
  集合运算——按"深度 0 先见 SELECT/集合运算关键字 → 查询；先 JOIN → JOIN 链"判别。
- `FROM ((a JOIN b) JOIN c)` 归 JOIN 链路径。

## 已记录的已知偏差（均为接受面放宽，不影响语料与用例）

1. **类型系统不做推断**：rowFilter 条件的非布尔性（如 `rowFilter: "1"`）Java 报
   CONFIG_ERROR（`row filter is not a valid condition`），Go 接受（注入后 `WHERE 1`
   语义合法）；GROUP BY 未含列检查（`SELECT status FROM t GROUP BY id`）Java 报
   VALIDATION_ERROR，Go 接受。
2. **词法边界**：`123abc` 的 fork 词法按 customIdentifierToken 视为标识符，Go 词法
   拆为 Number+Ident；裸 `?N`/`$1` 参数支持 `?` 形态。均不在语料与用例内。
3. **UNNEST/LATERAL**：语法面接受；注入期按 fail-closed 规则处理；血缘期按未知
   构造 LINEAGE_UNKNOWN（Java 在校验/元数据层行为不同，语料不覆盖）。
4. **UPDATE/DELETE 等语句**：Java 在 classify 报
   `statement N: unsupported statement kind X; …`；Go 解析层返回 Unsupported 标记、
   由引擎按同族消息拒绝（错误码一致、文案一致）。
5. **CLI 行尾**：Java println 在 Windows 输出 CRLF；Go 恒为 LF。
6. **行内位置**：解析错误行列基于拆分后的语句（与 Java 相同的 trim 语义），
   但具体文案格式（`Encountered "x" at line L, column C; expected …`）非逐字节。

## 验收对照

| 验收项 | Java | Go | 结果 |
|---|---|---|---|
| MaskLiteTest 用例 1（包装+注入子串断言） | 通过 | `TestRewritesWithMaskWrapperAndRowFilterInjection` | ✅ |
| 用例 2（脚本分号计数） | 通过 | `TestRewriteJoinsIntoScript` | ✅ |
| 用例 3（INSERT 拒绝消息） | 通过 | `TestRejectsWriteStatementsFailClosed` | ✅ |
| 用例 4（方言拒绝消息） | 通过 | `TestRejectsUnsupportedDialect` | ✅ |
| 用例 5（行过滤白名单拒绝） | 通过 | `TestRejectsRowFilterReferencingUndeclaredColumn` | ✅ |
| TPC-DS 离线回归 | 99/99，15 masked，91 rowFiltered | `TestTpcdsOfflineRewrite`（硬断言同值） | ✅ |
| 产物逐文件 diff | — | 99/99 identical（除 CRLF） | ✅ |
