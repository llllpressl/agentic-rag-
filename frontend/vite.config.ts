import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";

/** 原配置连接两个独立后端；现在默认将 Agent 和知识库请求都转发到统一应用。 */
export default defineConfig(({ mode }) => {
  const environment = loadEnv(mode, process.cwd(), "");
  return {
    plugins: [vue()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        "/api": {
          target: environment.AGENT_BACKEND_URL || "http://127.0.0.1:9091",
          changeOrigin: true,
        },
        "/knowledge": {
          /** 原默认目标：http://127.0.0.1:9090。仍允许通过环境变量连接独立 RAG。 */
          target: environment.RAG_BACKEND_URL || "http://127.0.0.1:9091",
          changeOrigin: true,
        },
      },
    },
  };
});
