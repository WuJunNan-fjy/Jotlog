<template>
  <div class="pt-1" aria-hidden="true">
    <div v-for="g in groups" :key="g" class="mb-1">
      <div class="skeleton mb-3 h-3 w-16 rounded"></div>
      <div v-for="i in perGroup" :key="i" class="flex gap-3 border-line-soft border-b py-4">
        <div class="skeleton mt-1 h-3 w-8 shrink-0 rounded"></div>
        <div class="min-w-0 flex-1 space-y-2">
          <div class="skeleton h-3.5 rounded" :style="{ width: lineWidth(g, i, 0) }"></div>
          <div class="skeleton h-3.5 rounded" :style="{ width: lineWidth(g, i, 1) }"></div>
          <div class="skeleton h-3 w-24 rounded"></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 骨架屏。
 *
 * 宽度用固定序列而不是随机数 —— 随机数每次渲染都变，看起来像在闪，
 * 反而比转圈更烦。固定几档宽度足够像"一段文字"了。
 */
const props = withDefaults(defineProps<{ groups?: number; perGroup?: number }>(), {
  groups: 2,
  perGroup: 3,
})

const WIDTHS = ['92%', '64%', '78%', '48%', '85%', '56%']

function lineWidth(g: number, i: number, line: number): string {
  return WIDTHS[(g * props.perGroup + i + line) % WIDTHS.length]
}
</script>
