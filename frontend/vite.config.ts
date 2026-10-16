import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";

/** 浏览器始终访问同源路径；两个独立后端由开发服务分别转发。 */
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
          target: environment.RAG_BACKEND_URL || "http://127.0.0.1:9090",
          changeOrigin: true,
        },
      },
    },
  };
});
