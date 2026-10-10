<template>
  <!--
    刊物刊头。整个产品的记忆点：
    双发丝线夹住 Fraunces 字标 + 出版信息，下面一排大号印数。
    只在桌面/平板出现（手机由 MobileTopBar + 紧凑标题承担）。
  -->
  <header>
    <div class="anim-fade border-t-[2px] border-ink" style="animation-delay: 0ms"></div>

    <div
      class="anim-fade flex items-end justify-between gap-4 py-3"
      style="animation-delay: 70ms"
    >
      <div>
        <p class="font-display text-[30px] font-medium leading-none tracking-[-0.02em]">
          Jotlog
        </p>
        <p class="mt-1.5 font-mono text-[10.5px] tracking-[0.22em] text-ink-3">
          私人隨記 · A PRIVATE REGISTER
        </p>
      </div>
      <p class="font-mono pb-[2px] text-[11px] tracking-[0.14em] text-ink-3">
        {{ todayDotted }}
      </p>
    </div>

    <div class="border-t border-ink"></div>

    <!-- 印数：Fraunces 大号等宽数字 + 小字注 -->
    <dl class="anim-fade grid grid-cols-4 divide-x divide-line" style="animation-delay: 150ms">
      <div v-for="cell in cells" :key="cell.key" class="px-3 py-3 first:pl-0 last:pr-0">
        <dd class="font-display tabular text-[26px] leading-none text-ink">
          {{ cell.value ?? '—' }}
        </dd>
        <dt class="mt-1.5 font-mono text-[10px] tracking-[0.2em] text-ink-3">
          {{ cell.label }}
        </dt>
      </div>
    </dl>

    <div class="anim-fade border-t border-line" style="animation-delay: 210ms"></div>
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useStatsStore } from '../stores/stats'

const stats = useStatsStore()

const cells = computed(() => [
  { key: 'total', label: '全部 ENTRIES', value: stats.data?.total ?? null },
  { key: 'today', label: '今天 TODAY', value: stats.data?.today ?? null },
  { key: 'week', label: '本周 WEEK', value: stats.data?.week ?? null },
  { key: 'star', label: '星标 STARS', value: stats.data?.starred ?? null },
])

/** 右上角出版日期：2026.10.10 */
const todayDotted = computed(() => {
  const n = new Date()
  const p = (v: number) => String(v).padStart(2, '0')
  return `${n.getFullYear()}.${p(n.getMonth() + 1)}.${p(n.getDate())}`
})
</script>
