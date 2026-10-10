<!--
  图片全屏查看。

  为什么不用浏览器自带的图片预览：黑底全屏、Esc 关闭、多图左右切换，
  这套是"看图"的最小闭环，自己写 80 行比引一个 viewer 库划算。
  滚动锁定靠给 body 加 overflow-hidden：sheet 和抽屉已经这么干了，口径一致。
-->
<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition-opacity duration-200"
      enter-from-class="opacity-0"
      leave-active-class="transition-opacity duration-150"
      leave-to-class="opacity-0"
    >
      <div
        v-if="visible"
        class="fixed inset-0 z-[60] flex flex-col bg-black/92"
        role="dialog"
        aria-modal="true"
        aria-label="查看图片"
        @click="close"
      >
        <!-- 顶栏：计数 + 关闭。stop 防止点按钮冒泡成关闭 -->
        <div class="flex items-center justify-between px-4 py-3 text-white/85" @click.stop>
          <span class="tabular text-[12.5px]">
            {{ index + 1 }} / {{ shas.length }}
          </span>
          <button
            class="grid h-10 w-10 place-items-center rounded-lg text-white/85 transition-colors hover:bg-white/10"
            aria-label="关闭"
            @click="close"
          >
            <Icon name="x" :size="20" />
          </button>
        </div>

        <!-- 图。点图不冒泡关闭 —— 看图时手滑点一下就退出太伤 -->
        <div class="flex min-h-0 flex-1 items-center justify-center px-4 pb-6">
          <img
            v-if="src"
            :src="src"
            :alt="''"
            class="anim-fade max-h-full max-w-full rounded-lg object-contain select-none"
            @click.stop
          />
        </div>

        <!-- 左右切换：多个图才有。手机上就是两个大按钮，点得着比精致重要 -->
        <template v-if="shas.length > 1">
          <button
            v-if="index > 0"
            class="fixed top-1/2 left-3 grid h-12 w-12 -translate-y-1/2 place-items-center rounded-full bg-white/10 text-white/85 hover:bg-white/20"
            aria-label="上一张"
            @click.stop="go(-1)"
          >
            <Icon name="chevron-right" :size="20" class="rotate-180" />
          </button>
          <button
            v-if="index < shas.length - 1"
            class="fixed top-1/2 right-3 grid h-12 w-12 -translate-y-1/2 place-items-center rounded-full bg-white/10 text-white/85 hover:bg-white/20"
            aria-label="下一张"
            @click.stop="go(1)"
          >
            <Icon name="chevron-right" :size="20" />
          </button>
        </template>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { loadAttachment } from '../api/attachmentCache'
import Icon from './Icon.vue'

const props = defineProps<{ shas: string[]; start: number }>()

const emit = defineEmits<{ close: [] }>()

const visible = ref(false)
const index = ref(props.start)
const src = ref<string | null>(null)

const currentSha = computed(() => props.shas[index.value] ?? '')

// 换图就加载。lightbox 的图通常刚在详情页看过了，大概率直接命中缓存
watch(
  currentSha,
  (sha) => {
    if (!sha) return
    src.value = null
    loadAttachment(sha)
      .then((url) => (src.value = url))
      .catch(() => (src.value = null))
  },
  { immediate: true },
)

onMounted(async () => {
  requestAnimationFrame(() => (visible.value = true))
  document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', onKey)
})

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKey)
})

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
  if (e.key === 'ArrowLeft') go(-1)
  if (e.key === 'ArrowRight') go(1)
}

function go(delta: number) {
  const next = index.value + delta
  if (next >= 0 && next < props.shas.length) index.value = next
}

function close() {
  visible.value = false
  setTimeout(() => emit('close'), 180)
}
</script>
