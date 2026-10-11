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
        @paste="onPaste"
        @keydown.ctrl.enter.prevent="submit"
        @keydown.meta.enter.prevent="submit"
        @keydown.esc.prevent="blurBox"
      />

      <!-- 待传附件：图片给缩略图，文件给横条 chip -->
      <div v-if="pendingFiles.length" class="flex flex-wrap gap-2 px-3 pb-2.5">
        <div
          v-for="f in pendingFiles"
          :key="f.key"
          class="group relative flex items-center"
          :class="f.isImage ? 'h-16 w-16' : ''"
        >
          <img
            v-if="f.isImage"
            :src="f.previewUrl ?? undefined"
            class="h-16 w-16 rounded-[3px] border border-line object-cover"
            alt=""
          />
          <span
            v-else
            class="chip max-w-[180px] bg-paper-sunken text-ink-2"
          >
            <Icon name="file" :size="12" />
            <span class="truncate">{{ f.file.name }}</span>
          </span>
          <button
            type="button"
            class="btn-icon absolute -top-2 -right-2 h-5 w-5 rounded-full border border-line bg-paper text-ink-3"
            aria-label="移除附件"
            @click="removeAt(f.key)"
          >
            <Icon name="x" :size="11" />
          </button>
        </div>
      </div>

      <div class="border-line-soft flex items-center gap-3 border-t px-3 py-2">
        <!-- 类型预览：还没提交就告诉用户"这条会被当成什么"。
             不为准确，为的是让"粘贴一个链接进去"这件事有反馈 -->
        <span v-if="preview" class="chip bg-paper-sunken text-ink-3">
          <Icon :name="preview.icon" :size="12" />
          {{ preview.label }}
        </span>

        <!-- 添加附件：label 触发隐藏的 file input，手机上会直接弹出相册/文件选择 -->
        <label class="btn-icon cursor-pointer" title="添加图片或文件">
          <Icon name="paperclip" :size="16" />
          <input
            ref="fileInput"
            type="file"
            multiple
            class="hidden"
            @change="onPicked(($event.target as HTMLInputElement).files)"
          />
        </label>

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
import { useComposeAttachments } from '../composables/useComposeAttachments'
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
const fileInput = ref<HTMLInputElement | null>(null)
const {
  files: pendingFiles,
  addFiles,
  removeAt,
  clear,
  uploadAll,
  fallbackText,
} = useComposeAttachments()

const canSubmit = computed(
  () => (text.value.trim().length > 0 || pendingFiles.value.length > 0) && !busy.value,
)

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

/** 文件选择框选中后入队，并把 input 的值清掉，否则同一个文件再选一次不触发 change。 */
function onPicked(list: FileList | null) {
  if (!list) return
  const rejected = addFiles(list)
  rejected.forEach((r) => toast.push(r, 'danger'))
  if (fileInput.value) fileInput.value.value = ''
}

/** 直接往输入框里粘贴截图（微信截图、手机相册粘贴）时，图片走附件而不是变成文件名。 */
function onPaste(e: ClipboardEvent) {
  const pasted = Array.from(e.clipboardData?.files ?? [])
  if (pasted.length === 0) return
  e.preventDefault()
  addFiles(pasted).forEach((r) => toast.push(r, 'danger'))
}

async function submit() {
  if (!canSubmit.value) return
  busy.value = true
  error.value = ''
  try {
    // 没写字只发了图：用文件名拼一条正文，raw_input 不允许为空
    const body = text.value.trim() || fallbackText()
    const result = await api.createEntry(body)
    let failed = 0
    if (pendingFiles.value.length) {
      failed = await uploadAll(result.id)
    }
    text.value = ''
    clear()
    await nextTick()
    autoGrow()
    stats.onCreated()
    if (failed > 0) {
      toast.push(`已记下，但有 ${failed} 个附件没传上去`, 'danger')
    } else {
      toast.push(result.duplicate ? '这条之前记过了，已再记一条' : '已记下', 'success')
    }
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
