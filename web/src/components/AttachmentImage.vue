<!--
  附件图片（带懒加载）。

  <img src="/api/..."> 发不了 Authorization 头，所以图片走
  loadAttachment() 的 fetch → blob 链路。而列表一页 30 条，
  每条都立刻拉原图的话，打开页面就是 30 个几 MB 的请求 ——
  所以用 IntersectionObserver 只拉滚进视口的，lazy 到条目级。

  原图直接当缩略图用（暂时没有缩略图生成基建），浏览器自己缩放。
  对单用户几千条的量级可以接受；将来图片多了再补服务端缩略图。
-->
<template>
  <span
    ref="el"
    class="relative block overflow-hidden rounded-lg bg-paper-sunken"
    :style="{ width: size, height: size }"
  >
    <!-- 加载骨架：和列表骨架屏同一套微光，视觉语言一致 -->
    <span v-if="status === 'loading'" class="skeleton absolute inset-0"></span>

    <img
      v-else-if="status === 'ok' && src"
      :src="src"
      :alt="alt"
      class="h-full w-full cursor-zoom-in object-cover"
      @click.stop="emit('open')"
    />

    <!-- 失败占位：图标的位置是稳定的，不会突然塌陷 -->
    <span v-else class="absolute inset-0 grid place-items-center text-ink-4">
      <Icon name="image" :size="18" />
    </span>
  </span>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { loadAttachment } from '../api/attachmentCache'
import Icon from './Icon.vue'

const props = withDefaults(
  defineProps<{ sha: string; alt?: string; size?: string }>(),
  { alt: '', size: '64px' },
)

const emit = defineEmits<{ open: [] }>()

const el = ref<HTMLElement | null>(null)
const src = ref<string | null>(null)
const status = ref<'loading' | 'ok' | 'error'>('loading')

let observer: IntersectionObserver | null = null
let cancelled = false

onMounted(() => {
  observer = new IntersectionObserver(
    (entries) => {
      if (!entries[0]?.isIntersecting) return
      observer?.disconnect()
      loadAttachment(props.sha)
        .then((url) => {
          if (cancelled) return
          src.value = url
          status.value = 'ok'
        })
        .catch(() => {
          if (cancelled) return
          status.value = 'error'
        })
    },
    { rootMargin: '200px' }, // 提前一点开始拉，滚到时基本已经好了
  )
  // ref 挂载后 el 一定有值；万一没有，退化为立即加载（比永远不显示强）
  if (el.value) observer.observe(el.value)
  else loadAttachment(props.sha).then((url) => { if (!cancelled) { src.value = url; status.value = 'ok' } }).catch(() => {})
})

onBeforeUnmount(() => {
  cancelled = true
  observer?.disconnect()
})
</script>
