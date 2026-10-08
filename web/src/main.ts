import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { initTheme } from './composables/useTheme'
import './style.css'

// 主题必须在挂载前定好，否则第一帧会用浅色渲染，深色用户会看到闪一下白
initTheme()

createApp(App).use(createPinia()).use(router).mount('#app')

// Service Worker 只在生产构建里注册：开发时它会缓存住旧资源，刷新半天看不到改动
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch((err) => {
      console.warn('[jotlog] service worker 注册失败：', err)
    })
  })
}
