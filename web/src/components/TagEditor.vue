<template>
  <div
    class="flex flex-wrap items-center gap-1.5 rounded-lg border border-line-soft bg-paper px-2.5 py-2 transition-colors focus-within:border-accent"
    @click="inputEl?.focus()"
  >
    <span
      v-for="tag in modelValue"
      :key="tag"
      class="chip bg-paper-sunken text-ink-2 gap-1"
    >
      #{{ tag }}
      <button
        type="button"
        class="text-ink-4 transition-colors hover:text-danger"
        :title="`移除标签 ${tag}`"
        @click.stop="removeTag(tag)"
      >
        <Icon name="x" :size="11" />
      </button>
    </span>

    <input
      ref="inputEl"
      v-model="draft"
      class="min-w-[7rem] flex-1 bg-transparent py-0.5 text-[13px] text-ink outline-none placeholder:text-ink-4"
      :placeholder="modelValue.length ? '' : '加标签，回车确认'"
      :list="`tag-suggest-${uid}`"
      @keydown="onKeydown"
      @focus="loadSuggestions"
      @input="loadSuggestions"
    />

    <datalist :id="`tag-suggest-${uid}`">
      <option v-for="s in suggestions" :key="s" :value="s" />
    </datalist>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api/http'
import Icon from './Icon.vue'

const props = withDefaults(defineProps<{ modelValue: string[] }>(), {})

const emit = defineEmits<{
  'update:modelValue': [tags: string[]]
}>()

/** datalist id 需要唯一，页面上理论上只有一个编辑器，但别赌 */
const uid = Math.random().toString(36).slice(2, 8)

const draft = ref('')
const suggestions = ref<string[]>([])
const inputEl = ref<HTMLInputElement | null>(null)

function commit(raw: string) {
  const tag = raw
    .trim()
    .replace(/^#+/, '')
    .slice(0, 16)
  if (!tag || props.modelValue.includes(tag)) {
    draft.value = ''
    return
  }
  if (props.modelValue.length >= 10) return
  emit('update:modelValue', [...props.modelValue, tag])
  draft.value = ''
}

function removeTag(tag: string) {
  emit(
    'update:modelValue',
    props.modelValue.filter((t) => t !== tag),
  )
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' || e.key === ',' || e.key === '，') {
    e.preventDefault()
    commit(draft.value)
  } else if (e.key === 'Backspace' && !draft.value && props.modelValue.length) {
    // 输入框为空时 Backspace 删最后一个 —— tag 编辑器的通用手感
    emit('update:modelValue', props.modelValue.slice(0, -1))
  }
}

let suggestTimer: ReturnType<typeof setTimeout> | null = null
async function loadSuggestions() {
  if (suggestTimer) clearTimeout(suggestTimer)
  suggestTimer = setTimeout(async () => {
    try {
      const list = await api.tagsSuggest(draft.value.trim())
      suggestions.value = list.filter((t) => !props.modelValue.includes(t)).slice(0, 8)
    } catch {
      // 联想失败不影响手动输入
    }
  }, 200)
}
</script>
