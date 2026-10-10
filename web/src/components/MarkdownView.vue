<!--
  Markdown 渲染视图。

  接收 utils/markdown.ts 解析出的 token 树，用 h() 渲染成 vnode。

  为什么不用 v-html：一条含 <script> 的随手记就能执行脚本，
  而这个应用的 token 存在 localStorage 里。这里没有一处 innerHTML，
  安全不靠"记得转义"，靠的是结构上根本没有注入面。
-->
<template>
  <div class="md">
    <component :is="() => renderBlocks(blocks)" />
  </div>
</template>

<script setup lang="ts">
import { computed, h, type VNode } from 'vue'
import { parseMarkdown, type Block, type Span } from '../utils/markdown'

const props = defineProps<{ source: string }>()

const blocks = computed(() => parseMarkdown(props.source))

function renderBlocks(blocks: Block[]): VNode[] {
  return blocks.map((b) => {
    switch (b.kind) {
      case 'heading': {
        const tag = `h${Math.min(b.level + 2, 6)}`
        // 随手记的标题不该像文章标题那么大：# 映射成 h3，逐级递减
        return h(tag, renderSpans(b.spans))
      }
      case 'paragraph':
        return h('p', renderSpans(b.spans))
      case 'code':
        return h(
          'pre',
          b.lang ? { 'data-lang': b.lang } : {},
          [h('code', b.text)],
        )
      case 'quote':
        return h('blockquote', renderBlocks(b.blocks))
      case 'list':
        return h(
          b.ordered ? 'ol' : 'ul',
          b.ordered && b.start !== 1 ? { start: b.start } : {},
          b.items.map((spans) => h('li', renderSpans(spans))),
        )
      case 'task':
        return h('div', { class: 'md-task' }, [
          h('span', {
            class: ['md-check', b.checked ? 'is-done' : ''],
            'aria-hidden': 'true',
          }),
          h('span', { class: b.checked ? 'md-task-done' : '' }, renderSpans(b.spans)),
        ])
      case 'divider':
        return h('hr')
    }
  })
}

function renderSpans(spans: Span[]): VNode[] {
  return spans.map((s) => {
    switch (s.t) {
      case 'text':
        return h('span', s.text)
      case 'bold':
        return h('strong', renderSpans(s.children))
      case 'italic':
        return h('em', renderSpans(s.children))
      case 'del':
        return h('del', renderSpans(s.children))
      case 'code':
        return h('code', s.text)
      case 'link':
        // 外链一律新开标签 + noopener：外站拿不到 window.opener，反向操纵不了本页
        return h(
          'a',
          { href: s.href, target: '_blank', rel: 'noopener noreferrer' },
          renderSpans(s.children),
        )
    }
  })
}
</script>
