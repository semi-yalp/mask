#!/usr/bin/env bash
# summarize.sh — 汇总 results/raw → results/matrix.tsv + results/summary.txt
# 列: qid engine mode code ms maskHits rfCust rfAddr rfDate errcode changed
#   maskHits  输出中脱敏 UDF 调用次数
#   rfCust/rfAddr/rfDate 输出中三条行过滤谓词出现次数（原始查询不含这些谓词）
#   changed   产物相对输入是否有结构变化（脱敏包装/注入/规范化）
set -u
BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$BENCH_DIR"
OUT=results/matrix.tsv
echo -e "qid\tengine\tmode\tcode\tms\tmaskHits\trfCust\trfAddr\trfDate\terrcode\tchanged" > "$OUT"

for engine in lite core; do
  for mode in mask rowfilter both; do
    for cf in results/raw/$engine/$mode/q*.code; do
      [ -e "$cf" ] || continue
      q=$(basename "$cf" .code)
      code=$(cat "$cf")
      ms=$(cat "results/raw/$engine/$mode/$q.ms" 2>/dev/null || echo 0)
      outf="results/raw/$engine/$mode/$q.out"
      errf="results/raw/$engine/$mode/$q.err"
      maskHits=$(grep -o "mask_hash(\|mask_name(\|mask_email(\|mask_phone(\|mask_text(" "$outf" 2>/dev/null | wc -l)
      rfCust=$(grep -o "c_birth_year >= 1930" "$outf" 2>/dev/null | wc -l)
      rfAddr=$(grep -o "ca_country = 'United States'" "$outf" 2>/dev/null | wc -l)
      rfDate=$(grep -o "d_year <= 2002" "$outf" 2>/dev/null | wc -l)
      errcode=""
      [ "$code" != "0" ] && errcode=$(grep -ohE "CONFIG_ERROR|PARSE_ERROR|VALIDATION_ERROR|UNSUPPORTED_STATEMENT|LINEAGE_UNKNOWN|REWRITE_ERROR|IO_ERROR|INTERNAL_ERROR" "$errf" 2>/dev/null | head -1)
      # changed: 归一化（去空白、统一大写、去尾分号）后比对
      if [ "$code" = "0" ]; then
        a=$(tr -d ' \t\r\n' < "corpus/queries/$q.sql" | tr 'a-z' 'A-Z' | sed 's/;$//')
        b=$(tr -d ' \t\r\n' < "$outf" | tr 'a-z' 'A-Z' | sed 's/;$//')
        if [ "$a" = "$b" ]; then changed=0; else changed=1; fi
      else
        changed=-1
      fi
      echo -e "$q\t$engine\t$mode\t$code\t$ms\t$maskHits\t$rfCust\t$rfAddr\t$rfDate\t$errcode\t$changed" >> "$OUT"
    done
  done
done

# 汇总
{
  echo "=== 按 引擎×模式 汇总（ms 为串行冷启动墙钟,含 JVM 启动） ==="
  printf "%-6s %-10s %4s %4s %7s %9s %8s %9s %7s %9s\n" engine mode ok err masked rfInj avgMs p95Ms changed sameOut
  for engine in lite core; do
    for mode in mask rowfilter both; do
      ok=$(awk -v e=$engine -v m=$mode -F'\t' '$2==e&&$3==m&&$4==0' "$OUT" | wc -l)
      err=$(awk -v e=$engine -v m=$mode -F'\t' '$2==e&&$3==m&&$4!=0' "$OUT" | wc -l)
      masked=$(awk -v e=$engine -v m=$mode -F'\t' '$2==e&&$3==m&&$4==0&&$6>0' "$OUT" | wc -l)
      rfinj=$(awk -v e=$engine -v m=$mode -F'\t' '$2==e&&$3==m&&$4==0&&($7+$8+$9)>0' "$OUT" | wc -l)
      avg=$(awk -v e=$engine -v m=$mode -F'\t' '$2==e&&$3==m{t+=$5;n++}END{if(n)printf"%d",t/n;else print 0}' "$OUT")
      p95=$(awk -v e=$engine -v m=$mode -F'\t' '$2==e&&$3==m{v[++n]=$5}END{asort(v);if(n)printf"%d",v[int(n*0.95)];else print 0}' "$OUT")
      chg=$(awk -v e=$engine -v m=$mode -F'\t' '$2==e&&$3==m&&$11==1' "$OUT" | wc -l)
      same=$(awk -v e=$engine -v m=$mode -F'\t' '$2==e&&$3==m&&$11==0' "$OUT" | wc -l)
      printf "%-6s %-10s %4d %4d %7d %9d %8d %9d %7d %9d\n" "$engine" "$mode" "$ok" "$err" "$masked" "$rfinj" "$avg" "$p95" "$chg" "$same"
    done
  done
  echo
  echo "=== 错误码分布（引擎×模式×错误码 → 查询数） ==="
  awk -F'\t' '$4!=0{print $2"\t"$3"\t"$10}' "$OUT" | sort | uniq -c | sort -rn
  echo
  echo "=== 失败查询清单 ==="
  awk -F'\t' '$4!=0{print $1"\t"$2"\t"$3"\t"$10}' "$OUT" | sort | uniq | sort -t$'\t' -k1,1V
} > results/summary.txt
cat results/summary.txt
