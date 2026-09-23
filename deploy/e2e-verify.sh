#!/usr/bin/env bash
# 端到端验证：生效配置 → 改写 → 真库查询 → CLI
set -e
DK='X-Api-Key: local-data-key'
CT='Content-Type: application/json'

E=http://127.0.0.1:8081/api/effective/crm_pg
show() {
  echo "=== $1 ==="
  curl -s --max-time 60 "$E$2" -H "$DK" | python3 -c '
import json,sys
d=json.load(sys.stdin); c=d["config"]
print("configVersion:", d["configVersion"], "| summary:", d["policySummary"])
print("masked columns:", [(x["column"], x["policy"]) for x in c["columns"]])
print("rowFilters:", [(t["name"], t["rowFilter"]) for t in c["metadata"]["tables"] if t.get("rowFilter")])
'
}
show "alice (devs)"   "?user=alice&groups=devs"
show "bob (analysts)" "?user=bob&groups=analysts"
show "anonymous"      ""

echo "=== core rewrite (instance mode, alice) ==="
printf '%s' '{"instance":"crm_pg","sql":"SELECT name, phone, email, id_card FROM customer WHERE id <= 3 ORDER BY id","user":"alice","groups":["devs"]}' > /tmp/sqlmask-rw.json
curl -s --max-time 60 http://127.0.0.1:8080/api/rewrite -H "$CT" --data @/tmp/sqlmask-rw.json | python3 -m json.tool | head -20

echo "=== mask-query: alice on customer ==="
printf '%s' '{"instance":"crm_pg","sql":"SELECT name, phone, email, id_card FROM customer WHERE id <= 3 ORDER BY id","user":"alice","groups":["devs"],"includeRewrittenSql":true}' > /tmp/sqlmask-q1.json
curl -s --max-time 90 http://127.0.0.1:8083/api/v1/query -H "$CT" -H "X-Api-Key: local-query-key" --data @/tmp/sqlmask-q1.json | python3 -m json.tool

echo "=== mask-query: bob (analysts) on orders -> rowFiltered ==="
printf '%s' '{"instance":"crm_pg","sql":"SELECT o.id, c.name, c.phone, o.region, o.amount FROM orders o JOIN customer c ON c.id = o.customer_id ORDER BY o.id","user":"bob","groups":["analysts"]}' > /tmp/sqlmask-q2.json
curl -s --max-time 90 http://127.0.0.1:8083/api/v1/query -H "X-Api-Key: local-query-key" -H "$CT" --data @/tmp/sqlmask-q2.json | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("masked:", d["masked"], "| rowFiltered:", d["rowFiltered"], "| rowCount:", d["rowCount"], "| truncated:", d["truncated"], "| elapsedMs:", d["elapsedMs"])
print("columns:", [c["name"] for c in d["columns"]])
for r in d["rows"][:5]: print(r)
'

echo "=== mask-query: alice (devs) same join -> no row filter ==="
printf '%s' '{"instance":"crm_pg","sql":"SELECT o.id, c.name, c.phone, o.region, o.amount FROM orders o JOIN customer c ON c.id = o.customer_id ORDER BY o.id","user":"alice","groups":["devs"]}' > /tmp/sqlmask-q3.json
curl -s --max-time 90 http://127.0.0.1:8083/api/v1/query -H "X-Api-Key: local-query-key" -H "$CT" --data @/tmp/sqlmask-q3.json | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("masked:", d["masked"], "| rowFiltered:", d["rowFiltered"], "| rowCount:", d["rowCount"])
'

echo "=== CLI: instance mode rewrite (bob) ==="
POLICY_SERVICE_API_KEY=local-data-key java -jar /opt/sqlmask/sql-mask.jar --instance crm_pg \
  --policy-service http://127.0.0.1:8081 --user bob --groups analysts \
  --sql "SELECT name, phone FROM customer ORDER BY id LIMIT 3"
