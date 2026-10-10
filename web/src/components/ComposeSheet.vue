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
              @keydown.ctrl.enter.prevent="submit"
              @keydown.meta.enter.prevent="submit"
            />

            <div class="mt-3 flex items-center gap-3">
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
              手机上是点「记下」，电脑上可以 Ctrl + Enter。
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

const canSubmit = computed(() => text.value.trim().length > 0 && !busy.value)

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

async function submit() {
  if (!canSubmit.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await api.createEntry(text.value.trim())
    stats.onCreated()
    toast.push(result.duplicate ? '这条之前记过了，已再记一条' : '已记下', 'success')
    close(true)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '没记成，稍后再试'
  } finally {
    busy.value = false
  }
}

function close(saved = false) {
  visible.value = false
  setTimeout(() => emit('close', saved), 200)
}
</script>
