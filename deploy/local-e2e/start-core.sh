#!/usr/bin/env bash
# 启动本地 core：setsid 完全脱离 wsl 会话，避免会话拆除时被连带杀掉
setsid nohup env \
  POLICY_SERVICE_URL=http://127.0.0.1:8081 POLICY_SERVICE_API_KEY=local-data-key \
  SQLMASK_REWRITE_API_KEY=local-rewrite-key AUDIT_ENABLED=false MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED=false \
  SQLMASK_METADATA_BASE_URL=http://127.0.0.1:8082 SQLMASK_METADATA_API_KEY=local-dev-key \
  SQLMASK_POLICY_BASE_URL=http://127.0.0.1:8081 SQLMASK_POLICY_API_KEY=local-data-key \
  java -Xms48m -Xmx192m -jar /opt/sqlmask/sql-mask.jar > /opt/sqlmask/logs/core.log 2>&1 &
echo "core launching, pid $!"
sleep 3
pgrep -c java
