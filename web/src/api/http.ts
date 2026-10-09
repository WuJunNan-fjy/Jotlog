import type { AttachmentMeta, Entry, Page, Stats, User } from '../types'

const TOKEN_KEY = 'jotlog.token'

/**
 * 401 回调。
 *
 * token 过期是随时可能发生的（后端重启、改密码、会话被清），
 * 与其让每个页面各自处理，不如在这里统一交给 store 去登出。
 */
let unauthorizedHandler: (() => void) | null = null

export function onUnauthorized(fn: () => void) {
  unauthorizedHandler = fn
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

interface RequestOptions {
  method?: string
  body?: unknown
  params?: Record<string, string | number | boolean | undefined | null>
}

/**
 * 统一请求出口。
 *
 * 只认一种错误格式：{"error": "..."}，由后端 ApiExceptionHandler 保证。
 * 网络层失败（连不上服务器）也转成 ApiError，让调用方只写一个 catch。
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, params } = options

  let url = path
  if (params) {
    const search = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null && value !== '') {
        search.append(key, String(value))
      }
    }
    const qs = search.toString()
    if (qs) url += `?${qs}`
  }

  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const token = getToken()
  if (token) headers['Authorization'] = `Bearer ${token}`

  let response: Response
  try {
    response = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, '连不上服务器，请检查网络或后端是否在跑')
  }

  if (response.status === 401) {
    unauthorizedHandler?.()
    throw new ApiError(401, '登录已失效，请重新登录')
  }

  // 204 / 空响应体不能 JSON.parse，否则抛 SyntaxError 盖掉真实状态
  const text = await response.text()
  const payload = text ? JSON.parse(text) : null

  if (!response.ok) {
    throw new ApiError(response.status, payload?.error ?? `请求失败（${response.status}）`)
  }
  return payload as T
}

export const api = {
  entries: (params: {
    q?: string
    type?: string
    starred?: boolean
    archived?: boolean
    page?: number
    size?: number
  }) => request<Page>('/api/entries', { params }),

  // 正文走 JSON body。以前放在 query 里，长笔记会被 URL 长度上限悄悄截断
  createEntry: (text: string) =>
    request<{ id: number; type: string; duplicate: boolean }>('/api/entries', {
      method: 'POST',
      body: { text },
    }),

  // 详情面板按 id 精取。用于带 ?id= 的链接直达（刷新、分享）时列表里还没有这条
  entry: (id: number) => request<Entry>(`/api/entries/${id}`),

  // 某条记录的全部附件元数据（含图片和文件）
  entryAttachments: (id: number) =>
    request<AttachmentMeta[]>(`/api/entries/${id}/attachments`),

  updateEntry: (id: number, patch: { starred?: boolean; archived?: boolean; note?: string }) =>
    request<{ ok: boolean }>(`/api/entries/${id}`, { method: 'PATCH', body: patch }),

  deleteEntry: (id: number) => request<{ ok: boolean }>(`/api/entries/${id}`, { method: 'DELETE' }),

  stats: () => request<Stats>('/api/stats'),

  login: (username: string, password: string, code: string) =>
    request<{ token: string; expiresInSeconds: number; user: User }>('/api/auth/login', {
      method: 'POST',
      body: { username, password, code },
    }),

  sendCode: (username: string) =>
    request<{ sent: boolean; ttlMinutes: number }>('/api/auth/code', {
      method: 'POST',
      body: { username },
    }),

  me: () => request<User>('/api/auth/me'),

  logout: () => request<{ ok: boolean }>('/api/auth/logout', { method: 'POST' }),

  changePassword: (oldPassword: string, newPassword: string) =>
    request<{ ok: boolean }>('/api/auth/password', {
      method: 'PUT',
      body: { oldPassword, newPassword },
    }),

  changeEmail: (code: string, email: string) =>
    request<{ ok: boolean }>('/api/auth/email', { method: 'PUT', body: { code, email } }),
}
