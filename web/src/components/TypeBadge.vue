<template>
  <span class="chip" :class="toneClass(meta.tone)">
    <Icon :name="meta.icon" :size="12" />
    {{ meta.label }}
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import Icon from './Icon.vue'
import { toneClass, typeMeta } from '../utils/entryMeta'

const props = defineProps<{ type: string }>()
const meta = computed(() => typeMeta(props.type))
</script>
