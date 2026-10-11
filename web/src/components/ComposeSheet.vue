<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50" role="dialog" aria-modal="true" aria-label="记一笔">
      <Transition
        enter-active-class="transition-opacity duration-200"
        enter-from-class="opacity-0"
        leave-active-class="transition-opacity duration-150"
        leave-to-class="opacity-0"
      >
        <div
          v-if="visible"
          class="absolute inset-0 bg-ink/25 dark:bg-black/50"
          @click="close(false)"
        ></div>
      </Transition>

      <Transition
        enter-active-class="transition duration-300 ease-out"
        enter-from-class="translate-y-full opacity-0 sm:translate-y-3 sm:scale-[0.98]"
        leave-active-class="transition duration-200 ease-in"
        leave-to-class="translate-y-full opacity-0 sm:translate-y-3 sm:scale-[0.98]"
      >
        <div
          v-if="visible"
          class="sheet absolute shadow-lg sm:rounded-xl"
          :class="
            isCompact
              ? 'safe-b right-0 bottom-0 left-0 rounded-t-2xl'
              : 'top-[12vh] left-1/2 w-[560px] max-w-[92vw] -translate-x-1/2'
          "
        >
          <div class="flex items-center justify-between px-5 pt-3.5 pb-2">
            <span class="t-rule">记一笔</span>
            <button class="btn-icon" aria-label="关闭" @click="close(false)">
              <Icon name="x" :size="17" />
            </button>
          </div>

          <div class="px-5 pb-4">
            <textarea
              ref="box"
              v-model="text"
              rows="4"
              class="field resize-none leading-relaxed"
              placeholder="记点什么…"
              @input="autoGrow"
              @paste="onPaste"
              @keydown.ctrl.enter.prevent="submit"
              @keydown.meta.enter.prevent="submit"
            />

            <!-- 待传附件：图片缩略图，文件横条 -->
            <div v-if="pendingFiles.length" class="mt-2.5 flex flex-wrap gap-2">
              <div
                v-for="f in pendingFiles"
                :key="f.key"
                class="relative flex items-center"
                :class="f.isImage ? 'h-16 w-16' : ''"
              >
                <img
                  v-if="f.isImage"
                  :src="f.previewUrl ?? undefined"
                  class="h-16 w-16 rounded-[3px] border border-line object-cover"
                  alt=""
                />
                <span v-else class="chip max-w-[200px] bg-paper-sunken text-ink-2">
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

            <div class="mt-3 flex items-center gap-3">
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
              <span v-if="preview" class="chip bg-paper-sunken text-ink-3">
                <Icon :name="preview.icon" :size="12" />
                {{ preview.label }}
              </span>
              <span class="flex-1"></span>
              <button class="btn btn-ghost" @click="close(false)">取消</button>
              <button class="btn btn-primary" :disabled="!canSubmit" @click="submit">
                <Icon name="plus" :size="16" />
                {{ busy ? '记下中' : '记下' }}
              </button>
            </div>

            <p v-if="error" class="mt-2 text-[13px] text-danger">{{ error }}</p>
            <p v-else class="mt-2.5 text-[12px] text-ink-3">
              可以直接选图或粘贴截图；手机上点「记下」，电脑上可以 Ctrl + Enter。
            </p>
          </div>
        </div>
      </Transition>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { api, ApiError } from '../api/http'
import { useStatsStore } from '../stores/stats'
import { useToast } from '../composables/useToast'
import { useBreakpoint } from '../composables/useBreakpoint'
import { useComposeAttachments } from '../composables/useComposeAttachments'
import { typeMeta } from '../utils/entryMeta'
import Icon from './Icon.vue'

const emit = defineEmits<{ close: [saved: boolean] }>()

const stats = useStatsStore()
const toast = useToast()
const { isCompact } = useBreakpoint()

const text = ref('')
const busy = ref(false)
const error = ref('')
const visible = ref(false)
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

const preview = computed(() => {
  const url = /(https?:\/\/[^\s]+)/i.exec(text.value.trim())?.[1]
  if (!url) return null
  return typeMeta(/github\.com|gitee\.com|gitlab\.com/i.test(url) ? 'repo' : 'link')
})

onMounted(async () => {
  requestAnimationFrame(() => (visible.value = true))
  await nextTick()
  // 手机上不自动聚焦：键盘会顶掉半个屏幕，用户还没决定写什么就被弹上来了
  if (!isCompact.value) box.value?.focus()
})

function autoGrow() {
  const el = box.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 8 * 24 + 16)}px`
}

function onPicked(list: FileList | null) {
  if (!list) return
  const rejected = addFiles(list)
  rejected.forEach((r) => toast.push(r, 'danger'))
  if (fileInput.value) fileInput.value.value = ''
}

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
    const body = text.value.trim() || fallbackText()
    const result = await api.createEntry(body)
    let failed = 0
    if (pendingFiles.value.length) {
      failed = await uploadAll(result.id)
    }
    if (failed > 0) {
      toast.push(`已记下，但有 ${failed} 个附件没传上去`, 'danger')
    } else {
      toast.push(result.duplicate ? '这条之前记过了，已再记一条' : '已记下', 'success')
    }
    stats.onCreated()
    close(true)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '没记成，稍后再试'
  } finally {
    busy.value = false
  }
}

function close(saved = false) {
  visible.value = false
  // 取消时待传文件没有提交，也要把本地预览的 objectURL 释放掉
  clear()
  setTimeout(() => emit('close', saved), 200)
}
</script>
