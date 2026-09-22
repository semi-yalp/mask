FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html
# 构建前置:frontend/dist 必须已存在(本机 npm run build,或在有 node 的构建
# 主机上 bash frontend/deploy.sh build)。镜像与容器内部署用 nginx.conf.docker
# (upstream 指向 host.docker.internal;compose 已配 host-gateway 映射)。
COPY frontend/dist/ .
COPY frontend/nginx.conf.docker /etc/nginx/conf.d/default.conf
EXPOSE 80
