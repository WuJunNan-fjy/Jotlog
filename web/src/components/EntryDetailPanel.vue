<template>
  <div class="flex h-full min-h-0 flex-col">
    <!-- 头部：日期块 + 关闭（抽屉形态才有） -->
    <header class="border-line-soft flex shrink-0 items-start gap-3 border-b px-5 py-4">
      <!-- 日期做成一块：Fraunces 大日期 + mono 月份，像翻开一页的页眉 -->
      <div class="flex items-baseline gap-2">
        <span class="font-display tabular text-[28px] leading-none text-ink">{{
          dayNumber(entry.createdAt)
        }}</span>
        <span class="font-mono text-[10.5px] tracking-[0.16em] text-ink-3">{{
          monthYear(entry.createdAt)
        }}</span>
      </div>

      <div class="flex-1"></div>

      <button
        v-for="act in quickActions"
        :key="act.key"
        class="btn-icon"
        :class="act.active ? 'text-brass' : ''"
        :title="act.title"
        @click="act.run"
      >
        <Icon :name="act.icon" :size="17" :class="act.active ? 'fill-current' : ''" />
      </button>

      <button v-if="closable" class="btn-icon" title="关闭" @click="emit('close')">
        <Icon name="x" :size="17" />
      </button>
    </header>

    <!-- 内容 -->
    <div class="min-h-0 flex-1 overflow-y-auto px-5 py-4">
      <div class="mb-3 flex flex-wrap items-center gap-2">
        <TypeBadge :type="entry.entryType" />
        <span class="tabular text-[12px] text-ink-3">{{ formatFull(entry.createdAt) }}</span>
        <span class="chip bg-paper-sunken text-ink-3">{{ sourceLabel }}</span>
      </div>

      <!-- 原文区。
           检测到 md 语法时默认渲染（写 # 、写列表的人想要的是结构，不是符号），
           但"原文"永远一键可达 —— 渲染是展示层的 courtesy，
           原样才是产品的承诺。切换是临时的，不持久化：
           下一次该怎样还是怎样，由内容本身决定。 -->
      <div class="relative">
        <button
          v-if="looksMd"
          class="btn-icon absolute top-0 right-0 h-7 w-7"
          :title="rendered ? '查看原文' : '渲染显示'"
          :aria-pressed="rendered"
          @click="rendered = !rendered"
        >
          <Icon :name="rendered ? 'note' : 'sparkles'" :size="14" />
        </button>

        <MarkdownView v-if="looksMd && rendered" :source="entry.rawInput" class="text-[15.5px]" />
        <p v-else class="prose-entry pr-8 text-[15.5px] leading-[1.85] text-ink">
          {{ entry.rawInput }}
        </p>
      </div>

      <!-- 附件。图片给墙（点开全屏看），文件给行（直接下载）。
           列表页只有摘要，全量信息在这里 —— 附件的"家"在详情。 -->
      <section v-if="attachments.length" class="mt-4">
        <p class="mb-1.5 flex items-center gap-1.5 text-[11.5px] tracking-wide text-ink-3">
          <Icon name="file" :size="13" />
          附件 · {{ attachments.length }}
        </p>

        <!-- 图片墙：两列，方形拇指。点开走 lightbox，手机上这比"新标签页打开"友好得多 -->
        <div v-if="images.length" class="mb-2.5 grid grid-cols-2 gap-2">
          <button
            v-for="(img, i) in images"
            :key="img.sha256"
            class="group relative aspect-square overflow-hidden rounded-lg bg-paper-sunken"
            :aria-label="`查看图片 ${img.filename}`"
            @click="lightboxIndex = i"
          >
            <AttachmentImage :sha="img.sha256" size="100%" :alt="img.filename" class="h-full w-full" />
          </button>
        </div>

        <!-- 文件行：图标 + 名字 + 大小。整行可点。
             下载走 fetch + blob：浏览器导航的 <a href> 带不上
             Authorization 头，直接点链接会撞 401 -->
        <button
          v-for="file in files"
          :key="file.sha256"
          class="flex w-full items-center gap-2.5 rounded-lg border border-line-soft px-3 py-2.5 text-left transition-colors hover:border-accent hover:bg-accent-soft/40"
          @click="saveFile(file)"
        >
          <span class="grid h-8 w-8 shrink-0 place-items-center rounded-md bg-paper-sunken text-ink-3">
            <Icon name="file" :size="15" />
          </span>
          <span class="min-w-0 flex-1">
            <span class="block truncate text-[13px] text-ink">{{ file.filename }}</span>
            <span class="tabular block text-[11px] text-ink-3">{{ prettySize(file.sizeBytes) }}</span>
          </span>
          <Icon name="chevron-down" :size="15" class="shrink-0 -rotate-90 text-ink-4" />
        </button>
      </section>

      <!-- AI 补充。
           带图标和标签，明确告诉用户这段不是你写的 ——
           产品承诺过原文永不被改写，界面上就得让人看得出这个承诺被兑现了。 -->
      <section v-if="entry.aiSummary || entry.title" class="mt-4">
        <p class="mb-1.5 flex items-center gap-1.5 text-[11.5px] tracking-wide text-ink-3">
          <Icon name="sparkles" :size="13" />
          AI 补充
        </p>
        <div class="rounded-lg border border-line-soft bg-paper-sunken/60 px-3.5 py-3">
          <p v-if="entry.title" class="mb-1.5 text-[14px] font-medium text-ink-2">
            {{ entry.title }}
          </p>
          <p v-if="entry.aiSummary" class="text-[13.5px] leading-relaxed text-ink-2">
            {{ entry.aiSummary }}
          </p>
        </div>
      </section>

      <!-- 链接 -->
      <a
        v-if="entry.url"
        :href="entry.url"
        target="_blank"
        rel="noopener noreferrer"
        class="mt-4 flex items-center gap-3 rounded-lg border border-line px-3.5 py-3 transition-colors hover:border-accent hover:bg-accent-soft/40"
      >
        <span
          class="grid h-9 w-9 shrink-0 place-items-center rounded-md bg-accent-soft text-[14px] font-medium text-accent"
        >
          {{ hostInitial }}
        </span>
        <span class="min-w-0 flex-1">
          <span class="block truncate text-[13.5px] text-ink">{{ host }}</span>
          <span class="block truncate text-[11.5px] text-ink-3">{{ entry.url }}</span>
        </span>
        <Icon name="external" :size="16" class="shrink-0 text-ink-3" />
      </a>

      <!-- 标签 -->
      <div v-if="tags.length" class="mt-4 flex flex-wrap gap-1.5">
        <span v-for="tag in tags" :key="tag" class="chip bg-paper-sunken text-ink-3">
          {{ tag }}
        </span>
      </div>

      <!-- 元信息：折叠在最后，多数时候没人看，但排查"这条怎么进来的"时很有用 -->
      <dl class="mt-5 border-line-soft space-y-1.5 border-t pt-4 text-[12px]">
        <div class="flex justify-between gap-3">
          <dt class="text-ink-3">来源通道</dt>
          <dd class="text-ink-2">{{ sourceLabel }}</dd>
        </div>
        <div class="flex justify-between gap-3">
          <dt class="text-ink-3">补充状态</dt>
          <dd class="text-ink-2">{{ aiStatusLabel(entry.aiStatus) }}</dd>
        </div>
        <div class="flex justify-between gap-3">
          <dt class="text-ink-3">编号</dt>
          <dd class="tabular text-ink-2">#{{ entry.id }}</dd>
        </div>
      </dl>
    </div>

    <!-- 底部操作 -->
    <footer class="border-line-soft flex shrink-0 items-center gap-1 border-t px-4 py-3">
      <button class="btn btn-quiet flex-1" @click="ops.copyText(entry)">
        <Icon name="copy" :size="16" />
        复制原文
      </button>
      <button class="btn btn-quiet flex-1" @click="ops.toggleArchive(entry)">
        <Icon name="archive" :size="16" />
        {{ entry.archived ? '移出归档' : '归档' }}
      </button>
      <button
        class="btn btn-quiet flex-1 hover:text-danger"
        @click="askDelete"
      >
        <Icon name="trash" :size="16" />
        {{ confirmDelete ? '确认删除' : '删除' }}
      </button>
    </footer>

    <!-- 全屏看图。lightboxIndex 指向 images 数组，跟图片墙同一顺序 -->
    <Lightbox
      v-if="lightboxIndex !== null"
      :shas="lightboxShas"
      :start="lightboxIndex"
      @close="lightboxIndex = null"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AttachmentMeta, Entry } from '../types'
import { api } from '../api/http'
import { downloadAttachment } from '../api/attachmentCache'
import { useToast } from '../composables/useToast'
import { formatFull, dayNumber, hostLabel, monthYear } from '../utils/format'
import { looksLikeMarkdown } from '../utils/markdown'
import { aiStatusLabel, sourceLabel as sourceText } from '../utils/entryMeta'
import { useEntryOps } from '../composables/useEntryOps'
import AttachmentImage from './AttachmentImage.vue'
import Icon from './Icon.vue'
import Lightbox from './Lightbox.vue'
import MarkdownView from './MarkdownView.vue'
import TypeBadge from './TypeBadge.vue'

const props = withDefaults(defineProps<{ entry: Entry; closable?: boolean }>(), { closable: false })

const emit = defineEmits<{ close: [] }>()

const ops = useEntryOps()
const toast = useToast()

const confirmDelete = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

const host = computed(() => hostLabel(props.entry.url ?? '', props.entry.domain))
const hostInitial = computed(() => host.value.slice(0, 1).toUpperCase())
const sourceLabel = computed(() => sourceText(props.entry.source))

const tags = computed(() =>
  (props.entry.aiTags ?? '')
    .split(/[,，\s]+/)
    .map((t) => t.trim())
    .filter(Boolean),
)

/* ---------- Markdown 渲染 / 原文切换 ---------- */

const looksMd = computed(() => looksLikeMarkdown(props.entry.rawInput))
const rendered = ref(true)

const quickActions = computed(() => [
  {
    key: 'star',
    icon: 'star',
    title: props.entry.starred ? '取消星标' : '加星标',
    active: props.entry.starred,
    run: () => ops.toggleStar(props.entry),
  },
])

/* ---------- 附件 ---------- */

const attachments = ref<AttachmentMeta[]>([])
const lightboxIndex = ref<number | null>(null)

/** 全屏查看时的图片 sha 顺序，和图片墙一致，左右切换不跳 */
const lightboxShas = computed(() => images.value.map((a) => a.sha256))

const images = computed(() =>
  attachments.value.filter((a) => (a.mime ?? '').startsWith('image/') && a.mime !== 'image/svg+xml'),
)

const files = computed(() => attachments.value.filter((a) => !images.value.includes(a)))

async function saveFile(file: AttachmentMeta) {
  try {
    await downloadAttachment(file.sha256, file.filename)
  } catch {
    toast.push('没下载成，稍后再试', 'danger')
  }
}

/** 1.2 MB / 340 KB 这种一眼能懂的数 */
function prettySize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`
  return `${bytes} B`
}

// 换条目就重拉附件。附件跟着条目走，不进全局状态 —— 只有详情页用得到
watch(
  () => props.entry.id,
  async () => {
    confirmDelete.value = false
    if (timer) clearTimeout(timer)
    rendered.value = true
    lightboxIndex.value = null
    attachments.value = []
    try {
      attachments.value = await api.entryAttachments(props.entry.id)
    } catch {
      // 附件拉不到不影响正文阅读，静默降级成"没有附件"
    }
  },
  { immediate: true },
)

function askDelete() {
  if (confirmDelete.value) {
    confirmDelete.value = false
    if (timer) clearTimeout(timer)
    ops.remove(props.entry)
    return
  }
  confirmDelete.value = true
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => (confirmDelete.value = false), 3000)
}
</script>
