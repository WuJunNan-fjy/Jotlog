<template>
  <!--
    一份组件覆盖两档宽度：md~xl 是 68px 窄轨（只有图标），xl 以上是 248px 完整侧栏。
    不拆成两个组件，是因为它们的内容完全一样，拆开后每次加导航项都要改两处。
  -->
  <aside
    class="safe-x border-line bg-paper-sunken/60 hidden shrink-0 flex-col border-r md:flex"
    :class="collapsed ? 'w-[68px]' : 'w-[248px]'"
  >
    <!-- 品牌：Fraunces 字标。窄轨只放 J. -->
    <div class="px-4 pt-4 pb-2" :class="collapsed ? 'px-0 text-center' : ''">
      <p
        class="font-display text-[24px] font-medium leading-none tracking-[-0.02em]"
        :class="collapsed ? 'text-[26px]' : ''"
      >
        {{ collapsed ? 'J.' : 'Jotlog' }}
      </p>
      <p v-if="!collapsed" class="mt-1 font-mono text-[9.5px] tracking-[0.22em] text-ink-3">
        私人隨記 · EST. 2026
      </p>
    </div>

    <!-- 记一笔 -->
    <div class="px-3 pt-2 pb-3">
      <button
        class="btn btn-primary w-full"
        :class="collapsed ? 'px-0' : ''"
        :title="collapsed ? '记一笔' : undefined"
        @click="compose"
      >
        <Icon name="plus" :size="17" />
        <span v-if="!collapsed">记一笔</span>
      </button>
    </div>

    <!-- 导航 -->
    <nav class="flex-1 overflow-y-auto px-3 pb-2">
      <RouterLink
        v-for="item in nav"
        :key="item.to"
        :to="item.to"
        :title="collapsed ? item.label : undefined"
        class="mb-0.5 flex items-center gap-3 rounded-[3px] px-2.5 py-2 text-[14px] transition-colors"
        :class="[
          collapsed ? 'justify-center' : '',
          isActive(item) ? 'bg-ink text-paper' : 'text-ink-2 hover:bg-paper-sunken hover:text-ink',
        ]"
      >
        <Icon :name="item.icon" :size="17" class="shrink-0" />
        <span v-if="!collapsed" class="flex-1 truncate">{{ item.label }}</span>
        <!-- 用真值判断而不是 !== null：星标数是 0 的时候显示一个"0"是噪音 -->
        <span v-if="!collapsed && item.badge" class="tabular text-[12px] text-ink-3">
          {{ item.badge }}
        </span>
      </RouterLink>
    </nav>

    <!-- 概览数字：窄轨放不下，只在完整侧栏显示 -->
    <div v-if="!collapsed && stats?.data" class="border-line-soft border-t px-5 py-4">
      <p class="t-rule mb-2.5">概览</p>
      <dl class="space-y-1.5">
        <div class="flex items-baseline justify-between">
          <dt class="text-[12px] text-ink-3">全部</dt>
          <dd class="font-display tabular text-[15px] text-ink">{{ stats.data.total }}</dd>
        </div>
        <div class="flex items-baseline justify-between">
          <dt class="text-[12px] text-ink-3">今天</dt>
          <dd class="font-display tabular text-[15px] text-ink">{{ stats.data.today }}</dd>
        </div>
        <div class="flex items-baseline justify-between">
          <dt class="text-[12px] text-ink-3">本周</dt>
          <dd class="font-display tabular text-[15px] text-ink">{{ stats.data.week }}</dd>
        </div>
        <div class="flex items-baseline justify-between">
          <dt class="text-[12px] text-ink-3">待补充</dt>
          <dd class="font-display tabular text-[15px] text-ink">{{ stats.data.pendingAi }}</dd>
        </div>
      </dl>
    </div>

    <!-- 用户 -->
    <div class="border-line-soft border-t p-2">
      <UserMenu placement="up" />
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from './Icon.vue'
import UserMenu from './UserMenu.vue'
import { useStatsStore } from '../stores/stats'
import { useUiStore } from '../stores/ui'

/** 窄轨模式由父组件根据断点传入，避免在组件里再监听一次媒体查询 */
defineProps<{ collapsed: boolean }>()

const route = useRoute()
const router = useRouter()
const stats = useStatsStore()
const ui = useUiStore()

// 用 computed 而不是普通数组：星标/归档的计数要跟着 stats 变，
// 写成普通对象数组的话数字只会是首次渲染那一刻的值。
const nav = computed(() => [
  { to: '/', label: '时间线', icon: 'timeline', exact: true, badge: null as number | null },
  { to: '/starred', label: '星标', icon: 'star', badge: stats.data?.starred ?? null },
  { to: '/archive', label: '归档', icon: 'archive', badge: stats.data?.archived ?? null },
  { to: '/search', label: '搜索', icon: 'search', badge: null as number | null },
  { to: '/settings', label: '设置', icon: 'settings', badge: null as number | null },
])

function isActive(item: { to: string; exact?: boolean }): boolean {
  if (item.exact) return route.path === item.to
  return route.path === item.to || route.path.startsWith(item.to + '/')
}

function compose() {
  // 时间线页有常驻输入框，把焦点给它就够了；别的页面要跳过去再聚焦
  if (route.path === '/') {
    ui.focusCompose()
  } else {
    router.push('/').then(() => ui.focusCompose())
  }
}
</script>
