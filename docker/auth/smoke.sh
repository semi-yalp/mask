#!/usr/bin/env bash
# sql-mask LDAP 认证授权端到端冒烟脚本。
#
# 对一个已运行的、启用了 LDAP 登录的 mask-policy-server 执行断言套件
# （登录 → 角色门禁 → 数据面主体绑定），全部通过退出码 0，任何一条
# 失败即非零退出。演示目录与启动方法见 README「LDAP 用户认证」章节，
# 演示用户：amy(ADMIN)/bob(AUDITOR)/carol(USER)，密码均为 <名字>-secret。
#
# 用法：
#   BASE_URL=http://127.0.0.1:8081 ./docker/auth/smoke.sh
#   BASE_URL=http://127.0.0.1:8081 ADMIN_PASS='amy-secret' ./docker/auth/smoke.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
ADMIN_USER="${ADMIN_USER:-amy}"
ADMIN_PASS="${ADMIN_PASS:-amy-secret}"
USER_USER="${USER_USER:-carol}"
USER_PASS="${USER_PASS:-carol-secret}"
INSTANCE="smoke_$(date +%s)"

fail() { echo "FAIL: $1"; exit 1; }
pass() { echo "PASS: $1"; }

token_of() {
  curl -s -m 10 -X POST "$BASE_URL/api/auth/login" -H "Content-Type: application/json" \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" |
    sed -n 's/.*"token":"\([^"]*\)".*/\1/p'
}
code_of() { curl -s -m 10 -o /dev/null -w "%{http_code}" "$@"; }

command -v curl >/dev/null || fail "curl is required"

# ---- 0. LDAP 登录已启用 ----
[ "$(curl -s -m 10 "$BASE_URL/api/auth/mode" | grep -o '"ldap":true')" ] ||
  fail "/api/auth/mode did not report ldap=true (is MASK_AUTH_SECRET + MASK_AUTH_LDAP_* configured?)"
pass "mode reports ldap=true"

# ---- 1. 登录与角色映射 ----
login_body() {
  curl -s -m 10 -X POST "$BASE_URL/api/auth/login" -H "Content-Type: application/json" \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}"
}
ADMIN_RAW=$(login_body "$ADMIN_USER" "$ADMIN_PASS")
USER_RAW=$(login_body "$USER_USER" "$USER_PASS")
ADMIN=$(sed -n 's/.*"token":"\([^"]*\)".*/\1/p' <<<"$ADMIN_RAW")
USER=$(sed -n 's/.*"token":"\([^"]*\)".*/\1/p' <<<"$USER_RAW")
[ -n "$ADMIN" ] || fail "admin login"
[ -n "$USER" ]  || fail "user login"
echo "$ADMIN_RAW" | grep -q '"role":"ADMIN"' || fail "admin token role is not ADMIN"
echo "$USER_RAW" | grep -q '"role":"USER"'   || fail "user token role is not USER"
pass "login issues role-bearing tokens for admin and user"

# ---- 2. 失败路径不泄露用户存在性 ----
WRONG=$(curl -s -m 10 -X POST "$BASE_URL/api/auth/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$USER_USER\",\"password\":\"definitely-wrong\"}")
GHOST=$(curl -s -m 10 -X POST "$BASE_URL/api/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"no-such-user-xyz","password":"x"}')
[ -n "$WRONG" ] && [ "$WRONG" = "$GHOST" ] || fail "wrong-password and unknown-user differ"
pass "wrong password == unknown user (identical 401 body)"

# ---- 3. 角色门禁：读放行、写限 ADMIN、坏令牌 401、无令牌走旧路径 ----
[ "$(code_of -H "Authorization: Bearer $USER" "$BASE_URL/api/instances")" = 200 ] ||
  fail "user token cannot read instances"
pass "logged-in user can read the admin surface"
WRITE_BODY='{"name":"'"$INSTANCE"'","dialect":"postgresql","tables":[{"catalog":"crm","schema":"public","name":"customer","columns":[{"name":"phone","type":"varchar"}]}]}'
[ "$(code_of -X POST -H "Authorization: Bearer $USER" -H "Content-Type: application/json" -d "$WRITE_BODY" "$BASE_URL/api/instances")" = 403 ] ||
  fail "user token was allowed to write instances"
pass "plain user write is 403 FORBIDDEN"
[ "$(code_of -X POST -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" -d "$WRITE_BODY" "$BASE_URL/api/instances")" = 200 ] ||
  fail "admin token could not create instance"
pass "admin can create the smoke instance"
[ "$(code_of -H "Authorization: Bearer forged.token.value" "$BASE_URL/api/instances")" = 401 ] ||
  fail "forged token was not rejected with 401"
pass "forged token rejected without falling back to API-key path"

# ---- 4. 数据面主体绑定：具名策略只对令牌身份生效，自报参数被忽略 ----
curl -s -m 10 -o /dev/null -X POST -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{"name":"mask_phone","signatures":[{"params":["varchar","integer","integer"],"returns":"varchar"}]}' \
  "$BASE_URL/api/instances/$INSTANCE/udfs" || fail "udf registration"
curl -s -m 10 -o /dev/null -X POST -H "Authorization: Bearer $ADMIN" -H "Content-Type: application/json" \
  -d '{"name":"smoke_phone_mask","policyType":"datamask","isEnabled":true,"resource":{"catalog":"crm","schema":"public","table":"customer","columns":["phone"]},"subjects":{"users":["'"$USER_USER"'"],"groups":[]},"udf":"mask_phone","arguments":[3,4]}' \
  "$BASE_URL/api/instances/$INSTANCE/policies" || fail "policy creation"
AS_USER=$(curl -s -m 10 -H "Authorization: Bearer $USER" "$BASE_URL/api/effective/$INSTANCE")
echo "$AS_USER" | grep -q smoke_phone_mask || fail "user's effective config misses their named policy"
pass "user's effective config contains their named policy"
AS_ADMIN=$(curl -s -m 10 -H "Authorization: Bearer $ADMIN" "$BASE_URL/api/effective/$INSTANCE")
echo "$AS_ADMIN" | grep -q smoke_phone_mask && fail "subject leak: admin sees user's named policy"
pass "other users' effective configs exclude the named policy"
SPOOFED=$(curl -s -m 10 -H "Authorization: Bearer $USER" "$BASE_URL/api/effective/$INSTANCE?user=root&groups=wheel")
echo "$SPOOFED" | grep -q smoke_phone_mask || fail "caller-asserted ?user= was not overridden by the token"
pass "caller-asserted user/groups are ignored (token identity wins)"

echo "smoke OK: all LDAP auth assertions passed against $BASE_URL"
