<template>
  <div>
    <!-- 数字只给"全部"页。星标页和归档页再报一遍总数没有意义 -->
    <div v-if="mode === 'all' && stats" class="mb-7 flex flex-wrap items-baseline gap-x-6 gap-y-1">
      <span class="flex items-baseline gap-1.5">
        <b class="font-serif text-[22px] leading-none text-ink">{{ stats.total }}</b>
        <span class="text-[13px] text-ink-3">条记录</span>
      </span>
      <span class="text-[13px] text-ink-3">今天 {{ stats.today }}</span>
      <span class="text-[13px] text-ink-3">本周 {{ stats.week }}</span>
    </div>

    <ComposeBox v-if="mode === 'all'" @created="reload" />

    <h2 v-if="mode !== 'all'" class="date-rule mb-3">
      {{ mode === 'starred' ? '星标' : '归档' }}
    </h2>

    <p v-if="error" class="rounded bg-danger/8 px-3 py-2 text-[13px] text-danger">{{ error }}</p>

    <!-- 首次加载 -->
    <div v-if="loading && items.length === 0" class="py-16 text-center text-[13px] text-ink-3">
      加载中…
    </div>

    <!-- 空状态：说清楚"这儿为什么是空的"，而不是只放一个图标 -->
    <div v-else-if="items.length === 0" class="py-16 text-center">
      <p class="text-[15px] text-ink-2">{{ emptyTitle }}</p>
      <p class="mt-1.5 text-[13px] text-ink-3">{{ emptyHint }}</p>
    </div>

    <div v-else>
      <section v-for="group in groups" :key="group.key">
        <div class="date-rule border-line-soft border-b py-2">{{ group.label }}</div>
        <EntryCard
          v-for="entry in group.entries"
          :key="entry.id"
          :entry="entry"
          @toggle-star="toggleStar(entry)"
          @toggle-archive="toggleArchive(entry)"
          @delete="remove(entry)"
        />
      </section>

      <!-- 滚动哨兵：滚到这里自动加载下一页，不用点按钮 -->
      <div ref="sentinel" class="h-4"></div>
      <div class="py-6 text-center text-[12px] text-ink-3">
        <template v-if="loading">加载中…</template>
        <template v-else-if="!hasMore">到底了</template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, ApiError } from '../api/http'
import type { Entry, Stats } from '../types'
import { dayKey, dayLabel } from '../utils/format'
import ComposeBox from '../components/ComposeBox.vue'
import EntryCard from '../components/EntryCard.vue'

const props = withDefaults(defineProps<{ mode?: 'all' | 'starred' | 'archive' }>(), {
  mode: 'all',
})

const SIZE = 30

const items = ref<Entry[]>([])
/** -1 表示还没拉过第一页，此时不能断言"没有更多了" */
const total = ref(-1)
const page = ref(0)
const loading = ref(false)
const error = ref('')
const stats = ref<Stats | null>(null)
const sentinel = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

const hasMore = computed(() => total.value < 0 || items.value.length < total.value)

/** 按日期分组。随手记是按时间回溯的，没有日期分隔就是一锅粥。 */
const groups = computed(() => {
  const map = new Map<string, Entry[]>()
  for (const entry of items.value) {
    const key = dayKey(entry.createdAt)
    const bucket = map.get(key)
    if (bucket) bucket.push(entry)
    else map.set(key, [entry])
  }
  return [...map.entries()].map(([key, entries]) => ({
    key,
    label: dayLabel(entries[0].createdAt),
    entries,
  }))
})

const emptyTitle = computed(() => {
  if (props.mode === 'starred') return '还没有加星标的内容'
  if (props.mode === 'archive') return '归档箱是空的'
  return '还没有记录'
})

const emptyHint = computed(() => {
  if (props.mode === 'starred') return '在时间线里点条目右上角的小星星，就会收到这里'
  if (props.mode === 'archive') return '归档不会删除内容，只是让它从时间线上消失'
  return '在上面写一条，或者去飞书里发给你的机器人'
})

async function fetchNextPage() {
  if (loading.value || !hasMore.value) return
  loading.value = true
  error.value = ''
  try {
    const result = await api.entries({
      starred: props.mode === 'starred' ? true : undefined,
      archived: props.mode === 'archive',
      page: page.value,
      size: SIZE,
    })
    items.value = [...items.value, ...result.items]
    total.value = result.total
    page.value += 1
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载失败，稍后再试'
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    stats.value = await api.stats()
  } catch {
    // 统计挂了不影响列表，静默忽略
  }
}

async function reload() {
  items.value = []
  total.value = -1
  page.value = 0
  await fetchNextPage()
  if (props.mode === 'all') loadStats()
}

async function toggleStar(entry: Entry) {
  const next = !entry.starred
  try {
    await api.updateEntry(entry.id, { starred: next })
    entry.starred = next
    // 星标页里取消星标后这条不该继续留在列表里
    if (props.mode === 'starred' && !next) {
      items.value = items.value.filter((x) => x.id !== entry.id)
      total.value = Math.max(total.value - 1, 0)
    }
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '操作失败'
  }
}

async function toggleArchive(entry: Entry) {
  const next = !entry.archived
  try {
    await api.updateEntry(entry.id, { archived: next })
    // 无论归档还是取消归档，这条都不属于当前列表了
    items.value = items.value.filter((x) => x.id !== entry.id)
    total.value = Math.max(total.value - 1, 0)
    if (props.mode === 'all') loadStats()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '操作失败'
  }
}

async function remove(entry: Entry) {
  try {
    await api.deleteEntry(entry.id)
    items.value = items.value.filter((x) => x.id !== entry.id)
    total.value = Math.max(total.value - 1, 0)
    if (props.mode === 'all') loadStats()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '删除失败'
  }
}

onMounted(() => {
  reload()
  observer = new IntersectionObserver(
    (entries) => {
      if (entries[0]?.isIntersecting) fetchNextPage()
    },
    { rootMargin: '200px' },
  )
  if (sentinel.value) observer.observe(sentinel.value)
})

// 切页（时间线 ↔ 星标 ↔ 归档）时组件会复用，必须重新拉数据
watch(
  () => props.mode,
  () => reload(),
)

// sentinel 在首次渲染时还不存在（列表为空），等它出现再挂观察
watch(sentinel, (el) => {
  if (el && observer) observer.observe(el)
})

onUnmounted(() => observer?.disconnect())
</script>
