/**
 * 附件加载缓存。
 *
 * <img> 标签发不了 Authorization 头（token 在 localStorage，不是 cookie），
 * 所以附件一律走 fetch → blob → objectURL。这条链路必须有个共享缓存：
 *
 *   1. 列表缩略图和详情大图是同一个文件，不缓存就是下两遍
 *   2. lightbox 打开大图时如果还要等一个全新的请求，体验是断裂的
 *
 * LRU 语义很简单：超过上限就从最老的开始 revoke。
 * objectURL 不主动释放会占内存直到刷新页面 —— 图片场景几 MB 一张，
 * 单用户量级上限 60 张已经远超一次会话的浏览量。
 */

const cache = new Map<string, string>() // sha → objectURL
const inflight = new Map<string, Promise<string>>()
const MAX = 60

import { getToken } from './http'

/**
 * 接口前缀，与 http.ts 保持一致。
 *
 * 部署在 /jotlog/ 子路径下时，附件 URL 也必须带同一前缀，
 * 否则请求会落到站点根的 /api/（另一个后端）上，拿到 404。
 * BASE_URL 生产为 '/jotlog/'、本地开发为 '/'，去掉结尾斜杠即可。
 */
const API_BASE = import.meta.env.BASE_URL.replace(/\/$/, '')

export function attachmentUrl(sha: string): string {
  return `${API_BASE}/api/attachments/${encodeURIComponent(sha)}/raw`
}

/** 取附件的 objectURL。缓存命中直接返回；请求中的去重等待；否则发起请求。 */
export function loadAttachment(sha: string): Promise<string> {
  const hit = cache.get(sha)
  if (hit) {
    // 命中时挪到 Map 尾部 = 最近使用
    cache.delete(sha)
    cache.set(sha, hit)
    return Promise.resolve(hit)
  }

  const pending = inflight.get(sha)
  if (pending) return pending

  const task = (async () => {
    const response = await fetch(attachmentUrl(sha), {
      headers: getToken() ? { Authorization: `Bearer ${getToken()}` } : {},
    })
    if (!response.ok) throw new Error(`附件加载失败（${response.status}）`)
    const blob = await response.blob()

    // 拿到结果后再查一次缓存：两个并发请求只留一份 URL
    const existing = cache.get(sha)
    if (existing) return existing

    const url = URL.createObjectURL(blob)
    cache.set(sha, url)

    // 超上限时淘汰最老的（Map 迭代顺序即插入顺序）
    while (cache.size > MAX) {
      const oldest = cache.keys().next().value
      if (oldest === undefined) break
      const oldUrl = cache.get(oldest)
      if (oldUrl) URL.revokeObjectURL(oldUrl)
      cache.delete(oldest)
    }

    return url
  })()

  inflight.set(sha, task)
  task.finally(() => inflight.delete(sha)).catch(() => {})
  return task
}

export function peekAttachment(sha: string): string | null {
  return cache.get(sha) ?? null
}

/**
 * 下载附件到本地。
 *
 * 不能用 <a href> 直接导航：浏览器发起的请求带不上 Authorization 头，
 * 必撞 401。所以先 fetch 拿 blob，再借一个临时 <a download> 触发另存。
 *
 * 刻意不走 loadAttachment 的缓存 —— 下载的可能是几十 MB 的大文件，
 * 塞进预览缓存会把缩略图全挤出去。
 */
export async function downloadAttachment(sha: string, filename: string): Promise<void> {
  const token = getToken()
  const response = await fetch(`${attachmentUrl(sha)}?download=1`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!response.ok) {
    throw new Error(`下载失败（${response.status}）`)
  }

  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename || sha
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 浏览器开始读这个 URL 就行，不用等下载完成；10 秒后回收足够保险
  setTimeout(() => URL.revokeObjectURL(url), 10_000)
}
