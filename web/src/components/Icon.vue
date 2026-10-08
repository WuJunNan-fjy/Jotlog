<template>
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <template v-if="name === 'timeline'">
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </template>
    <template v-else-if="name === 'star'">
      <path d="M12 3.5l2.6 5.4 5.9.8-4.3 4.2 1 5.9-5.2-2.8-5.2 2.8 1-5.9-4.3-4.2 5.9-.8z" />
    </template>
    <template v-else-if="name === 'archive'">
      <rect x="3" y="4" width="18" height="5" rx="1" />
      <path d="M5 9v10a1 1 0 001 1h12a1 1 0 001-1V9M10 13h4" />
    </template>
    <template v-else-if="name === 'search'">
      <circle cx="11" cy="11" r="7" />
      <path d="M16.5 16.5L21 21" />
    </template>
    <template v-else-if="name === 'settings'">
      <path d="M4 7h16M4 12h16M4 17h16" />
      <circle cx="9" cy="7" r="2" fill="var(--color-paper)" />
      <circle cx="15" cy="12" r="2" fill="var(--color-paper)" />
      <circle cx="8" cy="17" r="2" fill="var(--color-paper)" />
    </template>
    <template v-else-if="name === 'trash'">
      <path d="M4 7h16M9 7V5a1 1 0 011-1h4a1 1 0 011 1v2M6.5 7l1 12.5a1 1 0 001 .9h7a1 1 0 001-.9L17.5 7" />
    </template>
    <template v-else-if="name === 'link'">
      <path d="M9.5 14.5l5-5M10.5 6.5l1-1a4.5 4.5 0 016.4 6.4l-1 1M13.5 17.5l-1 1a4.5 4.5 0 01-6.4-6.4l1-1" />
    </template>
    <template v-else-if="name === 'plus'">
      <path d="M12 5v14M5 12h14" />
    </template>
    <template v-else-if="name === 'logout'">
      <path d="M15 17l5-5-5-5M20 12H9M12 4H6a1 1 0 00-1 1v14a1 1 0 001 1h6" />
    </template>
    <template v-else-if="name === 'send'">
      <path d="M21.5 2.5L11 13M21.5 2.5l-7 19-3.5-8.5L2.5 9.5z" />
    </template>
    <template v-else-if="name === 'check'">
      <path d="M20 6.5L9.5 17 4 11.5" />
    </template>
    <template v-else-if="name === 'x'">
      <path d="M18 6L6 18M6 6l12 12" />
    </template>
    <template v-else-if="name === 'chevron'">
      <path d="M6 9.5l6 6 6-6" />
    </template>
    <template v-else-if="name === 'external'">
      <path d="M18 13.5V19a1.5 1.5 0 01-1.5 1.5H5A1.5 1.5 0 013.5 19V7.5A1.5 1.5 0 015 6h5.5M14.5 3.5H21V10M10.5 13.5L21 3" />
    </template>
    <template v-else-if="name === 'mail'">
      <rect x="3" y="5" width="18" height="14" rx="1.5" />
      <path d="M3.5 6.5L12 13l8.5-6.5" />
    </template>
  </svg>
</template>

<script setup lang="ts">
/**
 * 单文件图标。
 *
 * 不引图标库：这里一共十来个图标，为一个自用项目拉一整个 lucide 依赖不划算，
 * 而且按需打包在 Vite 下也省不了多少 —— 手写更可控。
 */
defineProps<{ name: string }>()
</script>
