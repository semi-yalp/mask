#!/usr/bin/env bash
# WSL 内用 systemd 跑四个服务（会话回收免疫）
set -e
mkdir -p /opt/sqlmask/env /opt/sqlmask/logs

cat > /opt/sqlmask/env/core.env <<'EOF'
POLICY_SERVICE_URL=http://127.0.0.1:8081
POLICY_SERVICE_API_KEY=local-data-key
SQLMASK_REWRITE_API_KEY=local-rewrite-key
AUDIT_ENABLED=false
MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED=false
SQLMASK_METADATA_BASE_URL=http://127.0.0.1:8082
SQLMASK_METADATA_API_KEY=local-dev-key
SQLMASK_POLICY_BASE_URL=http://127.0.0.1:8081
SQLMASK_POLICY_API_KEY=local-data-key
EOF

cat > /opt/sqlmask/env/policy.env <<'EOF'
POLICY_PG_URL=jdbc:postgresql://127.0.0.1:5432/mask_policy
POLICY_PG_USER=postgres
POLICY_PG_PASSWORD=PgTest2026
SQLMASK_ADMIN_API_KEY=local-admin-key
SQLMASK_DATA_API_KEY=local-data-key
AUDIT_ENABLED=false
MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED=false
EOF

cat > /opt/sqlmask/env/metadata.env <<'EOF'
METADATA_PG_URL=jdbc:postgresql://127.0.0.1:5432/mask_metadata
METADATA_PG_USER=postgres
METADATA_PG_PASSWORD=PgTest2026
METADATA_API_KEY=local-dev-key
AUDIT_ENABLED=false
MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED=false
SQLMASK_DS_CRM_PASSWORD=PgTest2026
EOF

cat > /opt/sqlmask/env/query.env <<'EOF'
SQLMASK_QUERY_API_KEY=local-query-key
SQLMASK_METADATA_BASE_URL=http://127.0.0.1:8082
SQLMASK_METADATA_API_KEY=local-dev-key
SQLMASK_REWRITE_BASE_URL=http://127.0.0.1:8080
SQLMASK_REWRITE_API_KEY=local-rewrite-key
SQLMASK_DS_CRM_PASSWORD=PgTest2026
EOF

mkunit() {
cat > /etc/systemd/system/sqlmask-$1.service <<EOF
[Unit]
Description=sql-mask $1 (WSL local)
After=network.target postgresql.service

[Service]
EnvironmentFile=/opt/sqlmask/env/$1.env
ExecStart=/usr/bin/java -Xms48m -Xmx$3 -jar /opt/sqlmask/$2
Restart=on-failure
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF
}
mkunit core     sql-mask.jar        192m
mkunit policy   policy-server.jar   192m
mkunit metadata metadata-server.jar 192m
mkunit query    query-server.jar    256m
systemctl daemon-reload
systemctl enable --now sqlmask-policy sqlmask-metadata sqlmask-core sqlmask-query 2>/dev/null || systemctl start sqlmask-policy sqlmask-metadata sqlmask-core sqlmask-query
echo all-started
