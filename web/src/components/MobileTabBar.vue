<template>
  <!--
    只在 < 768 出现。四项而不是五项：
    "设置"移到了顶栏的头像菜单里 —— 它是低频动作，不该占掉底部条 20% 的宽度。
    底部条留给"每天要来回切"的四个地方，拇指够得着比功能齐全重要。
  -->
  <nav
    class="safe-b border-line bg-paper/95 flex shrink-0 items-stretch border-t md:hidden"
    aria-label="主导航"
  >
    <RouterLink
      v-for="item in nav"
      :key="item.to"
      :to="item.to"
      class="flex flex-1 flex-col items-center justify-center gap-1 py-2 transition-colors"
      :class="isActive(item) ? 'text-accent' : 'text-ink-3'"
    >
      <span class="relative">
        <Icon :name="item.icon" :size="20" />
        <span
          v-if="item.badge"
          class="tabular absolute -top-1.5 -right-2.5 min-w-[16px] rounded-full bg-paper-sunken px-1 text-[10px] leading-4 text-ink-3"
        >
          {{ item.badge }}
        </span>
      </span>
      <span class="text-[11px] leading-none">{{ item.label }}</span>
    </RouterLink>
  </nav>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import Icon from './Icon.vue'
import { useStatsStore } from '../stores/stats'

const route = useRoute()
const stats = useStatsStore()

const nav = computed(() => [
  { to: '/', label: '时间线', icon: 'timeline', exact: true, badge: null as number | null },
  { to: '/starred', label: '星标', icon: 'star', badge: stats.data?.starred ?? null },
  { to: '/archive', label: '归档', icon: 'archive', badge: stats.data?.archived ?? null },
  { to: '/search', label: '搜索', icon: 'search', badge: null as number | null },
])

function isActive(item: { to: string; exact?: boolean }): boolean {
  if (item.exact) return route.path === item.to
  return route.path === item.to || route.path.startsWith(item.to + '/')
}
</script>
