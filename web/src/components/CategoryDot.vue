<template>
  <!-- 分类色点。无颜色时用中性灰，保证视觉不塌 -->
  <span
    class="inline-block h-[8px] w-[8px] shrink-0 rounded-full"
    :style="{ backgroundColor: color ?? '#9a9a8f' }"
  />
</template>

<script setup lang="ts">
defineProps<{ color?: string | null }>()
</script>
