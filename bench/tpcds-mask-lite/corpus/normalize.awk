# normalize.awk — 把 dbt/DuckDB 形态的 TPC-DS 查询还原成 vanilla PostgreSQL 方言
#
# 输入: datamindedbe/blog-tpcds-dbt-duckdb 的 dbt model（99 条，随机种子已固定）
# 变换:
#   1. {{ source('external_source', 'tbl') }}  →  tpcds.public.tbl（字符串切分，不用正则）
#   2. 删除同名包装 CTE  tbl AS ( select * from tpcds.public.tbl )
#      仅当 CTE 名 == 表名 且体为 select * 透传（语义等价于直接引用基表）才删，
#      删除后基表引用恢复直接引用，脱敏/行过滤按表名解析才能命中基表；
#      其余 CTE 块（改名、带过滤条件、真业务 CTE）一律原样保留。
#   3. 包装 CTE 全部删光、WITH 子句再无剩余内容时，连同 WITH 一起去掉。
#   4. 兼容 CRLF 与两种排版（紧凑式 / 缩进式）。
# 退出码: 3 = 未发现任何 jinja source 引用（输入可能不是预期形态）
#
# 用法: awk -f normalize.awk tpcds_qNN.sql > qNN.sql

function emit(s) { out[++n] = s; if (!inBody && s !~ /^[ \t]*$/ && s != "WITH") keptCte = 1 }
function flushCand() {
    for (i = 1; i <= candN; i++) emit(cand[i])
    candN = 0
}
function subJinja(line,   a, b, inner, parts, res) {
    res = ""
    while ((a = index(line, "{{")) > 0) {
        b = index(line, "}}")
        if (b == 0) break
        inner = substr(line, a + 2, b - a - 2)
        split(inner, parts, "'")          # parts: " source(", "external_source", ", ", "tbl", ") "
        if (parts[2] != "external_source" || parts[4] == "") {
            res = res substr(line, 1, b + 1)   # 非预期形态原样保留
        } else {
            res = res substr(line, 1, a - 1) "tpcds.public." parts[4]
            jinja++
        }
        line = substr(line, b + 2)
    }
    return res line
}
function headName(line,   s) {   # 从 "[WITH] name AS (" 行提取 name
    s = line
    sub(/^[ \t]*/, "", s)
    sub(/^WITH[ \t]+/, "", s)
    sub(/[ \t]+AS[ \t]*\($/, "", s)
    return s
}

function process(line,   rest, mbody, mtbl) {
    if (candN == 1) {                    # 候选块: 期望透传体
        if (line ~ /^[ \t]*select \* from tpcds\.public\.[a-z_][a-z0-9_]*[ \t]*$/) {
            mbody = line
            sub(/^[ \t]*select \* from tpcds\.public\./, "", mbody)
            sub(/[ \t]*$/, "", mbody)
            if (mbody == candName) { cand[++candN] = line; return }
        }
        flushCand()
        # 落到下方按普通行处理
    } else if (candN == 2) {             # 候选块: 期望收尾 ) 或 ),
        if (line ~ /^[ \t]*\)/) {
            sub(/^[ \t]+/, "", line)
            rest = (substr(line, 2, 1) == ",") ? substr(line, 3) : substr(line, 2)
            sub(/^[ \t]+/, "", rest)
            if (withPending == 1) { emit("WITH"); withPending = 2 }
            dropped++
            candN = 0
            if (rest != "") process(rest)
            return
        }
        flushCand()
    }

    if (line ~ /^[ \t]*WITH[ \t]+[a-z_][a-z0-9_]*[ \t]+AS[ \t]*\($/) {
        candN = 1; cand[1] = line; candName = headName(line); withPending = 1
        return
    }
    if (line ~ /^[ \t]*[a-z_][a-z0-9_]*[ \t]+AS[ \t]*\($/) {
        candN = 1; cand[1] = line; candName = headName(line)
        return
    }
    if (line ~ /^[ \t]*[Ss][Ee][Ll][Ee][Cc][Tt]/) inBody = 1
    emit(line)
}

{
    gsub(/\r$/, "")
    process(subJinja($0))
}

END {
    if (candN > 0) flushCand()
    if (jinja == 0) exit 3
    for (i = 1; i <= n; i++) {
        if (out[i] == "WITH" && withPending == 2 && !keptCte) continue  # 包装全删光，去掉 WITH
        print out[i]
    }
    printf("dropped=%d keptCte=%d jinja=%d\n", dropped, keptCte, jinja) > "/dev/stderr"
}
