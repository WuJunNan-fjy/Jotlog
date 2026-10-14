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

      <!-- 素材链接：一条记录里抽出的全部链接，都抓过什么、抓到没有 -->
      <section v-if="linksList.length" class="mt-4">
        <p class="mb-1.5 flex items-center gap-1.5 text-[11.5px] tracking-wide text-ink-3">
          <Icon name="link" :size="13" />
          链接 · {{ linksList.length }}
        </p>
        <div class="space-y-1.5">
          <a
            v-for="l in linksList"
            :key="l.id"
            :href="l.url"
            target="_blank"
            rel="noopener noreferrer"
            class="flex items-center gap-2.5 rounded-lg border border-line-soft px-3 py-2.5 transition-colors hover:border-accent hover:bg-accent-soft/40"
          >
            <span
              class="grid h-7 w-7 shrink-0 place-items-center rounded-md border"
              :style="{ color: kindColor(l.kind), borderColor: `${kindColor(l.kind)}55` }"
            >
              <Icon :name="kindIcon(l.kind)" :size="13" />
            </span>
            <span class="min-w-0 flex-1">
              <span class="block truncate text-[13px] text-ink">{{
                l.fetchedTitle || hostLabel(l.url, l.domain)
              }}</span>
              <span class="block truncate text-[11px] text-ink-3">{{ l.url }}</span>
            </span>
            <Icon
              v-if="l.fetchStatus === 'failed'"
              name="refresh"
              :size="13"
              class="shrink-0 text-danger"
            />
            <Icon v-else name="external" :size="14" class="shrink-0 text-ink-3" />
          </a>
        </div>
      </section>

      <!-- 整理：分类与标签。AI 只做建议，采纳和修改永远在人这一侧 -->
      <section class="mt-4 space-y-2.5">
        <p class="flex items-center gap-1.5 text-[11.5px] tracking-wide text-ink-3">
          <Icon name="tag" :size="13" />
          整理
        </p>

        <!-- AI 觉得现有分类都不合适，建议了一个新名字：一键采纳落库 -->
        <div
          v-if="entry.suggestedCategory"
          class="flex items-center justify-between gap-2 rounded-lg border border-brass/40 bg-brass-soft/50 px-3 py-2"
        >
          <span class="text-[12.5px] text-ink-2">
            AI 建议新分类「<span class="font-medium">{{ entry.suggestedCategory }}</span>」
          </span>
          <button
            class="btn btn-ghost px-2 py-1 text-[12px]"
            :disabled="categoryBusy"
            @click="acceptSuggestion"
          >
            采纳
          </button>
        </div>

        <div class="flex items-center gap-2">
          <CategoryDot
            :color="
              categoryStore.categories.find((c) => c.id === selectedCategoryId)?.color ?? null
            "
          />
          <select
            v-model.number="selectedCategoryId"
            class="field flex-1 py-1.5 text-[13px]"
            :disabled="categoryBusy"
            @change="saveCategory"
          >
            <option :value="null">待分类</option>
            <option v-for="c in categoryStore.categories" :key="c.id" :value="c.id">
              {{ c.name }}
            </option>
          </select>
        </div>

        <TagEditor v-model="editingTags" />
        <div class="flex items-center justify-between gap-2">
          <button
            class="btn btn-ghost px-2.5 py-1 text-[12.5px]"
            :disabled="!tagsDirty || tagsBusy"
            @click="saveTags"
          >
            {{ tagsBusy ? '保存中…' : '保存标签' }}
          </button>
          <button
            class="btn btn-quiet px-2.5 py-1 text-[12.5px]"
            :disabled="repolishBusy"
            @click="repolish"
          >
            <Icon name="refresh" :size="13" />
            {{ repolishBusy ? '已入队' : '重新分析' }}
          </button>
        </div>
        <p
          v-if="entry.aiStatus === 'pending' || entry.aiStatus === 'processing'"
          class="flex items-center gap-1.5 text-[12px] text-ink-3"
        >
          <span class="h-[5px] w-[5px] animate-pulse rounded-full bg-ink-3" />
          AI 正在整理这条内容…
        </p>
        <p v-else-if="entry.aiStatus === 'failed'" class="text-[12px] text-danger">
          上次整理失败了，可以点"重新分析"再试一次
        </p>
      </section>

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
import type { AttachmentMeta, Entry, EntryLink } from '../types'
import { api, ApiError } from '../api/http'
import { downloadAttachment } from '../api/attachmentCache'
import { useToast } from '../composables/useToast'
import { formatFull, dayNumber, hostLabel, monthYear } from '../utils/format'
import { looksLikeMarkdown } from '../utils/markdown'
import { aiStatusLabel, sourceLabel as sourceText } from '../utils/entryMeta'
import { useEntryOps } from '../composables/useEntryOps'
import { useUiStore } from '../stores/ui'
import { useCategoryStore } from '../stores/categories'
import AttachmentImage from './AttachmentImage.vue'
import CategoryDot from './CategoryDot.vue'
import Icon from './Icon.vue'
import Lightbox from './Lightbox.vue'
import MarkdownView from './MarkdownView.vue'
import TagEditor from './TagEditor.vue'
import TypeBadge from './TypeBadge.vue'

const props = withDefaults(defineProps<{ entry: Entry; closable?: boolean }>(), { closable: false })

const emit = defineEmits<{ close: [] }>()

const ops = useEntryOps()
const toast = useToast()
const ui = useUiStore()
const categoryStore = useCategoryStore()
categoryStore.load().catch(() => {})

const confirmDelete = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

const sourceLabel = computed(() => sourceText(props.entry.source))

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

/* ---------- 链接素材 / 分类 / 标签 ---------- */

const links = ref<EntryLink[]>([])
/** 编辑中的标签副本，保存后才落库 —— 不边打字边发请求 */
const editingTags = ref<string[]>([])
const selectedCategoryId = ref<number | null>(null)
const categoryBusy = ref(false)
const tagsBusy = ref(false)
const repolishBusy = ref(false)

const tagsDirty = computed(
  () =>
    editingTags.value.length !== props.entry.tags.length ||
    editingTags.value.some((t, i) => t !== props.entry.tags[i]),
)

/** 优先展示 entry_links 的多链接；V3 之前的老条目没有登记，兜底用主链接 */
const linksList = computed<EntryLink[]>(() => {
  if (links.value.length) return links.value
  return props.entry.url
    ? [
        {
          id: -1,
          url: props.entry.url,
          domain: props.entry.domain,
          kind: 'WEB',
          fetchedTitle: props.entry.title,
          fetchStatus: 'legacy',
        },
      ]
    : []
})

const KIND_COLORS: Record<string, string> = {
  REPO: '#5B8A72',
  VIDEO: '#A5A58D',
  NPM: '#B08968',
  WEB: '#9a9a8f',
}
const KIND_ICONS: Record<string, string> = {
  REPO: 'repo',
  VIDEO: 'video',
  NPM: 'file',
  WEB: 'link',
}
function kindColor(kind: string) {
  return KIND_COLORS[kind] ?? KIND_COLORS.WEB
}
function kindIcon(kind: string) {
  return KIND_ICONS[kind] ?? 'link'
}

async function saveCategory() {
  categoryBusy.value = true
  try {
    const res = await api.setEntryCategory(props.entry.id, selectedCategoryId.value)
    props.entry.categoryId = res.categoryId
    props.entry.categoryName =
      categoryStore.categories.find((c) => c.id === res.categoryId)?.name ?? null
    props.entry.suggestedCategory = null
    categoryStore.load(true).catch(() => {})
    // 当前视图可能正按分类过滤，归类后让列表重新对齐
    ui.bumpReload()
    toast.push('分类已更新', 'success')
  } catch (e) {
    toast.push(e instanceof ApiError ? e.message : '分类没改成', 'danger')
    selectedCategoryId.value = props.entry.categoryId
  } finally {
    categoryBusy.value = false
  }
}

async function acceptSuggestion() {
  categoryBusy.value = true
  try {
    const res = await api.setEntryCategory(props.entry.id, null, true)
    await categoryStore.load(true)
    props.entry.categoryId = res.categoryId
    props.entry.categoryName =
      categoryStore.categories.find((c) => c.id === res.categoryId)?.name ?? null
    props.entry.suggestedCategory = null
    selectedCategoryId.value = res.categoryId
    ui.bumpReload()
    toast.push('已采纳建议分类', 'success')
  } catch (e) {
    toast.push(e instanceof ApiError ? e.message : '采纳失败', 'danger')
  } finally {
    categoryBusy.value = false
  }
}

async function saveTags() {
  tagsBusy.value = true
  try {
    const res = await api.setEntryTags(props.entry.id, editingTags.value)
    props.entry.tags = res.tags
    editingTags.value = [...res.tags]
    ui.bumpReload()
    toast.push('标签已保存', 'success')
  } catch (e) {
    toast.push(e instanceof ApiError ? e.message : '标签没保存成', 'danger')
  } finally {
    tagsBusy.value = false
  }
}

async function repolish() {
  repolishBusy.value = true
  try {
    await api.repolish(props.entry.id)
    props.entry.aiStatus = 'pending'
    toast.push('已重新排队，稍等片刻', 'success')
  } catch (e) {
    toast.push(e instanceof ApiError ? e.message : '没有入队', 'danger')
  } finally {
    repolishBusy.value = false
  }
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
    links.value = []
    selectedCategoryId.value = props.entry.categoryId
    // 优先结构化标签；老条目回退 aiTags 快照
    editingTags.value = props.entry.tags.length
      ? [...props.entry.tags]
      : (props.entry.aiTags ?? '')
          .split(/[,，\s]+/)
          .map((t) => t.trim())
          .filter(Boolean)
    try {
      attachments.value = await api.entryAttachments(props.entry.id)
    } catch {
      // 附件拉不到不影响正文阅读，静默降级成"没有附件"
    }
    try {
      links.value = await api.entryLinks(props.entry.id)
    } catch {
      // 链接列表拉不到就走 entry.url 兜底
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
