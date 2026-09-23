#!/usr/bin/env bash
# 主机恢复后的第一动作：停服务 → 套调优单元 → 错峰拉起 → 验证
set -e
echo "=== 1. stop all mask services ==="
systemctl stop sqlmask-query sqlmask-core sqlmask-metadata sqlmask-policy 2>/dev/null || true
sleep 2
echo "=== 2. apply tuned units ==="
# 基线 core 的 /api/rewrite/instances/* 需要 metadata/policy 服务地址
grep -q SQLMASK_METADATA_BASE_URL /opt/sqlmask/env/core.env || cat >> /opt/sqlmask/env/core.env <<'ENVEOF'
SQLMASK_METADATA_BASE_URL=http://127.0.0.1:8082
SQLMASK_METADATA_API_KEY=local-dev-key
SQLMASK_POLICY_BASE_URL=http://127.0.0.1:8081
SQLMASK_POLICY_API_KEY=local-data-key
ENVEOF
bash /tmp/tune-systemd.sh
echo "=== 3. start policy + metadata ==="
systemctl start sqlmask-policy sqlmask-metadata
sleep 50
echo "=== 4. start core ==="
systemctl start sqlmask-core
sleep 40
echo "=== 5. start query ==="
systemctl start sqlmask-query
sleep 60
echo "=== 6. health ==="
for p in 8080 8081 8082 8083; do printf "port %s: " $p; curl -s --max-time 8 http://127.0.0.1:$p/actuator/health 2>/dev/null | head -c 40; echo; done
systemctl is-active sqlmask-core sqlmask-policy sqlmask-metadata sqlmask-query
free -h
echo "=== 7. run e2e verify ==="
bash /tmp/e2e-verify.sh 2>&1
