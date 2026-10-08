import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// 开发时前端跑在 5173，接口走代理打到本机 8080。
// 生产环境前端产物被塞进 jar 的 static/ 下，同源部署，不需要代理。
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // 关掉 sourcemap：这是个人服务，没必要把源码映射公布出去
    sourcemap: false,
  },
})
