<template>
  <div class="mb-8">
    <textarea
      ref="box"
      v-model="text"
      rows="2"
      class="field resize-none leading-relaxed"
      placeholder="记点什么…"
      @input="autoGrow"
      @keydown.ctrl.enter.prevent="submit"
      @keydown.meta.enter.prevent="submit"
    />

    <div class="mt-2.5 flex items-center justify-between gap-3">
      <span class="text-[12px] text-ink-3">
        <template v-if="busy">记下中…</template>
        <template v-else-if="justSaved">已记下</template>
        <template v-else>Ctrl + Enter 记下</template>
      </span>
      <button class="btn-primary px-5 py-2" :disabled="!canSubmit" @click="submit">
        <Icon name="plus" class="h-4 w-4" />
        记下
      </button>
    </div>

    <p v-if="error" class="mt-2 text-[13px] text-danger">{{ error }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { api, ApiError } from '../api/http'
import Icon from './Icon.vue'

const emit = defineEmits<{ created: [] }>()

const text = ref('')
const busy = ref(false)
const justSaved = ref(false)
const error = ref('')
const box = ref<HTMLTextAreaElement | null>(null)

const canSubmit = computed(() => text.value.trim().length > 0 && !busy.value)

/** 内容变高就跟着长，最多 8 行，再长就自己滚。 */
function autoGrow() {
  const el = box.value
  if (!el) return
  el.style.height = 'auto'
  const max = 8 * 24 + 20
  el.style.height = `${Math.min(el.scrollHeight, max)}px`
}

async function submit() {
  if (!canSubmit.value) return
  busy.value = true
  error.value = ''
  try {
    await api.createEntry(text.value.trim())
    text.value = ''
    await nextTick()
    autoGrow()
    justSaved.value = true
    setTimeout(() => (justSaved.value = false), 2000)
    emit('created')
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '没记成，稍后再试'
  } finally {
    busy.value = false
  }
}
</script>
