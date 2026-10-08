<template>
  <div>
    <div class="relative mb-6">
      <Icon name="search" class="absolute top-1/2 left-3.5 h-4 w-4 -translate-y-1/2 text-ink-3" />
      <input
        ref="input"
        v-model="keyword"
        class="field pl-10"
        placeholder="搜点什么…"
        @input="schedule"
      />
      <button
        v-if="keyword"
        class="absolute top-1/2 right-3 -translate-y-1/2 text-ink-3 hover:text-ink"
        @click="clear"
      >
        <Icon name="x" class="h-4 w-4" />
      </button>
    </div>

    <p v-if="error" class="rounded bg-danger/8 px-3 py-2 text-[13px] text-danger">{{ error }}</p>

    <div v-if="loading" class="py-14 text-center text-[13px] text-ink-3">搜索中…</div>

    <template v-else-if="searched">
      <p class="date-rule mb-1">
        {{ items.length ? `找到 ${total} 条` : '没有匹配的内容' }}
      </p>

      <p v-if="!items.length" class="py-10 text-center text-[13px] leading-relaxed text-ink-3">
        两个及以上的词走全文索引，<br />单个字会退化成模糊匹配，慢一些但也能找到。
      </p>

      <template v-else>
        <EntryCard
          v-for="entry in items"
          :key="entry.id"
          :entry="entry"
          @toggle-star="toggleStar(entry)"
          @toggle-archive="toggleArchive(entry)"
          @delete="remove(entry)"
        />
      </template>
    </template>

    <div v-else class="py-14 text-center">
      <p class="text-[13px] leading-relaxed text-ink-3">
        搜原文、标题和 AI 摘要。<br />
        链接的域名和标签也在里面。
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError } from '../api/http'
import type { Entry } from '../types'
import EntryCard from '../components/EntryCard.vue'
import Icon from '../components/Icon.vue'

const route = useRoute()
const router = useRouter()

const keyword = ref(typeof route.query.q === 'string' ? route.query.q : '')
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

async function run() {
  const q = keyword.value.trim()
  router.replace({ path: '/search', query: q ? { q } : {} })

  if (!q) {
    items.value = []
    searched.value = false
    total.value = 0
    return
  }

  loading.value = true
  error.value = ''
  try {
    const page = await api.entries({ q, size: 50 })
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
  run()
}

async function toggleStar(entry: Entry) {
  await api.updateEntry(entry.id, { starred: !entry.starred })
  entry.starred = !entry.starred
}

async function toggleArchive(entry: Entry) {
  await api.updateEntry(entry.id, { archived: !entry.archived })
  items.value = items.value.filter((x) => x.id !== entry.id)
}

async function remove(entry: Entry) {
  await api.deleteEntry(entry.id)
  items.value = items.value.filter((x) => x.id !== entry.id)
  total.value = Math.max(total.value - 1, 0)
}

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
