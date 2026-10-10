<template>
  <div>
    <!-- 桌面刊头：刊物的门面，带印数 -->
    <div v-if="mode === 'all' && !isCompact" class="mb-8">
      <Masthead />
    </div>

    <!-- 手机紧凑页头：只放今天 + 条数，不占首屏 -->
    <header v-if="mode === 'all' && isCompact" class="mb-6">
      <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h1 class="font-serif text-[21px] leading-tight tracking-wide text-ink">
          {{ todayLabel }}
        </h1>
        <p v-if="stats.data" class="tabular text-[12.5px] text-ink-3">
          共 {{ stats.data.total }} 条 · 今天 {{ stats.data.today }}
        </p>
      </div>
    </header>

    <!-- 桌面常驻输入框；手机上靠右下角悬浮按钮，这里放会占掉首屏一大块 -->
    <ComposeBox v-if="mode === 'all' && !isCompact" @created="reload" />

    <h2 v-if="mode !== 'all'" class="t-rule mb-3">
      {{ mode === 'starred' ? '星标' : '归档' }}
    </h2>

    <p v-if="error" class="rounded-lg bg-danger-soft px-3.5 py-2.5 text-[13px] text-danger">
      {{ error }}
      <button class="ml-1 underline underline-offset-2" @click="fetchNextPage">重试</button>
    </p>

    <SkeletonList v-if="loading && items.length === 0" />

    <!-- 空状态说清楚"这儿为什么是空的"，而不是只放一个图标 -->
    <EmptyState
      v-else-if="items.length === 0"
      :title="emptyTitle"
      :hint="emptyHint"
      :art="emptyArt"
    >
      <RouterLink v-if="mode === 'all'" to="/search" class="btn btn-ghost mt-5">
        <Icon name="search" :size="15" />
        去搜点什么
      </RouterLink>
    </EmptyState>

    <div v-else>
      <section v-for="group in groups" :key="group.key">
        <!-- 日期分隔吸顶：往下翻的时候始终知道"现在翻到哪天了" -->
        <div
          class="t-rule border-line-soft bg-paper/92 sticky top-0 z-10 -mx-5 border-b px-5 py-2 backdrop-blur sm:-mx-6 sm:px-6"
        >
          {{ group.label }}
        </div>
        <EntryCard
          v-for="entry in group.entries"
          :key="entry.id"
          :entry="entry"
          :selected="entry.id === ui.selectedId"
          @open="ui.select(entry)"
          @toggle-star="ops.toggleStar(entry)"
          @toggle-archive="ops.toggleArchive(entry)"
          @delete="ops.remove(entry)"
        />
      </section>

      <!-- 滚动哨兵：滚到这里自动加载下一页，不用点按钮 -->
      <div ref="sentinel" class="h-4"></div>
      <div class="py-7 text-center text-[12px] text-ink-3">
        <template v-if="loading">加载中…</template>
        <template v-else-if="!hasMore">到底了</template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, ApiError } from '../api/http'
import type { Entry } from '../types'
import { dayKey, dayLabel, todayIso } from '../utils/format'
import { useBreakpoint } from '../composables/useBreakpoint'
import { useEntryOps } from '../composables/useEntryOps'
import { useHotkeys } from '../composables/useHotkeys'
import { useStatsStore } from '../stores/stats'
import { useUiStore } from '../stores/ui'
import ComposeBox from '../components/ComposeBox.vue'
import EntryCard from '../components/EntryCard.vue'
import Masthead from '../components/Masthead.vue'
import EmptyState from '../components/EmptyState.vue'
import SkeletonList from '../components/SkeletonList.vue'
import Icon from '../components/Icon.vue'

const props = withDefaults(defineProps<{ mode?: 'all' | 'starred' | 'archive' }>(), {
  mode: 'all',
})

const SIZE = 30

const ui = useUiStore()
const stats = useStatsStore()
const ops = useEntryOps()
const { isCompact } = useBreakpoint()

const items = ref<Entry[]>([])
/** -1 表示还没拉过第一页，此时不能断言"没有更多了" */
const total = ref(-1)
const page = ref(0)
const loading = ref(false)
const error = ref('')
const sentinel = ref<HTMLElement | null>(null)
let observer: IntersectionObserver | null = null

const hasMore = computed(() => total.value < 0 || items.value.length < total.value)

const todayLabel = computed(() => dayLabel(todayIso()))

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
  if (props.mode === 'starred') return '在时间线里点条目右边的小星星，就会收到这里'
  if (props.mode === 'archive') return '归档不会删除内容，只是让它从时间线上消失'
  return '在上面写一条，或者去飞书里发给你的机器人'
})

const emptyArt = computed<'empty' | 'star' | 'archive'>(() => {
  if (props.mode === 'starred') return 'star'
  if (props.mode === 'archive') return 'archive'
  return 'empty'
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

async function reload() {
  items.value = []
  total.value = -1
  page.value = 0
  await fetchNextPage()
  stats.load()
}

// 详情面板（挂在 AppShell 上）改了条目，这里要跟着改列表
watch(
  () => ui.entryChange,
  (change) => {
    if (!change) return
    const index = items.value.findIndex((e) => e.id === change.id)
    if (index < 0) return

    if (change.kind === 'delete' || change.kind === 'archive') {
      items.value.splice(index, 1)
      total.value = Math.max(total.value - 1, 0)
      return
    }

    if (change.kind === 'star') {
      const entry = items.value[index]
      entry.starred = change.starred ?? entry.starred
      // 星标页里取消星标后，这条就不该继续留在列表里了
      if (props.mode === 'starred' && !entry.starred) {
        items.value.splice(index, 1)
        total.value = Math.max(total.value - 1, 0)
      }
    }
  },
)

// 别处记了一条（手机弹层、快捷键），列表重新拉
watch(
  () => ui.reloadSeq,
  () => reload(),
)

// 切页（时间线 ↔ 星标 ↔ 归档）时组件会复用，必须重新拉数据
watch(
  () => props.mode,
  () => reload(),
)

onMounted(() => {
  reload()
  observer = new IntersectionObserver(
    (entries) => {
      if (entries[0]?.isIntersecting) fetchNextPage()
    },
    { rootMargin: '300px' },
  )
  if (sentinel.value) observer.observe(sentinel.value)
})

// sentinel 首次渲染时还不存在（列表为空），等它出现再挂观察
watch(sentinel, (el) => {
  if (el && observer) observer.observe(el)
})

onUnmounted(() => observer?.disconnect())

// j / k 上下移动选中项。只在列表视图里注册，避免和输入框抢键
useHotkeys({
  onMove: (delta) => {
    if (!items.value.length) return
    const current = items.value.findIndex((e) => e.id === ui.selectedId)
    const next = current < 0 ? 0 : Math.min(Math.max(current + delta, 0), items.value.length - 1)
    ui.select(items.value[next])
  },
})
</script>
