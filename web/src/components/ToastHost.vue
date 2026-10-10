<template>
  <!-- 移动端要抬到底部 tab 条上方，否则会被压住 -->
  <div
    class="pointer-events-none fixed inset-x-0 bottom-0 z-[60] flex flex-col items-center gap-2 px-5 pb-24 md:items-end md:px-6 md:pb-6"
  >
    <TransitionGroup
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="opacity-0 translate-y-2 md:translate-x-2"
      leave-active-class="transition duration-150 ease-in absolute"
      leave-to-class="opacity-0 translate-y-1"
      move-class="transition duration-200"
    >
      <div
        v-for="t in items"
        :key="t.id"
        class="pointer-events-auto flex max-w-full items-center gap-3 rounded-lg border px-3.5 py-2.5 shadow-md"
        :class="toneClass(t.tone)"
      >
        <Icon :name="toneIcon(t.tone)" :size="15" class="shrink-0" />
        <span class="text-[13.5px] leading-snug">{{ t.text }}</span>
        <button
          v-if="t.action"
          class="shrink-0 text-[13px] font-medium underline underline-offset-2"
          @click="runAction(t)"
        >
          {{ t.action.label }}
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup lang="ts">
import { useToast } from '../composables/useToast'
import type { ToastTone } from '../composables/useToast'
import Icon from './Icon.vue'

const { items, dismiss } = useToast()

function toneClass(tone: ToastTone): string {
  if (tone === 'success') return 'border-accent/25 bg-surface text-ink'
  if (tone === 'danger') return 'border-danger/25 bg-surface text-ink'
  return 'border-line bg-surface text-ink'
}

function toneIcon(tone: ToastTone): string {
  if (tone === 'success') return 'check'
  if (tone === 'danger') return 'trash'
  return 'sparkles'
}

function runAction(t: { id: number; action?: { label: string; run: () => void } }) {
  t.action?.run()
  dismiss(t.id)
}
</script>
