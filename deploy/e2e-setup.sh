#!/usr/bin/env bash
# 端到端演示配置：在策略服务重签 UDF 并创建 5 条策略
set -e
PS=http://127.0.0.1:8081/api/instances/crm_pg
H1='X-Api-Key: local-admin-key'
CT='Content-Type: application/json'
T=/tmp/sqlmask-e2e.json

put_udf() { printf '%s' "$2" > "$T"; curl -s -X PUT "$PS/udfs/$1" -H "$H1" -H "$CT" --data @"$T"; echo; }
post_pol() { printf '%s' "$2" > "$T"; curl -s -X POST "$PS/policies" -H "$H1" -H "$CT" --data @"$T" | head -c 200; echo; }

put_udf mask_phone  '{"name":"mask_phone","signatures":[{"params":["varchar(20)","integer","integer"],"returns":"varchar"},{"params":["bigint","integer","integer"],"returns":"varchar"}]}'
put_udf mask_email  '{"name":"mask_email","signatures":[{"params":["varchar(100)"],"returns":"varchar"}]}'
put_udf mask_name   '{"name":"mask_name","signatures":[{"params":["varchar(50)"],"returns":"varchar"}]}'
put_udf mask_idcard '{"name":"mask_idcard","signatures":[{"params":["varchar(18)","integer"],"returns":"varchar"}]}'

post_pol mask-customer-phone  '{"name":"mask-customer-phone","policyType":"DATAMASK","isEnabled":true,"priority":0,"resource":{"catalog":"crm","schema":"public","table":"customer","columns":["phone"]},"subjects":{"users":[],"groups":["*"]},"udf":"mask_phone","arguments":[3,4]}'
post_pol mask-customer-email  '{"name":"mask-customer-email","policyType":"DATAMASK","isEnabled":true,"priority":0,"resource":{"catalog":"crm","schema":"public","table":"customer","columns":["email"]},"subjects":{"users":[],"groups":["*"]},"udf":"mask_email","arguments":[]}'
post_pol mask-customer-idcard '{"name":"mask-customer-idcard","policyType":"DATAMASK","isEnabled":true,"priority":0,"resource":{"catalog":"crm","schema":"public","table":"customer","columns":["id_card"]},"subjects":{"users":[],"groups":["*"]},"udf":"mask_idcard","arguments":[4]}'
post_pol mask-customer-name   '{"name":"mask-customer-name","policyType":"DATAMASK","isEnabled":true,"priority":0,"resource":{"catalog":"crm","schema":"public","table":"customer","columns":["name"]},"subjects":{"users":[],"groups":["*"]},"udf":"mask_name","arguments":[]}'
post_pol analyst-north-only   '{"name":"analyst-north-only","policyType":"ROW_FILTER","isEnabled":true,"priority":0,"resource":{"catalog":"crm","schema":"public","table":"orders"},"subjects":{"users":[],"groups":["analysts"]},"filterExpr":"region = '"'"'north'"'"'"}'

echo '=== policies list ==='
curl -s "$PS/policies" -H "$H1" | python3 -c 'import json,sys; [print("-", p["name"], p["policyType"], "enabled" if p["isEnabled"] else "disabled") for p in json.load(sys.stdin)]'
