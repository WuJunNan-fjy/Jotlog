<template>
  <div class="mb-7">
    <div
      class="card overflow-hidden transition-colors"
      :class="focused ? 'border-ink' : ''"
    >
      <textarea
        ref="box"
        v-model="text"
        rows="2"
        class="w-full resize-none bg-transparent px-4 pt-3.5 pb-2 text-[15px] leading-relaxed text-ink outline-none"
        placeholder="记点什么…"
        @input="autoGrow"
        @focus="focused = true"
        @blur="focused = false"
        @keydown.ctrl.enter.prevent="submit"
        @keydown.meta.enter.prevent="submit"
        @keydown.esc.prevent="blurBox"
      />

      <div class="border-line-soft flex items-center gap-3 border-t px-3 py-2">
        <!-- 类型预览：还没提交就告诉用户"这条会被当成什么"。
             不为准确，为的是让"粘贴一个链接进去"这件事有反馈 -->
        <span v-if="preview" class="chip bg-paper-sunken text-ink-3">
          <Icon :name="preview.icon" :size="12" />
          {{ preview.label }}
        </span>

        <span class="flex-1"></span>

        <span class="hidden text-[12px] text-ink-3 sm:block">
          <kbd class="kbd">Ctrl</kbd>
          <span class="mx-0.5">+</span>
          <kbd class="kbd">Enter</kbd>
          <span class="ml-1">记下</span>
        </span>

        <button class="btn btn-primary px-4 py-1.5" :disabled="!canSubmit" @click="submit">
          <Icon name="plus" :size="16" />
          {{ busy ? '记下中' : '记下' }}
        </button>
      </div>
    </div>

    <p v-if="error" class="mt-2 text-[13px] text-danger">{{ error }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { api, ApiError } from '../api/http'
import { useStatsStore } from '../stores/stats'
import { useUiStore } from '../stores/ui'
import { useToast } from '../composables/useToast'
import { typeMeta } from '../utils/entryMeta'
import Icon from './Icon.vue'

const emit = defineEmits<{ created: [] }>()

const ui = useUiStore()
const stats = useStatsStore()
const toast = useToast()

const text = ref('')
const busy = ref(false)
const focused = ref(false)
const error = ref('')
const box = ref<HTMLTextAreaElement | null>(null)

const canSubmit = computed(() => text.value.trim().length > 0 && !busy.value)

/** 输入内容里出现 URL 时预先猜个类型，跟后端 Extractor 的口径大致对齐 */
const preview = computed(() => {
  const value = text.value.trim()
  if (!value) return null
  const url = /(https?:\/\/[^\s]+)/i.exec(value)?.[1]
  if (!url) return null
  return typeMeta(guessType(url))
})

function guessType(url: string): string {
  if (/github\.com|gitee\.com|gitlab\.com/i.test(url)) return 'repo'
  if (
    /(bilibili\.com|youtube\.com|youtu\.be|v\.qq\.com|douyin\.com|ixigua\.com)/i.test(url)
  )
    return 'video'
  return 'link'
}

/** 内容变高就跟着长，最多 8 行，再长就自己滚。 */
function autoGrow() {
  const el = box.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 8 * 24 + 16)}px`
}

function blurBox() {
  box.value?.blur()
}

async function submit() {
  if (!canSubmit.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await api.createEntry(text.value.trim())
    text.value = ''
    await nextTick()
    autoGrow()
    stats.onCreated()
    toast.push(result.duplicate ? '这条之前记过了，已再记一条' : '已记下', 'success')
    emit('created')
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '没记成，稍后再试'
  } finally {
    busy.value = false
  }
}

// 侧栏"记一笔"和快捷键 n 都打这个计数器，每次都是新值所以能重复触发
watch(
  () => ui.composeFocusSeq,
  async () => {
    await nextTick()
    box.value?.focus()
  },
)
</script>
