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
# 环境变量: DEPLOY_HOST(默认 root@47.100.166.158)、DEPLOY_ROOT(默认 ~/code/mask)
set -euo pipefail

HOST="${DEPLOY_HOST:-root@47.100.166.158}"
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
    ssh "$HOST" "mkdir -p $ROOT"
    scp -q ../docker-compose.frontend.yml ../docker/nginx.Dockerfile "$HOST:$ROOT/" 2>/dev/null || true
    ssh "$HOST" "cd $ROOT && docker compose -f docker-compose.frontend.yml up -d --force-recreate"
    echo "frontend: http://$HOST/"
    ;;
  *)
    echo "unknown command: $1" >&2; exit 2
    ;;
esac
