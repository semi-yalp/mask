#!/usr/bin/env bash
# 本地 WSL 栈启动脚本：PG(WSL 内) + 四服务，全部 127.0.0.1
set -e
D=/opt/sqlmask
PG_URL=jdbc:postgresql://127.0.0.1:5432

# policy-server
POLICY_PG_URL=$PG_URL/mask_policy POLICY_PG_USER=postgres POLICY_PG_PASSWORD=PgTest2026 \
SQLMASK_ADMIN_API_KEY=local-admin-key SQLMASK_DATA_API_KEY=local-data-key \
AUDIT_ENABLED=false MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED=false \
nohup java -Xms48m -Xmx192m -jar $D/policy-server.jar > $D/logs/policy.log 2>&1 &

# mask-metadata
METADATA_PG_URL=$PG_URL/mask_metadata METADATA_PG_USER=postgres METADATA_PG_PASSWORD=PgTest2026 \
METADATA_API_KEY=local-dev-key AUDIT_ENABLED=false MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED=false \
SQLMASK_DS_CRM_PASSWORD=PgTest2026 \
nohup java -Xms48m -Xmx192m -jar $D/metadata-server.jar > $D/logs/metadata.log 2>&1 &

sleep 40

# mask-core
POLICY_SERVICE_URL=http://127.0.0.1:8081 POLICY_SERVICE_API_KEY=local-data-key \
SQLMASK_REWRITE_API_KEY=local-rewrite-key AUDIT_ENABLED=false \
MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED=false \
nohup java -Xms48m -Xmx192m -jar $D/sql-mask.jar > $D/logs/core.log 2>&1 &

# mask-query
SQLMASK_QUERY_API_KEY=local-query-key \
SQLMASK_METADATA_BASE_URL=http://127.0.0.1:8082 SQLMASK_METADATA_API_KEY=local-dev-key \
SQLMASK_REWRITE_BASE_URL=http://127.0.0.1:8080 SQLMASK_REWRITE_API_KEY=local-rewrite-key \
SQLMASK_DS_CRM_PASSWORD=PgTest2026 \
nohup java -Xms48m -Xmx256m -jar $D/query-server.jar > $D/logs/query.log 2>&1 &

sleep 60
for p in 8080 8081 8082 8083; do printf "port %s: " $p; curl -s --max-time 5 http://127.0.0.1:$p/actuator/health 2>/dev/null || printf "(no actuator)"; echo; done
tail -2 $D/logs/query.log | head -2
free -h | head -2
