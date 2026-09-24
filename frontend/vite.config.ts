import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath, URL } from "node:url";

// arch-v2 起后端是单进程单体(mask-server, 8080):dev 代理与生产 nginx 同为
// 一条 /api 规则直指 8080。远程验证时用环境变量指到 SSH 隧道端口。
const server = process.env.VITE_PROXY_8080 || "http://127.0.0.1:8080";

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) }
  },
  server: {
    proxy: {
      "/api": { target: server, changeOrigin: true }
    }
  },
  test: {
    environment: "jsdom"
  }
});
