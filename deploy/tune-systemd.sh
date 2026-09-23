#!/usr/bin/env bash
# 内存调优后的 systemd 单元：上限贴合真实用量，避免 cgroup 回收风暴
set -e
FLAGS_COMMON="-XX:+UseSerialGC -Xss512k -XX:-TieredCompilation -XX:ReservedCodeCacheSize=64m"
mkunit() {
cat > /etc/systemd/system/sqlmask-$1.service <<EOF
[Unit]
Description=sql-mask $1 service ($2)
After=network-online.target docker.service $4
Wants=network-online.target

[Service]
EnvironmentFile=/opt/sqlmask/env/$1.env
ExecStart=/usr/bin/java -Xms48m -Xmx$5 -XX:MaxMetaspaceSize=$6 $FLAGS_COMMON -jar /opt/sqlmask/$3
Restart=on-failure
RestartSec=10
SuccessExitStatus=143
MemoryMax=$7

[Install]
WantedBy=multi-user.target
EOF
}
mkunit core     "rewrite+CLI 8080"   sql-mask.jar         ""                160m 128m 400M
mkunit policy   "policy server 8081" policy-server.jar    ""                160m 128m 400M
mkunit metadata "metadata 8082"      metadata-server.jar  ""                160m 128m 400M
mkunit query    "query 8083"         query-server.jar     "sqlmask-core.service sqlmask-metadata.service" 288m 224m 800M
systemctl daemon-reload
sysctl -qw vm.swappiness=15
pkill -f 'bash /tmp/e2e-verify.sh' 2>/dev/null || true
pkill -f 'curl.*api/v1/query' 2>/dev/null || true
echo units-updated
free -h | head -2
ps aux --sort=-rss | head -8
