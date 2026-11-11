import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址。默认指向本地 Spring Boot；如需改端口或机器，
// 启动前设置环境变量 VITE_API_TARGET，例如：
//   VITE_API_TARGET=http://192.168.1.20:8089 npm run dev
const target = process.env.VITE_API_TARGET || 'http://localhost:8089'

const proxy = {
  '/api': {
    target,
    changeOrigin: true,
  },
  '/actuator': {
    target,
    changeOrigin: true,
  },
}

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy,
  },
  preview: {
    port: 4173,
    proxy,
  },
})
