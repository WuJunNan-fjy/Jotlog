<template>
  <article
    class="group border-line-soft relative cursor-pointer border-b transition-colors last:border-b-0"
    :class="selected ? 'bg-accent-soft/45' : 'hover:bg-surface-hover'"
    @click="emit('open')"
  >
    <!-- 选中标记：一根竖线，不用边框框住整行 —— 框住会让列表变成一堆卡片，扫读变慢 -->
    <span
      v-if="selected"
      class="absolute top-2 bottom-2 left-0 w-[2.5px] rounded-r bg-accent"
      aria-hidden="true"
    ></span>

    <div class="flex gap-3 py-3.5 pr-3 pl-4">
      <!-- 时间单独一列：扫读时能快速定位"这是什么时候记的" -->
      <div class="w-9 shrink-0 pt-[3px] text-right md:w-11">
        <time class="tabular text-[12px] text-ink-3">{{ time }}</time>
      </div>

      <div class="min-w-0 flex-1">
        <!-- 分段渲染而不是 v-html：见 utils/highlight.ts 里的说明，这是安全底线 -->
        <p class="prose-entry text-[15px] text-ink">
          <template v-for="(seg, i) in rawSegments" :key="i"><mark
              v-if="seg.hit"
              class="rounded-[2px] bg-brass-soft px-[1px] text-ink"
              >{{ seg.text }}</mark
            ><template v-else>{{ seg.text }}</template></template>
        </p>

        <!-- AI 补充区。左边一道细线 + 弱化字色，
             让人一眼分清"我写的"和"机器补的" —— 这是整个产品的立身之本 -->
        <p
          v-if="entry.aiSummary"
          class="mt-2 border-l-2 border-line pl-3 text-[13.5px] leading-relaxed text-ink-2"
        >
          {{ entry.aiSummary }}
        </p>
        <p v-else-if="entry.title" class="mt-1.5 truncate text-[13.5px] text-ink-2">
          {{ entry.title }}
        </p>

        <!-- 链接：域名前加一块首字母色块，替代 favicon。
             不请求第三方 favicon 服务 —— 那等于告诉别人你收藏了什么 -->
        <a
          v-if="entry.url"
          :href="entry.url"
          target="_blank"
          rel="noopener noreferrer"
          class="mt-2.5 inline-flex max-w-full items-center gap-2 text-[13px] text-accent"
          @click.stop
        >
          <span
            class="grid h-[18px] w-[18px] shrink-0 place-items-center rounded bg-accent-soft text-[10px] font-medium text-accent"
          >
            {{ hostInitial }}
          </span>
          <span class="truncate">{{ host }}</span>
        </a>

        <div class="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1">
          <TypeBadge :type="entry.entryType" />
          <span v-for="tag in tags" :key="tag" class="chip bg-paper-sunken text-ink-3">
            {{ tag }}
          </span>
          <span class="text-[11.5px] text-ink-4">{{ sourceLabel }}</span>
        </div>
      </div>

      <!-- 操作区。
           移动端只留星标 —— 它是唯一"想起来就要随手点一下"的动作，
           归档和删除都在详情面板里，那里有完整信息，不容易误点。
           桌面 hover 才出现，但选中时和键盘聚焦时要能看见。 -->
      <div
        class="flex shrink-0 items-start gap-0.5 transition-opacity md:opacity-0 md:group-hover:opacity-100 md:group-focus-within:opacity-100"
        :class="selected ? 'md:opacity-100' : ''"
      >
        <template v-if="!confirming">
          <button
            class="btn-icon h-8 w-8"
            :class="entry.starred ? 'text-brass' : ''"
            :title="entry.starred ? '取消星标' : '加星标'"
            :aria-pressed="entry.starred"
            @click.stop="emit('toggleStar')"
          >
            <Icon
              name="star"
              :size="16"
              :class="entry.starred ? 'fill-current' : ''"
            />
          </button>

          <button
            class="btn-icon hidden h-8 w-8 md:inline-flex"
            :title="entry.archived ? '取消归档' : '归档'"
            @click.stop="emit('toggleArchive')"
          >
            <Icon name="archive" :size="16" />
          </button>

          <button
            class="btn-icon hidden h-8 w-8 hover:text-danger md:inline-flex"
            title="删除"
            @click.stop="askDelete"
          >
            <Icon name="trash" :size="16" />
          </button>
        </template>

        <template v-else>
          <!-- 二次确认就地展开，不用 confirm 对话框：
               原生 confirm 会打断整个页面，且样式跟应用完全无关 -->
          <button
            class="rounded px-1.5 py-1 text-[12px] text-danger hover:bg-danger-soft"
            @click.stop="doDelete"
          >
            删除
          </button>
          <button
            class="rounded px-1.5 py-1 text-[12px] text-ink-3 hover:bg-paper-sunken"
            @click.stop="confirming = false"
          >
            取消
          </button>
        </template>
      </div>
    </div>
  </article>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import type { Entry } from '../types'
import { formatTime, hostLabel } from '../utils/format'
import { splitHighlight } from '../utils/highlight'
import { sourceLabel as sourceText } from '../utils/entryMeta'
import Icon from './Icon.vue'
import TypeBadge from './TypeBadge.vue'

const props = defineProps<{ entry: Entry; selected?: boolean; highlight?: string }>()

const emit = defineEmits<{
  open: []
  toggleStar: []
  toggleArchive: []
  delete: []
}>()

const confirming = ref(false)
let resetTimer: ReturnType<typeof setTimeout> | null = null

const time = computed(() => formatTime(props.entry.createdAt))
const rawSegments = computed(() => splitHighlight(props.entry.rawInput, props.highlight ?? ''))
const host = computed(() => hostLabel(props.entry.url ?? '', props.entry.domain))
const hostInitial = computed(() => host.value.slice(0, 1).toUpperCase())
const sourceLabel = computed(() => sourceText(props.entry.source))

/** aiTags 是逗号或空格分隔的一串，页面上一个一个显示成小药丸 */
const tags = computed(() =>
  (props.entry.aiTags ?? '')
    .split(/[,，\s]+/)
    .map((t) => t.trim())
    .filter(Boolean)
    .slice(0, 3),
)

function askDelete() {
  confirming.value = true
  // 3 秒没点就自己收起来。一直挂着"删除"按钮，迟早会被误点
  if (resetTimer) clearTimeout(resetTimer)
  resetTimer = setTimeout(() => {
    confirming.value = false
  }, 3000)
}

function doDelete() {
  confirming.value = false
  if (resetTimer) clearTimeout(resetTimer)
  emit('delete')
}

onBeforeUnmount(() => {
  if (resetTimer) clearTimeout(resetTimer)
})
</script>
