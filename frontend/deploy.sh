#!/usr/bin/env bash
# sql-mask 前端远程构建/部署脚本。
# 本机不需要 node:源码经 tar 管道同步到远程构建主机,构建/测试/部署都在远端执行。
# 远端目录布局镜像仓库根:$ROOT/frontend 是构建工作区(含 node_modules/dist),
# $ROOT/docker-compose.frontend.yml 由 up 子命令从本仓库复制过去。
#
# 用法:
#   bash deploy.sh sync                 # 只同步源码
#   bash deploy.sh sync <cmd...>        # 同步后在远端执行命令,如: sync npm test
#   bash deploy.sh build                # 同步 + npm install + 构建
#   bash deploy.sh test                 # 同步 + npm install + 单测
#   bash deploy.sh up                   # 远端 docker compose 启动/更新 nginx(端口 80)
#
# 环境变量: DEPLOY_HOST(必填,如 root@example.com)、DEPLOY_ROOT(默认 ~/code/mask)
set -euo pipefail

if [ -z "${DEPLOY_HOST:-}" ]; then
  echo "DEPLOY_HOST 未设置:部署主机不再内置默认值,请显式指定(如 DEPLOY_HOST=root@example.com)" >&2
  exit 2
fi
HOST="$DEPLOY_HOST"
ROOT="${DEPLOY_ROOT:-~/code/mask}"
DEST="$ROOT/frontend"
cd "$(dirname "$0")"

sync_src() {
  # 远端只保留 node_modules 与 dist,其余清空后解包,保证删除语义
  ssh "$HOST" "mkdir -p $DEST && find $DEST -mindepth 1 -maxdepth 1 ! -name node_modules ! -name dist -exec rm -rf {} +"
  tar -czf - --exclude='node_modules' --exclude='dist' --exclude='.vite' . | ssh "$HOST" "tar -xzf - -C $DEST"
  echo "synced -> $HOST:$DEST"
}

case "${1:-sync}" in
  sync)
    shift || true
    sync_src
    [ $# -gt 0 ] && ssh "$HOST" "cd $DEST && $*"
    ;;
  build)
    sync_src
    ssh "$HOST" "cd $DEST && npm install --no-audit --no-fund && npm run build"
    ;;
  test)
    sync_src
    ssh "$HOST" "cd $DEST && npm install --no-audit --no-fund && npm test"
    ;;
  up)
    sync_src
    ssh "$HOST" "mkdir -p $ROOT/docker"
    scp -q ../docker-compose.frontend.yml "$HOST:$ROOT/"
    scp -q ../docker/nginx.Dockerfile "$HOST:$ROOT/docker/"
    scp -q nginx.conf.docker "$HOST:$DEST/"
    if ssh "$HOST" "docker compose version >/dev/null 2>&1"; then
      ssh "$HOST" "cd $ROOT && docker compose -f docker-compose.frontend.yml up -d --build --force-recreate"
    else
      # 无 compose 插件:等价的 docker build + docker run(bridge + host-gateway)
      ssh "$HOST" "cd $ROOT && docker build -f docker/nginx.Dockerfile -t sql-mask-frontend . && \
        docker rm -f sql-mask-frontend >/dev/null 2>&1 || true && \
        docker run -d --name sql-mask-frontend --restart unless-stopped \
          -p 80:80 --add-host host.docker.internal:host-gateway sql-mask-frontend"
    fi
    echo "frontend: http://$HOST/"
    ;;
  *)
    echo "unknown command: $1" >&2; exit 2
    ;;
esac
