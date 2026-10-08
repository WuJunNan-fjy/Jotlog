import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api, clearToken, getToken, onUnauthorized, setToken } from '../api/http'
import type { User } from '../types'

/**
 * 登录态。
 *
 * token 存 localStorage 而不是 cookie：前后端分离 + Bearer JWT 的组合下
 * 这是最简单可靠的做法。代价是 XSS 能读到 token，所以任何地方都不要用
 * v-html 渲染用户输入的内容。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getToken())
  const user = ref<User | null>(null)
  /** 是否已经确认过登录态。首次拉 me 之前不能让路由守卫下判断。 */
  const ready = ref(false)

  const isLoggedIn = computed(() => token.value !== null && user.value !== null)

  function reset() {
    token.value = null
    user.value = null
    clearToken()
  }

  // 后端说 401，说明这张票废了。统一在这里处理，页面不用各自判断。
  onUnauthorized(() => reset())

  /** 启动时调用：有 token 就确认它是不是还有效。 */
  async function bootstrap() {
    if (!token.value) {
      ready.value = true
      return
    }
    try {
      user.value = await api.me()
    } catch {
      reset()
    } finally {
      ready.value = true
    }
  }

  async function sendCode(username: string) {
    return api.sendCode(username)
  }

  async function login(username: string, password: string, code: string) {
    const result = await api.login(username, password, code)
    setToken(result.token)
    token.value = result.token
    user.value = result.user
  }

  async function logout() {
    try {
      await api.logout()
    } catch {
      // 后端登出失败也要把本地清掉，否则用户点了登出却还在登录态
    } finally {
      reset()
    }
  }

  async function changePassword(oldPassword: string, newPassword: string) {
    await api.changePassword(oldPassword, newPassword)
    // 后端改密码会踢掉所有会话，本地这张票也废了，直接退回登录页
    reset()
  }

  async function changeEmail(code: string, email: string) {
    await api.changeEmail(code, email)
    if (user.value) user.value = { ...user.value, email }
  }

  return {
    token,
    user,
    ready,
    isLoggedIn,
    bootstrap,
    sendCode,
    login,
    logout,
    changePassword,
    changeEmail,
  }
})
