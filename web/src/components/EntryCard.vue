<template>
  <article class="group relative border-line-soft border-b py-4 last:border-b-0">
    <div class="flex gap-3">
      <!-- 时间单独一列：扫读时能快速定位"这是什么时候记的" -->
      <div class="w-10 shrink-0 pt-[3px] text-right">
        <span class="text-[12px] tabular-nums text-ink-3">{{ time }}</span>
      </div>

      <div class="min-w-0 flex-1">
        <p class="prose-entry text-[15px] text-ink">{{ entry.rawInput }}</p>

        <!-- AI 补充区：弱化显示，让人一眼分清"我写的"和"机器补的" -->
        <p v-if="entry.aiSummary" class="mt-2 border-l-2 border-line pl-3 text-[13.5px] text-ink-2">
          {{ entry.aiSummary }}
        </p>
        <p v-else-if="entry.title" class="mt-1.5 text-[13.5px] text-ink-2">{{ entry.title }}</p>

        <a
          v-if="entry.url"
          :href="entry.url"
          target="_blank"
          rel="noopener noreferrer"
          class="mt-2.5 inline-flex items-center gap-1.5 text-[13px] text-accent hover:underline"
        >
          <Icon name="external" class="h-3.5 w-3.5" />
          <span class="truncate">{{ entry.domain || entry.url }}</span>
        </a>

        <div class="mt-2 flex flex-wrap items-center gap-2 text-[11px] text-ink-3">
          <span class="rounded px-1.5 py-[1px]" :class="badgeClass">{{ typeLabel }}</span>
          <span>{{ sourceLabel }}</span>
          <span v-if="entry.aiTags" class="truncate">{{ entry.aiTags }}</span>
        </div>
      </div>

      <!-- 操作区：桌面 hover 才显示，移动端常显（没有 hover 这回事） -->
      <div
        class="flex shrink-0 items-start gap-0.5 transition-opacity md:opacity-0 md:group-hover:opacity-100"
      >
        <button
          class="rounded p-1.5 transition-colors"
          :class="props.entry.starred ? 'text-brass' : 'text-ink-3 hover:text-brass'"
          :title="props.entry.starred ? '取消星标' : '加星标'"
          @click="emit('toggleStar')"
        >
          <Icon
            name="star"
            class="h-[15px] w-[15px]"
            :class="props.entry.starred ? 'fill-current' : ''"
          />
        </button>

        <button
          class="rounded p-1.5 text-ink-3 transition-colors hover:text-accent"
          :title="props.entry.archived ? '取消归档' : '归档'"
          @click="emit('toggleArchive')"
        >
          <Icon name="archive" class="h-[15px] w-[15px]" />
        </button>

        <!-- 删除要二次确认：误删一条随手记是不可逆的 -->
        <button
          v-if="!confirming"
          class="rounded p-1.5 text-ink-3 transition-colors hover:text-danger"
          title="删除"
          @click="confirming = true"
        >
          <Icon name="trash" class="h-[15px] w-[15px]" />
        </button>
        <button
          v-else
          class="rounded px-1.5 py-1 text-[11px] text-danger"
          @click="confirmDelete"
          @blur="confirming = false"
        >
          确定删除
        </button>
      </div>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Entry } from '../types'
import { formatTime } from '../utils/format'
import Icon from './Icon.vue'

const props = defineProps<{ entry: Entry }>()

const emit = defineEmits<{
  toggleStar: []
  toggleArchive: []
  delete: []
}>()

const confirming = ref(false)

const time = computed(() => formatTime(props.entry.createdAt))

const TYPE_LABEL: Record<string, string> = {
  link: '链接',
  repo: '仓库',
  video: '视频',
  note: '随记',
  file: '文件',
  image: '图片',
  unknown: '未分类',
}

const SOURCE_LABEL: Record<string, string> = {
  feishu: '飞书',
  web: '网页',
  pwa: '网页',
  manual: '手动',
  cli: '命令行',
}

const typeLabel = computed(() => TYPE_LABEL[props.entry.entryType] ?? props.entry.entryType)
const sourceLabel = computed(() => SOURCE_LABEL[props.entry.source] ?? props.entry.source)

const badgeClass = computed(() => {
  switch (props.entry.entryType) {
    case 'link':
    case 'repo':
      return 'bg-accent-soft text-accent'
    case 'video':
      return 'bg-brass-soft text-brass'
    default:
      return 'bg-paper-deep text-ink-3'
  }
})

function confirmDelete() {
  confirming.value = false
  emit('delete')
}
</script>
