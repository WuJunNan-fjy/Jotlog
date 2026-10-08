import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

/**
 * 路由。
 *
 * /login 是唯一公开页，其余全部嵌在 AppShell 里（带导航和输入框）。
 * 用 history 模式而不是 hash：URL 干净，刷新时靠后端 SPA 兜底转发到 index.html。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: () => import('../components/AppShell.vue'),
      children: [
        { path: '', name: 'timeline', component: () => import('../views/TimelineView.vue') },
        {
          path: 'starred',
          name: 'starred',
          component: () => import('../views/TimelineView.vue'),
          props: { mode: 'starred' },
        },
        {
          path: 'archive',
          name: 'archive',
          component: () => import('../views/TimelineView.vue'),
          props: { mode: 'archive' },
        },
        { path: 'search', name: 'search', component: () => import('../views/SearchView.vue') },
        { path: 'settings', name: 'settings', component: () => import('../views/SettingsView.vue') },
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

export default router
