import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// 不引 @types/node：配置里只用 process.env 这一个 Node 全局
declare const process: { env: Record<string, string | undefined> }

// 开发时前端跑在 5190，接口走代理打到本机 8095（Jotlog 后端默认端口）。
// 生产环境前端产物被塞进 jar 的 static/ 下，同源部署，不需要代理。
//
// 线上部署在 junan.cloud/jotlog/ 子路径下：
// 构建时 base 设为 /jotlog/，静态资源与接口都会带上该前缀；
// 本地开发仍用根路径，下面的 /api 代理照旧可用。
export default defineConfig(({ command }) => ({
  base: command === 'build' ? '/jotlog/' : '/',
  plugins: [vue(), tailwindcss()],
  server: {
    port: 5190,
    proxy: {
      '/api': {
        // 默认 8095；需要指到别的后端时，可用环境变量 JOTLOG_DEV_BACKEND 覆盖
        target: process.env.JOTLOG_DEV_BACKEND ?? 'http://localhost:8095',
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
}))
