// Jotlog service worker
//
// 策略分三类，因为它们的更新语义完全不同：
//   /api/**      —— 只走网络。缓存接口数据会让用户看到过期的列表，
//                   更糟的是把带登录态的响应缓存下来给下一个人
//   导航请求     —— 网络优先，离线时回落到 index.html（SPA 必须这样，否则刷新就白屏）
//   静态资源     —— 缓存优先。文件名带 hash，内容变了文件名就变，不存在"缓存住旧版本"
//
// 版本号手动加。改了任何被缓存的资源就要 +1，否则用户拿到的是旧文件。
//
// ⚠️ 所有路径都基于 registration.scope 计算，不能写死 '/'：
//    线上部署在 /jotlog/ 子路径，写死根路径会让预缓存全部 404，
//    更糟的是 /jotlog/api/* 匹配不上 '/api/' 前缀，接口响应会被当成静态资源缓存。
const CACHE = 'jotlog-v5'
const SCOPE = self.registration.scope // 末尾恒带 /：本地是 https://host/，线上是 https://host/jotlog/
const SHELL = [
  SCOPE,
  new URL('index.html', SCOPE).href,
  new URL('manifest.webmanifest', SCOPE).href,
]

/** 本应用 API 的路径前缀：根部署为 '/api/'，子路径部署为 '/jotlog/api/'。 */
const API_PREFIX = new URL('api/', SCOPE).pathname

self.addEventListener('install', (event) => {
  // 预缓存壳子，这样第一次离线打开也有东西可渲染
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(SHELL))
      .catch(() => {
        // 预缓存失败不该让安装失败，否则整个 PWA 都装不上
      }),
  )
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))),
  )
  self.clients.claim()
})

self.addEventListener('fetch', (event) => {
  const { request } = event
  if (request.method !== 'GET') return

  const url = new URL(request.url)
  if (url.origin !== self.location.origin) return

  // 接口：永远不缓存（含 /api/attachments/.../raw，私人附件不许进 Cache Storage）
  if (url.pathname.startsWith(API_PREFIX)) return

  // 导航：网络优先，失败回落壳子
  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request).catch(() =>
        caches.match(new URL('index.html', SCOPE).href).then((r) => r || Response.error()),
      ),
    )
    return
  }

  // 静态资源：缓存优先，顺手把新资源塞进去
  event.respondWith(
    caches.match(request).then(
      (hit) =>
        hit ||
        fetch(request).then((response) => {
          if (response.ok && response.type === 'basic') {
            const copy = response.clone()
            caches.open(CACHE).then((cache) => cache.put(request, copy))
          }
          return response
        }),
    ),
  )
})
