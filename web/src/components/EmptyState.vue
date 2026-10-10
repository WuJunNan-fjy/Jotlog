<template>
  <div class="anim-fade flex flex-col items-center px-6 py-14 text-center">
    <!-- 手绘感线条插画。
         不引插画库、不用 emoji：emoji 在各平台上长得不一样，
         而这个应用的其余部分都在努力做到"在哪看都一样"。 -->
    <svg
      viewBox="0 0 120 96"
      class="mb-5 h-auto w-[124px] text-ink-4"
      fill="none"
      stroke="currentColor"
      stroke-width="1.4"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <!-- 纸：略微倾斜，像随手摊在桌上 -->
      <path d="M28 14l38-6 26 8v58a3 3 0 01-3 3H31a3 3 0 01-3-3z" class="text-line" stroke="currentColor" />
      <path d="M28 14v-4a3 3 0 013-3h17l4 7" />
      <g v-if="art === 'empty'">
        <path d="M42 40h38M42 52h30M42 64h20" />
      </g>
      <g v-else-if="art === 'star'">
        <path d="M60 38l4.6 9.6 10.4 1.5-7.6 7.4 1.8 10.4-9.2-4.9-9.2 4.9 1.8-10.4-7.6-7.4 10.4-1.5z" />
      </g>
      <g v-else-if="art === 'archive'">
        <rect x="42" y="36" width="36" height="12" rx="2" />
        <path d="M45 48v22a2 2 0 002 2h26a2 2 0 002-2V48M56 58h8" />
      </g>
      <g v-else-if="art === 'search'">
        <circle cx="58" cy="55" r="15" />
        <path d="M69 66l9 9" />
        <path d="M52 50c3-3 8-4 12-2" class="opacity-60" />
      </g>
      <g v-else-if="art === 'offline'">
        <path d="M46 46l28 22M74 46l-28 22" />
      </g>
    </svg>

    <p class="text-[15px] text-ink-2">{{ title }}</p>
    <p v-if="hint" class="mt-1.5 max-w-[300px] text-[13px] leading-relaxed text-ink-3">
      {{ hint }}
    </p>
    <slot />
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    hint?: string
    art?: 'empty' | 'star' | 'archive' | 'search' | 'offline'
  }>(),
  { art: 'empty' },
)
</script>
