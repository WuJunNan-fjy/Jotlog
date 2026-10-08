<template>
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    :width="size"
    :height="size"
  >
    <!-- 导航 -->
    <template v-if="name === 'timeline'">
      <path d="M4 6h16M4 12h16M4 18h10" />
      <circle cx="8" cy="6" r="1.6" />
      <circle cx="14" cy="12" r="1.6" />
    </template>
    <template v-else-if="name === 'star'">
      <path d="M12 3.6l2.6 5.4 5.9.8-4.3 4.2 1 5.9-5.2-2.8-5.2 2.8 1-5.9-4.3-4.2 5.9-.8z" />
    </template>
    <template v-else-if="name === 'archive'">
      <rect x="3" y="4" width="18" height="5" rx="1.2" />
      <path d="M5 9v10a1 1 0 001 1h12a1 1 0 001-1V9M10 13h4" />
    </template>
    <template v-else-if="name === 'search'">
      <circle cx="11" cy="11" r="7" />
      <path d="M16.5 16.5L21 21" />
    </template>
    <template v-else-if="name === 'settings'">
      <circle cx="12" cy="12" r="3.2" />
      <path
        d="M12 2.8v2.6M12 18.6v2.6M21.2 12h-2.6M5.4 12H2.8M18.5 5.5l-1.9 1.9M7.4 16.6l-1.9 1.9M18.5 18.5l-1.9-1.9M7.4 7.4L5.5 5.5"
      />
    </template>
    <template v-else-if="name === 'inbox'">
      <path d="M3 13h5l1.2 2.4h5.6L16 13h5" />
      <path d="M5.4 5.5h13.2l2.4 7.5v5a1.5 1.5 0 01-1.5 1.5H4.5A1.5 1.5 0 013 19v-5z" />
    </template>

    <!-- 条目类型 -->
    <template v-else-if="name === 'link'">
      <path
        d="M9.5 14.5l5-5M10.5 6.5l1-1a4.5 4.5 0 016.4 6.4l-1 1M13.5 17.5l-1 1a4.5 4.5 0 01-6.4-6.4l1-1"
      />
    </template>
    <template v-else-if="name === 'repo'">
      <path d="M8 6.5V4.8A1.3 1.3 0 016.7 3.5h-2A1.4 1.4 0 003.3 5v14a1.4 1.4 0 001.4 1.4h2A1.3 1.3 0 008 19.2v-1.7" />
      <path d="M10.5 6.8l3.5-3.3 3.5 3.3M14 4.2v10.4" />
      <path d="M10.5 19.2l3.5 3.3 3.5-3.3M14 21.8V11.4" />
    </template>
    <template v-else-if="name === 'video'">
      <rect x="2.5" y="5.5" width="19" height="13" rx="2" />
      <path d="M10 9.6l5 2.4-5 2.4z" />
    </template>
    <template v-else-if="name === 'note'">
      <path d="M14.5 3.5H6.5A1.5 1.5 0 005 5v14a1.5 1.5 0 001.5 1.5h11A1.5 1.5 0 0019 19V8z" />
      <path d="M14.5 3.5V8H19M8.5 13h7M8.5 16.5h4.5" />
    </template>
    <template v-else-if="name === 'file'">
      <path d="M13.5 3.5H7A1.5 1.5 0 005.5 5v14A1.5 1.5 0 007 20.5h10a1.5 1.5 0 001.5-1.5V8.5z" />
      <path d="M13.5 3.5v5h5" />
    </template>
    <template v-else-if="name === 'image'">
      <rect x="3.5" y="4.5" width="17" height="15" rx="2" />
      <circle cx="9" cy="10" r="1.6" />
      <path d="M3.8 17.4l4.6-4.2 3.3 3 3-2.6 4.5 3.8" />
    </template>
    <template v-else-if="name === 'unknown'">
      <circle cx="12" cy="12" r="9" />
      <path d="M9.6 9.4a2.5 2.5 0 114.2 2.2c-.8.6-1.8.9-1.8 2M12 17.2h.01" />
    </template>

    <!-- 动作 -->
    <template v-else-if="name === 'trash'">
      <path
        d="M4 7h16M9 7V5a1 1 0 011-1h4a1 1 0 011 1v2M6.5 7l1 12.5a1 1 0 001 .9h7a1 1 0 001-.9L17.5 7"
      />
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
    <template v-else-if="name === 'copy'">
      <rect x="8.5" y="8.5" width="12" height="12" rx="1.8" />
      <path d="M15.5 5.5H5.5a1.5 1.5 0 00-1.5 1.5v10" />
    </template>
    <template v-else-if="name === 'external'">
      <path
        d="M18 13.5V19a1.5 1.5 0 01-1.5 1.5H5A1.5 1.5 0 013.5 19V7.5A1.5 1.5 0 015 6h5.5M14.5 3.5H21V10M10.5 13.5L21 3"
      />
    </template>
    <template v-else-if="name === 'chevron-down'">
      <path d="M6 9.5l6 6 6-6" />
    </template>
    <template v-else-if="name === 'chevron-right'">
      <path d="M9.5 6l6 6-6 6" />
    </template>
    <template v-else-if="name === 'arrow-left'">
      <path d="M19 12H5M11 6l-6 6 6 6" />
    </template>
    <template v-else-if="name === 'menu'">
      <path d="M4 7h16M4 12h16M4 17h16" />
    </template>
    <template v-else-if="name === 'sparkles'">
      <path d="M12 3.5l1.5 4 4 1.5-4 1.5L12 14.5 10.5 10.5 6.5 9l4-1.5z" />
      <path d="M18.5 15.5l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z" />
    </template>
    <template v-else-if="name === 'tag'">
      <path d="M20.5 13.5l-7 7a2 2 0 01-2.8 0l-7-7a2 2 0 01-.6-1.5l.4-6a2 2 0 011.9-1.9l6-.4a2 2 0 011.5.6l7 7a2 2 0 010 2.8z" />
      <circle cx="8" cy="8" r="1.3" />
    </template>
    <template v-else-if="name === 'refresh'">
      <path d="M20 12a8 8 0 11-2.6-5.9M20 4.5V10h-5.5" />
    </template>

    <!-- 状态 / 主题 -->
    <template v-else-if="name === 'mail'">
      <rect x="3" y="5" width="18" height="14" rx="1.8" />
      <path d="M3.5 6.5L12 13l8.5-6.5" />
    </template>
    <template v-else-if="name === 'sun'">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2.8v2M12 19.2v2M21.2 12h-2M4.8 12h-2M18.5 5.5l-1.4 1.4M6.9 17.1l-1.4 1.4M18.5 18.5l-1.4-1.4M6.9 6.9L5.5 5.5" />
    </template>
    <template v-else-if="name === 'moon'">
      <path d="M20 14.5A8.5 8.5 0 019.5 4 8.5 8.5 0 1020 14.5z" />
    </template>
    <template v-else-if="name === 'monitor'">
      <rect x="2.8" y="4" width="18.4" height="12.5" rx="1.8" />
      <path d="M8.5 20.5h7M12 16.5v4" />
    </template>
    <template v-else-if="name === 'user'">
      <circle cx="12" cy="8.5" r="3.8" />
      <path d="M4.5 20c1.2-3.6 4-5.4 7.5-5.4s6.3 1.8 7.5 5.4" />
    </template>
    <template v-else-if="name === 'shield'">
      <path d="M12 3.2l7 2.6v5.6c0 4.3-2.9 7.6-7 9.4-4.1-1.8-7-5.1-7-9.4V5.8z" />
      <path d="M9.2 12.2l2 2 3.6-3.8" />
    </template>
  </svg>
</template>

<script setup lang="ts">
/**
 * 单文件图标集。
 *
 * 不引图标库：一共二十来个图标，为一个自用项目拉整个 lucide 不划算，
 * 而且这里每个图标的笔画粗细和圆角都被调过，跟正文的中文字重是配的 ——
 * 换成通用图标库反而会和文字打架。
 */
withDefaults(defineProps<{ name: string; size?: number | string }>(), { size: 18 })
</script>
