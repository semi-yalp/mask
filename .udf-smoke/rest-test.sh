#!/usr/bin/env bash
# UDF registry REST smoke test against a live sql-mask instance (127.0.0.1:8080).
# Usage: bash rest-test.sh
set -u
BASE=http://127.0.0.1:8080/api/instances
PASS=0; FAIL=0

t() { # t <case-name> <expected-status> <expected-body-substring> <method> <url> [json]
  local name="$1" want_status="$2" want_body="$3" method="$4" url="$5" body="${6-}"
  local out status resp
  if [ -n "$body" ]; then
    resp=$(curl -s -w $'\n%{http_code}' -X "$method" -H 'Content-Type: application/json' -d "$body" "$url")
  else
    resp=$(curl -s -w $'\n%{http_code}' -X "$method" "$url")
  fi
  status=$(echo "$resp" | tail -n1)
  out=$(echo "$resp" | sed '$d')
  local ok=1
  [ "$status" = "$want_status" ] || ok=0
  [ -z "$want_body" ] || echo "$out" | grep -qF "$want_body" || ok=0
  if [ "$ok" = 1 ]; then
    PASS=$((PASS+1)); printf 'PASS  %-42s %s  %s\n' "$name" "$status" "$(echo "$out" | head -c 120)"
  else
    FAIL=$((FAIL+1)); printf 'FAIL  %-42s want=%s got=%s\n      body: %s\n' "$name" "$want_status" "$status" "$(echo "$out" | head -c 300)"
  fi
}

PHONE='{"name":"mask_phone","signatures":[{"params":["varchar","integer","integer"],"returns":"varchar"},{"params":["bigint","integer","integer"],"returns":"varchar"}]}'

echo "===== A. CRUD round trip ====="
t "list empty on fresh instance"          200 "[]"                                  GET  "$BASE/pg_prod/udfs"
t "create mask_phone (2 overloads)"       200 '"name":"mask_phone"'                POST "$BASE/pg_prod/udfs" "$PHONE"
t "get single echoes first overload"      200 '"params":["varchar","integer","integer"]' GET "$BASE/pg_prod/udfs/mask_phone"
t "get single echoes bigint overload"     200 '"params":["bigint","integer","integer"]'  GET "$BASE/pg_prod/udfs/mask_phone"
t "list shows one definition"             200 '"name":"mask_phone"'                GET  "$BASE/pg_prod/udfs"
t "replace shrinks to 1 signature"        200 '"signatures":[{"params":["varchar"],"returns":"varchar"}]' PUT "$BASE/pg_prod/udfs/mask_phone" '{"name":"mask_phone","signatures":[{"params":["varchar"],"returns":"varchar"}]}'
t "delete existing udf"                   200 ""                                   DELETE "$BASE/pg_prod/udfs/mask_phone"
t "get after delete -> CONFIG_ERROR"      400 "CONFIG_ERROR"                       GET  "$BASE/pg_prod/udfs/mask_phone"
t "delete nonexistent -> CONFIG_ERROR"    400 "CONFIG_ERROR"                       DELETE "$BASE/pg_prod/udfs/mask_phone"

echo "===== B. instance-level error contract ====="
t "POST unknown instance"                 400 "POLICY_INSTANCE_NOT_FOUND"          POST "$BASE/nope/udfs" "$PHONE"
t "GET  unknown instance"                 400 "POLICY_INSTANCE_NOT_FOUND"          GET  "$BASE/nope/udfs"
t "DELETE unknown instance"               400 "POLICY_INSTANCE_NOT_FOUND"          DELETE "$BASE/nope/udfs/mask_phone"

echo "===== C. REST-layer input guards ====="
t "create mask_email"                     200 '"name":"mask_email"'                POST "$BASE/pg_prod/udfs" '{"name":"mask_email","signatures":[{"params":["varchar"],"returns":"varchar"}]}'
t "duplicate name rejected"               400 "CONFIG_ERROR"                       POST "$BASE/pg_prod/udfs" '{"name":"mask_email","signatures":[{"params":["varchar"],"returns":"varchar"}]}'
t "PUT missing name in body"              400 "CONFIG_ERROR"                       PUT  "$BASE/pg_prod/udfs/mask_email" '{"signatures":[{"params":["varchar"],"returns":"varchar"}]}'
t "POST signature missing returns"        400 "requires a return type"             POST "$BASE/pg_prod/udfs" '{"name":"bad1","signatures":[{"params":["varchar"]}]}'
t "POST null element in signatures"       400 "must not contain null"              POST "$BASE/pg_prod/udfs" '{"name":"bad2","signatures":[null]}'
t "POST null element in params"           400 "must not contain null"              POST "$BASE/pg_prod/udfs" '{"name":"bad3","signatures":[{"params":["varchar",null],"returns":"varchar"}]}'
t "POST missing name"                     400 "requires a name"                    POST "$BASE/pg_prod/udfs" '{"signatures":[{"params":["varchar"],"returns":"varchar"}]}'
t "PUT unknown udf name"                  400 "CONFIG_ERROR"                       PUT  "$BASE/pg_prod/udfs/never_created" '{"name":"never_created","signatures":[{"params":["varchar"],"returns":"varchar"}]}'

echo "===== D. validator rules (PolicyValidator.validateUdf) ====="
t "illegal type name 'strng'"             400 "invalid type declaration"           POST "$BASE/pg_prod/udfs" '{"name":"bad_type","signatures":[{"params":["strng"],"returns":"varchar"}]}'
t "illegal return type"                   400 "invalid type declaration"           POST "$BASE/pg_prod/udfs" '{"name":"bad_ret","signatures":[{"params":["varchar"],"returns":"txt"}]}'
t "empty signatures list"                 400 "requires at least one signature"    POST "$BASE/pg_prod/udfs" '{"name":"bad_empty","signatures":[]}'
t "empty params (no column param)"        400 "column-value parameter"             POST "$BASE/pg_prod/udfs" '{"name":"bad_noparam","signatures":[{"params":[],"returns":"varchar"}]}'
t "duplicate params in one definition"    400 "duplicate signature"                POST "$BASE/pg_prod/udfs" '{"name":"bad_dup","signatures":[{"params":["varchar"],"returns":"varchar"},{"params":["varchar"],"returns":"text"}]}'
t "name violating NAME regex"             400 "CONFIG_ERROR"                       POST "$BASE/pg_prod/udfs" '{"name":"mask phone!","signatures":[{"params":["varchar"],"returns":"varchar"}]}'
t "numeric(p,s) type accepted"            200 '"params":["numeric(10,2)","integer"]' POST "$BASE/pg_prod/udfs" '{"name":"mask_num","signatures":[{"params":["numeric(10,2)","integer"],"returns":"numeric(10,2)"}]}'
t "mysql type rejected on pg instance"    400 "invalid type declaration"           POST "$BASE/pg_prod/udfs" '{"name":"bad_mysql","signatures":[{"params":["int unsigned"],"returns":"varchar"}]}'

echo "===== E. final state ====="
t "cleanup mask_num"                      200 ""                                  DELETE "$BASE/pg_prod/udfs/mask_num"
t "final list keeps mask_email"           200 '"name":"mask_email"'                GET  "$BASE/pg_prod/udfs"

echo
echo "RESULT: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = 0 ]
