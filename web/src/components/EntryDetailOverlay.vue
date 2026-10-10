<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-50" role="dialog" aria-modal="true">
      <!-- 遮罩 -->
      <Transition
        enter-active-class="transition-opacity duration-200"
        enter-from-class="opacity-0"
        leave-active-class="transition-opacity duration-150"
        leave-to-class="opacity-0"
      >
        <div v-if="visible" class="absolute inset-0 bg-ink/25 dark:bg-black/50" @click="close"></div>
      </Transition>

      <!-- 容器：手机上从底部升起（拇指区间），平板/小屏桌面从右侧滑出（更像"展开"） -->
      <Transition
        enter-active-class="transition duration-300 ease-out"
        :enter-from-class="isCompact ? 'translate-y-full' : 'translate-x-full'"
        leave-active-class="transition duration-200 ease-in"
        :leave-to-class="isCompact ? 'translate-y-full' : 'translate-x-full'"
      >
        <div
          v-if="visible"
          class="sheet absolute shadow-lg"
          :class="
            isCompact
              ? 'safe-b right-0 bottom-0 left-0 max-h-[88dvh] rounded-t-2xl'
              : 'top-0 right-0 h-full w-[420px] max-w-[92vw] border-y-0 border-r-0'
          "
        >
          <!-- 手机端顶部那根小横条：提示"这是个可以拖下去的面板"。
               不做真拖拽 —— 拖动冲突处理比它带来的价值麻烦得多 -->
          <div v-if="isCompact" class="flex justify-center pt-2.5 pb-1">
            <span class="h-1 w-9 rounded-full bg-line"></span>
          </div>

          <EntryDetailPanel v-if="entry" :entry="entry" closable class="max-h-[88dvh]" @close="close" />

          <div v-else class="p-6">
            <div class="skeleton mb-4 h-6 w-32 rounded"></div>
            <div class="space-y-2.5">
              <div class="skeleton h-3.5 w-full rounded"></div>
              <div class="skeleton h-3.5 w-4/5 rounded"></div>
              <div class="skeleton h-3.5 w-2/3 rounded"></div>
            </div>
          </div>
        </div>
      </Transition>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import EntryDetailPanel from './EntryDetailPanel.vue'
import { useBreakpoint } from '../composables/useBreakpoint'
import type { Entry } from '../types'

defineProps<{ entry: Entry | null }>()
const emit = defineEmits<{ close: [] }>()

const { isCompact } = useBreakpoint()

// 进场动画需要元素先以"初始态"挂载一帧，否则 Transition 不生效
const visible = ref(false)
onMounted(() => requestAnimationFrame(() => (visible.value = true)))

function close() {
  visible.value = false
  // 等退场动画跑完再卸载，否则面板会"啪"地消失
  setTimeout(() => emit('close'), 220)
}
</script>
