/**
 * 轻量 Markdown 解析器 —— 为"随手记"量身裁剪，不追求 CommonMark 全集。
 *
 * 为什么不用 marked / markdown-it + DOMPurify：
 *   1. 那条路必然走 v-html，而这个应用的 token 就放在 localStorage 里，
 *      渲染用户输入的第一原则是"DOM 里不许出现我拼出来的 HTML 字符串"。
 *   2. 输出结构化的 token 树交给 Vue 渲染函数，安全是结构保证的，
 *      不依赖"过滤器没漏"这种假设。
 *   3. 随手记里真正会出现的 md 语法很有限，自研 200 行足够，
 *      还省掉几十 KB 的 PWA 体积。
 *
 * 解析哲学是【保守】：没认出来的语法一律按普通文本原样保留。
 * 宁可少渲染一个标题，也不能把用户的原话改得面目全非。
 * （raw_input 不可变是产品红线，展示层的每一次"自作聪明"都是背叛。）
 */

/* ============================================================
 * 类型定义
 * ============================================================ */

/** 行内片段。渲染层见 MarkdownView.vue 的 renderSpans。 */
export type Span =
  | { t: 'text'; text: string }
  | { t: 'bold'; children: Span[] }
  | { t: 'italic'; children: Span[] }
  | { t: 'code'; text: string }
  | { t: 'del'; children: Span[] }
  | { t: 'link'; href: string; children: Span[] }

export type Block =
  | { kind: 'heading'; level: 1 | 2 | 3 | 4; spans: Span[] }
  | { kind: 'paragraph'; spans: Span[] }
  | { kind: 'code'; lang: string; text: string }
  | { kind: 'quote'; blocks: Block[] }
  | { kind: 'list'; ordered: boolean; start: number; items: Span[][] }
  | { kind: 'task'; checked: boolean; spans: Span[] }
  | { kind: 'divider' }

/* ============================================================
 * 对外入口
 * ============================================================ */

export function parseMarkdown(src: string): Block[] {
  const lines = src.replace(/\r\n?/g, '\n').split('\n')
  const blocks: Block[] = []

  let i = 0
  while (i < lines.length) {
    const line = lines[i]

    // 空行：分段的呼吸，直接跳过
    if (!line.trim()) {
      i++
      continue
    }

    // 围栏代码块。``` 或 ~~~ 开头且至少 3 个；没等到闭合就把剩余原文整个收进来
    const fence = /^(`{3,}|~{3,})\s*([\w+#-]*)\s*$/.exec(line)
    if (fence) {
      const close = fence[1][0].repeat(fence[1].length)
      const body: string[] = []
      i++
      while (i < lines.length && !lines[i].trimStart().startsWith(close)) {
        body.push(lines[i])
        i++
      }
      i++ // 吃掉闭合行；没闭合时这里把数组吃穿，同样是安全的
      blocks.push({ kind: 'code', lang: fence[2] ?? '', text: body.join('\n') })
      continue
    }

    // 水平线
    if (/^\s{0,3}((-\s*){3,}|(\*\s*){3,}|(_\s*){3,})$/.test(line)) {
      blocks.push({ kind: 'divider' })
      i++
      continue
    }

    // 标题：# 到 ####（随手记不往深了用）
    const heading = /^(#{1,4})\s+(.+?)\s*#*\s*$/.exec(line)
    if (heading) {
      blocks.push({
        kind: 'heading',
        level: heading[1].length as 1 | 2 | 3 | 4,
        spans: parseSpans(heading[2]),
      })
      i++
      continue
    }

    // 引用：连续的 > 行收进同一块，内部按段落解析
    if (/^\s{0,3}>/.test(line)) {
      const inner: string[] = []
      while (i < lines.length && /^\s{0,3}>/.test(lines[i])) {
        inner.push(lines[i].replace(/^\s{0,3}>\s?/, ''))
        i++
      }
      blocks.push({ kind: 'quote', blocks: parseMarkdown(inner.join('\n')) })
      continue
    }

    // 任务：- [ ] / - [x]。单独一种块，渲染成可扫读的待办样式
    const task = /^\s*[-*+]\s+\[([ xX])\]\s+(.*)$/.exec(line)
    if (task) {
      blocks.push({ kind: 'task', checked: task[1] !== ' ', spans: parseSpans(task[2]) })
      i++
      continue
    }

    // 列表：连续同符号的行归一组。有序列表的起始编号要保留（5. 接着写是有意的）
    const item = /^(\s*)([-*+]|(\d{1,9})[.)])\s+(.*)$/.exec(line)
    if (item) {
      const ordered = item[3] !== undefined
      const start = ordered ? Number(item[3]) : 1
      const marker = ordered ? null : item[2]
      const items: Span[][] = []
      while (i < lines.length) {
        const m = /^(\s*)([-*+]|(\d{1,9})[.)])\s+(.*)$/.exec(lines[i])
        // 无序列表混入有序符号（或反过来）就断组，各是各的
        if (!m || (m[3] !== undefined) !== ordered) break
        if (!ordered && m[2] !== marker) break
        items.push(parseSpans(m[4]))
        i++
      }
      blocks.push({ kind: 'list', ordered, start, items })
      continue
    }

    // 段落：收集到空行或任何块级语法为止
    const para: string[] = []
    while (i < lines.length) {
      const cur = lines[i]
      if (
        !cur.trim() ||
        /^(`{3,}|~{3,})/.test(cur) ||
        /^(#{1,4})\s/.test(cur) ||
        /^\s{0,3}>/.test(cur) ||
        /^\s*[-*+]\s+/.test(cur) ||
        /^\s*\d{1,9}[.)]\s+/.test(cur) ||
        /^\s{0,3}((-\s*){3,}|(\*\s*){3,}|(_\s*){3,})$/.test(cur)
      ) {
        break
      }
      para.push(cur)
      i++
    }
    if (para.length) {
      blocks.push({ kind: 'paragraph', spans: parseSpans(para.join('\n')) })
    }
  }

  return blocks
}

/** 有没有任何认识的 md 语法。详情面板靠它决定默认渲染还是默认原文。 */
export function looksLikeMarkdown(src: string): boolean {
  if (!src) return false
  const s = src.slice(0, 4000)
  return (
    /^```/m.test(s) ||
    /^~{3,}\s*$/m.test(s) ||
    /^#{1,4}\s+\S/m.test(s) ||
    /^\s{0,3}>\s?\S/m.test(s) ||
    /^\s*[-*+]\s+\[[ xX]\]/m.test(s) ||
    /^\s*[-*+]\s+\S/m.test(s) ||
    /^\s*\d{1,9}[.)]\s+\S/m.test(s) ||
    /\*\*[^*\n]+\*\*/.test(s) ||
    /~~[^~\n]+~~/.test(s) ||
    /(^|[^`])`[^`\n]+`/.test(s) ||
    /\[[^\]\n]+\]\([^)\n]+\)/.test(s)
  )
}

/** 列表预览用的纯文本净化：剥掉 md 符号，只留可读的字。不碰原文。 */
export function plainPreview(src: string): string {
  return src
    .split('\n')
    .map((line) => {
      let l = line
      // 围栏行整行去掉（```python 这行没有阅读价值）
      if (/^\s*(`{3,}|~{3,})/.test(l)) return ''
      l = l
        .replace(/^\s{0,3}#{1,4}\s+/, '') // 标题
        .replace(/^\s{0,3}>\s?/, '') // 引用
        .replace(/^(\s*)([-*+]|\d{1,9}[.)])\s+(\[[ xX]\]\s+)?/, '') // 列表/任务
        .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1') // 图片 → alt
        .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1') // 链接 → 文字
        .replace(/\*\*\*([^*]+)\*\*\*/g, '$1')
        .replace(/\*\*([^*]+)\*\*/g, '$1')
        .replace(/\*([^*\n]+)\*/g, '$1')
        .replace(/~~([^~]+)~~/g, '$1')
        .replace(/`([^`]+)`/g, '$1')
      return l.trimEnd()
    })
    .filter((l, idx, arr) => l.trim() || (idx > 0 && arr[idx - 1]!.trim()))
    .join('\n')
    .trim()
}

/* ============================================================
 * 行内解析
 * ============================================================ */

/**
 * 逐字符扫描 + 递归解析配对语法。
 *
 * 配对语法（**、`、~~、[]()）都要求闭合标记，找不到闭合就按普通文本处理。
 * 链接只放行 http(s) 和 mailto —— javascript: 这种东西在生成阶段就该死掉，
 * 而不是指望渲染层想起校验。
 */
export function parseSpans(text: string): Span[] {
  const spans: Span[] = []
  let buf = ''
  let i = 0

  const flush = () => {
    if (buf) {
      spans.push({ t: 'text', text: buf })
      buf = ''
    }
  }

  while (i < text.length) {
    const ch = text[i]!
    const next = text[i + 1]

    // 反斜杠转义：\* \_ \` \[ \\ 等，转义后按字面输出
    if (ch === '\\' && next !== undefined && /^[\s\p{P}\p{S}]/u.test(next)) {
      buf += next
      i += 2
      continue
    }

    // 行内代码：`code`。内部不做任何嵌套解析（代码就是代码）
    if (ch === '`') {
      const end = text.indexOf('`', i + 1)
      if (end > i + 1) {
        flush()
        spans.push({ t: 'code', text: text.slice(i + 1, end) })
        i = end + 1
        continue
      }
    }

    // 加粗斜体 ***text***，先于 ** 和 * 判定
    if (ch === '*' && next === '*' && text[i + 2] === '*') {
      const end = findClosing(text, i + 3, '***')
      if (end >= 0) {
        flush()
        spans.push({ t: 'bold', children: parseSpans(text.slice(i + 3, end)) })
        i = end + 3
        continue
      }
    }

    // 加粗 **text**（__text__ 同理）
    if ((ch === '*' && next === '*') || (ch === '_' && next === '_')) {
      const mark = ch + ch!
      const end = findClosing(text, i + 2, mark)
      if (end >= 0) {
        flush()
        spans.push({ t: 'bold', children: parseSpans(text.slice(i + 2, end)) })
        i = end + 2
        continue
      }
    }

    // 斜体 *text* / _text_。前后都不是字母数字时才算（snake_case 别误伤）
    if ((ch === '*' || ch === '_') && next && next !== ch) {
      const prevOk = i === 0 || !/[\p{L}\p{N}]/u.test(text[i - 1]!)
      if (prevOk && ch === '*') {
        const end = findClosing(text, i + 1, '*')
        if (end >= 0 && text[end + 1] !== '*') {
          flush()
          spans.push({ t: 'italic', children: parseSpans(text.slice(i + 1, end)) })
          i = end + 1
          continue
        }
      }
    }

    // 删除线 ~~text~~
    if (ch === '~' && next === '~') {
      const end = findClosing(text, i + 2, '~~')
      if (end >= 0) {
        flush()
        spans.push({ t: 'del', children: parseSpans(text.slice(i + 2, end)) })
        i = end + 2
        continue
      }
    }

    // 链接 [text](url)。url 不允许空白和未转义的括号
    if (ch === '[') {
      const closeText = text.indexOf('](', i + 1)
      if (closeText > i) {
        const closeUrl = text.indexOf(')', closeText + 2)
        if (closeUrl > closeText + 2) {
          const label = text.slice(i + 1, closeText)
          const href = text.slice(closeText + 2, closeUrl)
          if (!/[\s]/.test(href) && isSafeHref(href)) {
            flush()
            spans.push({ t: 'link', href, children: parseSpans(label) })
            i = closeUrl + 1
            continue
          }
        }
      }
    }

    // 裸链接自动识别。结尾不吃标点：句号、逗号、右括号通常是句子的一部分
    if (/^https?:\/\//i.test(text.slice(i, i + 8))) {
      let end = i + 8
      while (end < text.length && !/[\s<>"`]/.test(text[end]!)) end++
      while (end > i + 8 && /[.,;:!?)\]}，。；：！？）】」』]/.test(text[end - 1]!)) end--
      flush()
      spans.push({ t: 'link', href: text.slice(i, end), children: [{ t: 'text', text: text.slice(i, end) }] })
      i = end
      continue
    }

    buf += ch
    i++
  }

  flush()
  return spans
}

/** 从 from 开始找与 open 相同的闭合标记。找不到返回 -1，调用方按普通文本处理。 */
function findClosing(text: string, from: number, open: string): number {
  return text.indexOf(open, from)
}

/** 链接协议白名单。这里的名单比 DOMPurify 的默认还短 —— 随手记用不着别的。 */
function isSafeHref(href: string): boolean {
  return /^(https?:\/\/|mailto:)/i.test(href)
}
