import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

/**
 * 路由。
 *
 * /login 是唯一公开页，其余全部嵌在 AppShell 里（带导航和输入框）。
 * 用 history 模式而不是 hash：URL 干净，刷新时靠后端 SPA 兜底转发到 index.html。
 *
 * base 取 Vite 的 BASE_URL：线上部署在 /jotlog/ 子路径下时为 /jotlog/，
 * 本地开发为 /。这样深链接和刷新都能正确匹配路由。
 *
 * 不做滚动行为配置：滚动条在 AppShell 的内容列上，不在 window 上，
 * router 的 scrollBehavior 管不到它，由 AppShell 自己处理。
 */
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: () => import('../components/AppShell.vue'),
      children: [
        {
          path: '',
          name: 'timeline',
          component: () => import('../views/TimelineView.vue'),
          meta: { title: '时间线' },
        },
        {
          path: 'starred',
          name: 'starred',
          component: () => import('../views/TimelineView.vue'),
          props: { mode: 'starred' },
          meta: { title: '星标' },
        },
        {
          path: 'archive',
          name: 'archive',
          component: () => import('../views/TimelineView.vue'),
          props: { mode: 'archive' },
          meta: { title: '归档' },
        },
        {
          path: 'search',
          name: 'search',
          component: () => import('../views/SearchView.vue'),
          meta: { title: '搜索' },
        },
        {
          path: 'settings',
          name: 'settings',
          component: () => import('../views/SettingsView.vue'),
          meta: { title: '设置' },
        },
      ],
    },
    // 兜底：未知路径回到时间线，而不是空白页
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.ready) await auth.bootstrap()

  if (!to.meta.public && !auth.isLoggedIn) {
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.isLoggedIn) {
    return { path: '/' }
  }
  return true
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : ''
  document.title = title ? `${title} · Jotlog` : 'Jotlog · 随手记'
})

export default router
