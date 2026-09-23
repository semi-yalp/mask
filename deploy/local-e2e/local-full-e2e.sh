#!/usr/bin/env bash
# WSL 本地全链路：配置 + 验证（与远程环境同构）
set -e
CT='Content-Type: application/json'
T=/tmp/lc.json

echo "== 1. metadata instance + collect =="
printf '%s' '{"name":"crm_pg","dialect":"postgresql","connection":{"host":"127.0.0.1","port":5432,"database":"crm","dbUser":"postgres","passwordRef":"SQLMASK_DS_CRM_PASSWORD"}}' > $T
curl -s --max-time 60 http://127.0.0.1:8082/api/instances -H 'X-Api-Key: local-dev-key' -H "$CT" --data @$T | head -c 120; echo
curl -s --max-time 120 -X POST http://127.0.0.1:8082/api/instances/crm_pg/collect -H 'X-Api-Key: local-dev-key'; echo

echo "== 2. policy import =="
printf '%s' '{"metadataBaseUrl":"http://127.0.0.1:8082","metadataApiKey":"local-dev-key","metadataInstance":"crm_pg"}' > $T
curl -s --max-time 60 -X POST http://127.0.0.1:8081/api/instances/crm_pg/import-metadata -H 'X-Api-Key: local-admin-key' -H "$CT" --data @$T | python3 -c 'import json,sys; d=json.load(sys.stdin); print("imported:", d["name"], d["dialect"], len(d["tables"]), "tables")'

echo "== 3. udfs =="
udf() { printf '%s' "$2" > $T; curl -s --max-time 60 http://127.0.0.1:8081/api/instances/crm_pg/udfs -H 'X-Api-Key: local-admin-key' -H "$CT" --data @$T | head -c 80; echo; }
udf mask_phone  '{"name":"mask_phone","signatures":[{"params":["varchar(20)","integer","integer"],"returns":"varchar"},{"params":["bigint","integer","integer"],"returns":"varchar"}]}'
udf mask_email  '{"name":"mask_email","signatures":[{"params":["varchar(100)"],"returns":"varchar"}]}'
udf mask_name   '{"name":"mask_name","signatures":[{"params":["varchar(50)"],"returns":"varchar"}]}'
udf mask_idcard '{"name":"mask_idcard","signatures":[{"params":["varchar(18)","integer"],"returns":"varchar"}]}'

echo "== 4. policies =="
pol() { printf '%s' "$2" > $T; curl -s --max-time 60 http://127.0.0.1:8081/api/instances/crm_pg/policies -H 'X-Api-Key: local-admin-key' -H "$CT" --data @$T | head -c 60; echo; }
pol mask-customer-phone  '{"name":"mask-customer-phone","policyType":"DATAMASK","isEnabled":true,"priority":0,"resource":{"catalog":"crm","schema":"public","table":"customer","columns":["phone"]},"subjects":{"users":[],"groups":["*"]},"udf":"mask_phone","arguments":[3,4]}'
pol mask-customer-email  '{"name":"mask-customer-email","policyType":"DATAMASK","isEnabled":true,"priority":0,"resource":{"catalog":"crm","schema":"public","table":"customer","columns":["email"]},"subjects":{"users":[],"groups":["*"]},"udf":"mask_email","arguments":[]}'
pol mask-customer-idcard '{"name":"mask-customer-idcard","policyType":"DATAMASK","isEnabled":true,"priority":0,"resource":{"catalog":"crm","schema":"public","table":"customer","columns":["id_card"]},"subjects":{"users":[],"groups":["*"]},"udf":"mask_idcard","arguments":[4]}'
pol mask-customer-name   '{"name":"mask-customer-name","policyType":"DATAMASK","isEnabled":true,"priority":0,"resource":{"catalog":"crm","schema":"public","table":"customer","columns":["name"]},"subjects":{"users":[],"groups":["*"]},"udf":"mask_name","arguments":[]}'
pol analyst-north-only   '{"name":"analyst-north-only","policyType":"ROW_FILTER","isEnabled":true,"priority":0,"resource":{"catalog":"crm","schema":"public","table":"orders"},"subjects":{"users":[],"groups":["analysts"]},"filterExpr":"region = '"'"'north'"'"'"}'

echo "== 5. verify =="
bash /opt/sqlmask/e2e-verify.sh
