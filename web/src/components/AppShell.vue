<template>
  <div class="flex min-h-screen flex-col">
    <!-- 顶栏：桌面显示完整导航，移动端只留品牌 -->
    <header class="border-line bg-paper/92 sticky top-0 z-20 border-b backdrop-blur">
      <div class="mx-auto flex h-14 max-w-[720px] items-center justify-between px-5">
        <RouterLink to="/" class="flex items-baseline gap-2">
          <span class="font-serif text-[19px] tracking-wide text-ink">Jotlog</span>
          <span class="hidden text-[12px] text-ink-3 sm:inline">随手记</span>
        </RouterLink>

        <nav class="hidden items-center gap-1 md:flex">
          <RouterLink
            v-for="item in nav"
            :key="item.to"
            :to="item.to"
            class="rounded px-3 py-1.5 text-[14px] transition-colors"
            :class="isActive(item) ? 'text-accent' : 'text-ink-3 hover:text-ink'"
          >
            {{ item.label }}
          </RouterLink>
        </nav>
      </div>
    </header>

    <main class="mx-auto w-full max-w-[720px] flex-1 px-5 pt-6 pb-28 md:pb-16">
      <RouterView />
    </main>

    <!-- 底部 tab：只在移动端出现。手机是主要入口，拇指够得着比什么都重要 -->
    <nav
      class="border-line bg-paper/92 safe-bottom fixed bottom-0 left-0 right-0 z-20 flex border-t backdrop-blur md:hidden"
    >
      <RouterLink
        v-for="item in nav"
        :key="item.to"
        :to="item.to"
        class="flex flex-1 flex-col items-center gap-1 py-2.5 transition-colors"
        :class="isActive(item) ? 'text-accent' : 'text-ink-3'"
      >
        <Icon :name="item.icon" class="h-[18px] w-[18px]" />
        <span class="text-[11px]">{{ item.label }}</span>
      </RouterLink>
    </nav>
  </div>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
import Icon from './Icon.vue'

const route = useRoute()

const nav = [
  { to: '/', label: '时间线', icon: 'timeline', exact: true },
  { to: '/starred', label: '星标', icon: 'star' },
  { to: '/archive', label: '归档', icon: 'archive' },
  { to: '/search', label: '搜索', icon: 'search' },
  { to: '/settings', label: '设置', icon: 'settings' },
]

function isActive(item: { to: string; exact?: boolean }): boolean {
  if (item.exact) return route.path === item.to
  return route.path === item.to || route.path.startsWith(item.to + '/')
}
</script>
