export interface Segment {
  text: string
  hit: boolean
}

/**
 * 把一段文字按关键词切成"命中 / 未命中"的片段，供模板分段渲染。
 *
 * 刻意返回数组而不是拼 HTML 字符串：渲染用户输入的内容必须走 Vue 的文本插值，
 * 一旦用 v-html 拼 <mark>，一条含有 <script> 的随手记就能执行脚本 ——
 * 而这个应用的 token 就存在 localStorage 里。
 */
export function splitHighlight(text: string, keyword: string): Segment[] {
  const kw = keyword.trim()
  if (!kw) return [{ text, hit: false }]

  // 多个词之间按空格拆，任一词命中都算。中文常常连着写，所以不做整串匹配
  const terms = kw
    .split(/\s+/)
    .filter(Boolean)
    .map(escapeRegExp)
  if (!terms.length) return [{ text, hit: false }]

  const re = new RegExp(`(${terms.join('|')})`, 'gi')
  const lowered = new Set(terms.map((t) => t.toLowerCase()))

  return text
    .split(re)
    .filter((s) => s !== '')
    .map((s) => ({ text: s, hit: lowered.has(s.toLowerCase()) }))
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
