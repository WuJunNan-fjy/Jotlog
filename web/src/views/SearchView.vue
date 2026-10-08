<template>
  <div>
    <!-- 搜索框。比普通输入框大一号：这页唯一的主角就是它 -->
    <div class="relative mb-4">
      <Icon
        name="search"
        :size="17"
        class="absolute top-1/2 left-4 -translate-y-1/2 text-ink-3"
      />
      <input
        ref="input"
        v-model="keyword"
        class="field py-3 pr-11 pl-11 text-[15.5px]"
        placeholder="搜原文、标题和 AI 摘要…"
        @input="schedule"
        @keydown.esc.prevent="clear"
      />
      <button
        v-if="keyword"
        class="btn-icon absolute top-1/2 right-2.5 -translate-y-1/2"
        aria-label="清空"
        @click="clear"
      >
        <Icon name="x" :size="16" />
      </button>
    </div>

    <!-- 类型筛选 -->
    <div class="no-scrollbar -mx-4 mb-5 flex gap-1.5 overflow-x-auto px-4 sm:-mx-6 sm:px-6">
      <button
        v-for="f in TYPE_FILTERS"
        :key="f.value"
        class="chip shrink-0 border transition-colors"
        :class="
          type === f.value
            ? 'border-accent bg-accent-soft text-accent'
            : 'border-line bg-surface text-ink-3 hover:text-ink'
        "
        @click="setType(f.value)"
      >
        {{ f.label }}
      </button>
    </div>

    <p v-if="error" class="rounded-lg bg-danger-soft px-3.5 py-2.5 text-[13px] text-danger">
      {{ error }}
    </p>

    <SkeletonList v-else-if="loading" :groups="1" :per-group="4" />

    <template v-else-if="searched">
      <p class="t-rule mb-2">
        {{ items.length ? `找到 ${total} 条` : '没有匹配的内容' }}
      </p>

      <EmptyState
        v-if="!items.length"
        art="search"
        title="什么都没找到"
        hint="两个及以上的词走全文索引，单个字会退化成模糊匹配 —— 慢一些，但也能找到。"
      />

      <div v-else class="border-line-soft border-t">
        <EntryCard
          v-for="entry in items"
          :key="entry.id"
          :entry="entry"
          :selected="entry.id === ui.selectedId"
          :highlight="keyword"
          @open="ui.select(entry)"
          @toggle-star="ops.toggleStar(entry)"
          @toggle-archive="ops.toggleArchive(entry)"
          @delete="ops.remove(entry)"
        />
      </div>
    </template>

    <EmptyState
      v-else
      art="search"
      title="想找点什么？"
      hint="搜的是原文、标题和 AI 摘要。链接的域名和标签也在里面。"
    />
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError } from '../api/http'
import type { Entry } from '../types'
import { TYPE_FILTERS } from '../utils/entryMeta'
import { useEntryOps } from '../composables/useEntryOps'
import { useUiStore } from '../stores/ui'
import EntryCard from '../components/EntryCard.vue'
import EmptyState from '../components/EmptyState.vue'
import SkeletonList from '../components/SkeletonList.vue'
import Icon from '../components/Icon.vue'

const route = useRoute()
const router = useRouter()
const ui = useUiStore()
const ops = useEntryOps()

const keyword = ref(typeof route.query.q === 'string' ? route.query.q : '')
const type = ref(typeof route.query.type === 'string' ? route.query.type : '')
const items = ref<Entry[]>([])
const total = ref(0)
const loading = ref(false)
const searched = ref(false)
const error = ref('')
const input = ref<HTMLInputElement | null>(null)

let timer: ReturnType<typeof setTimeout> | null = null

/** 防抖 350ms：中文输入时每个字都会触发 input，不打散的话请求量很吓人 */
function schedule() {
  if (timer) clearTimeout(timer)
  timer = setTimeout(run, 350)
}

function setType(next: string) {
  type.value = next
  run()
}

async function run() {
  const q = keyword.value.trim()
  router.replace({ path: '/search', query: { ...(q ? { q } : {}), ...(type.value ? { type: type.value } : {}) } })

  if (!q) {
    items.value = []
    searched.value = false
    total.value = 0
    return
  }

  loading.value = true
  error.value = ''
  try {
    const page = await api.entries({ q, type: type.value || undefined, size: 50 })
    items.value = page.items
    total.value = page.total
    searched.value = true
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '搜索失败'
  } finally {
    loading.value = false
  }
}

function clear() {
  keyword.value = ''
  if (timer) clearTimeout(timer)
  run()
}

// 详情面板改了条目，结果列表同步
watch(
  () => ui.entryChange,
  (change) => {
    if (!change) return
    const index = items.value.findIndex((e) => e.id === change.id)
    if (index < 0) return
    if (change.kind === 'delete' || change.kind === 'archive') {
      items.value.splice(index, 1)
      total.value = Math.max(total.value - 1, 0)
    } else if (change.kind === 'star' && change.starred !== undefined) {
      items.value[index].starred = change.starred
    }
  },
)

onMounted(async () => {
  await nextTick()
  // 手机上自动聚焦会弹起键盘，比较打扰，只在宽屏聚焦
  if (window.innerWidth >= 768) input.value?.focus()
  if (keyword.value.trim()) run()
})

// 地址栏 ?q= 变化时同步（比如从别处粘贴链接进来）
watch(
  () => route.query.q,
  (q) => {
    if (typeof q === 'string' && q !== keyword.value) {
      keyword.value = q
      run()
    }
  },
)
</script>
