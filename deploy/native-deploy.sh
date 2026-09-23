#!/usr/bin/env bash
# 原生部署：env 文件 + 调优 systemd 单元 + 错峰启动（无 Docker 版）
set -e
D=/opt/sqlmask

cat > $D/env/core.env <<'EOF'
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

cat > $D/env/policy.env <<'EOF'
POLICY_PG_URL=jdbc:postgresql://127.0.0.1:5432/mask_policy
POLICY_PG_USER=postgres
POLICY_PG_PASSWORD=PgTest2026
SQLMASK_ADMIN_API_KEY=local-admin-key
SQLMASK_DATA_API_KEY=local-data-key
AUDIT_ENABLED=false
MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED=false
EOF

cat > $D/env/metadata.env <<'EOF'
METADATA_PG_URL=jdbc:postgresql://127.0.0.1:5432/mask_metadata
METADATA_PG_USER=postgres
METADATA_PG_PASSWORD=PgTest2026
METADATA_API_KEY=local-dev-key
AUDIT_ENABLED=false
MANAGEMENT_HEALTH_ELASTICSEARCH_ENABLED=false
SQLMASK_DS_CRM_PASSWORD=PgTest2026
EOF

cat > $D/env/query.env <<'EOF'
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
Description=sql-mask $1 service ($2)
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
EnvironmentFile=$D/env/$1.env
ExecStart=/usr/bin/java -Xms48m -Xmx$4 -XX:MaxMetaspaceSize=$5 -XX:+UseSerialGC -Xss512k -XX:-TieredCompilation -XX:ReservedCodeCacheSize=64m -jar $D/$3
Restart=on-failure
RestartSec=10
StartLimitBurst=0
SuccessExitStatus=143
MemoryMax=$6

[Install]
WantedBy=multi-user.target
EOF
}
mkunit core     "rewrite+CLI 8080"   sql-mask.jar         160m 128m 400M
mkunit policy   "policy server 8081" policy-server.jar    160m 128m 400M
mkunit metadata "metadata 8082"      metadata-server.jar  160m 128m 400M
mkunit query    "query 8083"         query-server.jar     288m 224m 800M
systemctl daemon-reload

# 前端站点（nginx 原生）
cp $D/nginx.conf /etc/nginx/sites-available/sqlmask.conf
sed -i 's|root /usr/share/nginx/html;|root /opt/sqlmask/frontend/dist;|' /etc/nginx/sites-available/sqlmask.conf
rm -f /etc/nginx/sites-enabled/default
ln -sf /etc/nginx/sites-available/sqlmask.conf /etc/nginx/sites-enabled/sqlmask.conf
nginx -t && systemctl enable --now nginx && systemctl reload nginx

# 错峰启动：policy+metadata → core → query
systemctl enable sqlmask-core sqlmask-policy sqlmask-metadata sqlmask-query
systemctl start sqlmask-policy sqlmask-metadata
sleep 45
systemctl start sqlmask-core
sleep 35
systemctl start sqlmask-query
sleep 50
for p in 8080 8081 8082 8083; do printf "port %s: " $p; curl -s --max-time 6 http://127.0.0.1:$p/actuator/health 2>/dev/null || printf "(no actuator/starting)"; echo; done
systemctl is-active sqlmask-core sqlmask-policy sqlmask-metadata sqlmask-query
free -h | head -2
