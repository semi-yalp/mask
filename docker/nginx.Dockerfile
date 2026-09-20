FROM nginx:1.27-alpine
WORKDIR /usr/share/nginx/html
COPY frontend/ .
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80