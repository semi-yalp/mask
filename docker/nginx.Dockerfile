FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html
# 部署产物是 vite 构建出的 dist/(deploy.sh build 在远端生成);源码树不进镜像。
# 先经 deploy.sh build 再 up,否则 dist 为空。
COPY frontend/dist/ .
# 容器内 127.0.0.1 指向容器自身，因此用 upstream 指向 host.docker.internal 的
# 专用配置（与 frontend/nginx.conf 保持同构，仅 upstream 主机不同）。
COPY frontend/nginx.conf.docker /etc/nginx/conf.d/default.conf
EXPOSE 80
