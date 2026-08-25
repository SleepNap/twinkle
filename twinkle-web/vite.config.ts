import path from "node:path"

import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { defineConfig, loadEnv } from "vite"

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "")

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        "@": path.resolve(__dirname, "./src"),
      },
    },
    server: {
      proxy: {
        "/api-docs": {
          target: env.VITE_API_PROXY_TARGET || "http://127.0.0.1:8686",
          changeOrigin: true,
        },
        "/docs": {
          target: env.VITE_API_PROXY_TARGET || "http://127.0.0.1:8686",
          changeOrigin: true,
        },
        "^/api/": {
          target: env.VITE_API_PROXY_TARGET || "http://127.0.0.1:8686",
          changeOrigin: true,
        },
        "/admin": {
          target: env.VITE_API_PROXY_TARGET || "http://127.0.0.1:8686",
          changeOrigin: true,
        },
      },
    },
  }
})
