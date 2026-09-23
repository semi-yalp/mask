#!/usr/bin/env bash
# 单会话内完成：等服务启动 → 全链路配置 → 端到端验证
# （WSL VM 会在最后一个会话结束后关闭，一切必须在同一会话内完成）
systemctl enable -q sqlmask-core sqlmask-policy sqlmask-metadata sqlmask-query 2>/dev/null
systemctl start sqlmask-policy sqlmask-metadata sqlmask-core sqlmask-query

echo "== waiting for services =="
for i in $(seq 1 60); do
  ok=1
  for p in 8080 8081 8082; do
    code=$(curl -s --max-time 3 -o /dev/null -w "%{http_code}" http://127.0.0.1:$p/actuator/health 2>/dev/null)
    [ "$code" != "200" ] && ok=0
  done
  [ "$ok" = "1" ] && break
  sleep 5
done
echo "waited $((i*5))s; health:"
for p in 8080 8081 8082 8083; do printf "%s:" $p; curl -s --max-time 5 -o /dev/null -w "%{http_code}" http://127.0.0.1:$p/actuator/health 2>/dev/null; echo; done

echo "== configure =="
bash /opt/sqlmask/local-full-e2e.sh 2>&1 | sed -n '1,12p'
