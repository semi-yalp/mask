import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath, URL } from "node:url";

// dev 代理默认指本机后端;远程验证时用环境变量指到 SSH 隧道端口。
const policy = process.env.VITE_PROXY_8081 || "http://127.0.0.1:8081";
const core = process.env.VITE_PROXY_8080 || "http://127.0.0.1:8080";

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) }
  },
  server: {
    proxy: {
      // 登录端点在 policy-server；必须置于泛 /api 规则之前
      "/api/auth": { target: policy, changeOrigin: true },
      "/api/instances": { target: policy, changeOrigin: true },
      "/api/effective": { target: policy, changeOrigin: true },
      "/api": { target: core, changeOrigin: true }
    }
  },
  test: {
    environment: "jsdom"
  }
});
