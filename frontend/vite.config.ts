import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath, URL } from "node:url";

// dev 代理默认指本机后端;远程验证时用环境变量指到 SSH 隧道端口。
// 代理规则与生产 nginx 同构(最长前缀匹配):
//   /api/instances、/api/effective → policy(8081)
//   /api/meta/** → metadata(8082) 的 /api/**(前缀重写)
//   /api/v1/**   → query(8083)
//   其余 /api/** → core(8080,含 rewrite/config/policies/audit/metadata pull)
const policy = process.env.VITE_PROXY_8081 || "http://127.0.0.1:8081";
const risk = process.env.VITE_PROXY_8084 || "http://127.0.0.1:8084";
const core = process.env.VITE_PROXY_8080 || "http://127.0.0.1:8080";
const metadata = process.env.VITE_PROXY_8082 || "http://127.0.0.1:8082";
const query = process.env.VITE_PROXY_8083 || "http://127.0.0.1:8083";

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) }
  },
  server: {
    proxy: {
      "/api/instances": { target: policy, changeOrigin: true },
      "/api/risk": { target: risk, changeOrigin: true },
      "/api/effective": { target: policy, changeOrigin: true },
      "/api/v1": { target: query, changeOrigin: true },
      "/api/meta": {
        target: metadata,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api\/meta/, "/api")
      },
      "/api": { target: core, changeOrigin: true }
    }
  },
  test: {
    environment: "jsdom"
  }
});
