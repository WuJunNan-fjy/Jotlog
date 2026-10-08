<template>
  <!-- 不做页面切换动画：随手记是高频打开的应用，
       每次切标签都要等动画，用两天就会烦 -->
  <RouterView />
  <ToastHost />
</template>

<script setup lang="ts">
import ToastHost from './components/ToastHost.vue'
</script>
