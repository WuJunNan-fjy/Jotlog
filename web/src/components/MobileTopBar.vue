<template>
  <!--
    只在 < 768 出现。
    不做成半透明毛玻璃：内容区在它下面滚动，毛玻璃会让划过的文字糊成一片，
    实色 + 细边框反而干净，也省得处理 backdrop-filter 在低端机上的掉帧。
  -->
  <header
    class="safe-t border-line bg-paper/95 flex shrink-0 items-center gap-2 border-b pl-[calc(0.75rem+env(safe-area-inset-left,0px))] pr-[calc(0.75rem+env(safe-area-inset-right,0px))] md:hidden"
  >
    <span
      class="font-display px-2 text-[18px] tracking-tight text-ink"
      :class="route.path === '/' ? 'italic' : 'font-medium'"
      >{{ title }}</span
    >

    <div class="flex-1"></div>

    <button class="btn-icon" aria-label="搜索" @click="router.push('/search')">
      <Icon name="search" :size="18" />
    </button>

    <UserMenu placement="down" compact />
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from './Icon.vue'
import UserMenu from './UserMenu.vue'

const route = useRoute()
const router = useRouter()

const title = computed(() => {
  switch (route.path) {
    case '/starred':
      return '星标'
    case '/archive':
      return '归档'
    case '/search':
      return '搜索'
    case '/settings':
      return '设置'
    default:
      return 'Jotlog'
  }
})
</script>
