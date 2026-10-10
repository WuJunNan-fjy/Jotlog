<template>
  <!--
    三档布局，同一份 DOM，靠断点切换：

      < 768   移动：顶栏 + 全宽单列 + 底部 tab，详情走底部 sheet
      768-1279 平板：68px 窄轨侧栏 + 单列，详情走右侧抽屉
      >= 1280  桌面：248px 完整侧栏 + 单列 + 详情常驻右栏

    整屏高度用 100dvh 而不是 100vh：手机浏览器地址栏收起/展开会让 100vh 抖动，
    dvh 跟着可视高度走。滚动条只出现在内容列上，导航永远固定。
  -->
  <div class="flex h-[100dvh] overflow-hidden">
    <SideNav :collapsed="!isWide" />

    <div class="flex min-w-0 flex-1 flex-col">
      <MobileTopBar />

      <div class="flex min-h-0 flex-1">
        <main ref="scrollEl" class="min-w-0 flex-1 overflow-y-auto" @scroll.passive="onScroll">
          <!-- 内容列最宽 760px。再宽一行字就太长了，读起来要"找下一行在哪" -->
          <div class="mx-auto w-full max-w-[760px] px-5 py-5 sm:px-6 md:py-8">
            <RouterView />
          </div>
        </main>

        <!-- 详情常驻栏：只有宽屏放得下 -->
        <aside
          v-if="ui.selectedId"
          class="border-line hidden w-[380px] shrink-0 border-l xl:block 2xl:w-[420px]"
        >
          <EntryDetailPanel
            v-if="ui.selectedEntry"
            :entry="ui.selectedEntry"
            closable
            @close="ui.clearSelection()"
          />
          <div v-else class="p-6">
            <div class="skeleton mb-4 h-6 w-32 rounded"></div>
            <div class="space-y-2.5">
              <div class="skeleton h-3.5 w-full rounded"></div>
              <div class="skeleton h-3.5 w-4/5 rounded"></div>
              <div class="skeleton h-3.5 w-2/3 rounded"></div>
            </div>
          </div>
        </aside>
      </div>

      <MobileTabBar />
    </div>

    <!-- 回到顶部。只在桌面出现：手机上右下角已经悬浮着"记一笔"，
         再叠一个圆按钮会互相挡，而手机用户更习惯直接甩一下屏幕 -->
    <Transition
      enter-active-class="transition duration-200"
      enter-from-class="opacity-0 translate-y-1"
      leave-active-class="transition duration-150"
      leave-to-class="opacity-0 translate-y-1"
    >
      <button
        v-if="showToTop && !isCompact"
        class="card fixed right-6 bottom-6 z-30 grid h-10 w-10 place-items-center shadow-md transition-colors hover:bg-paper-sunken"
        aria-label="回到顶部"
        @click="scrollEl?.scrollTo({ top: 0, behavior: 'smooth' })"
      >
        <Icon name="chevron-down" :size="18" class="rotate-180 text-ink-3" />
      </button>
    </Transition>

    <!-- 悬浮记录按钮：只在手机上出现。
         桌面有时间线顶部常驻的输入框，再飘一个圆按钮是多余的 -->
    <button
      v-if="isCompact"
      class="fixed right-5 z-30 flex h-13 w-13 items-center justify-center rounded-[14px] bg-ink text-paper shadow-lg transition-transform active:scale-95 md:hidden"
      :style="{ bottom: 'calc(4.5rem + env(safe-area-inset-bottom, 0px))' }"
      aria-label="记一笔"
      @click="ui.openCompose()"
    >
      <Icon name="plus" :size="22" />
    </button>

    <!-- 详情覆盖层：非宽屏用（手机底部 sheet / 平板右侧抽屉） -->
    <EntryDetailOverlay
      v-if="ui.selectedId && !isWide"
      :entry="ui.selectedEntry"
      @close="ui.clearSelection()"
    />

    <ComposeSheet v-if="ui.composeOpen" @close="onComposeClose" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SideNav from './SideNav.vue'
import MobileTopBar from './MobileTopBar.vue'
import MobileTabBar from './MobileTabBar.vue'
import EntryDetailPanel from './EntryDetailPanel.vue'
import EntryDetailOverlay from './EntryDetailOverlay.vue'
import ComposeSheet from './ComposeSheet.vue'
import Icon from './Icon.vue'
import { useBreakpoint } from '../composables/useBreakpoint'
import { useHotkeys } from '../composables/useHotkeys'
import { useStatsStore } from '../stores/stats'
import { useUiStore } from '../stores/ui'
import { api } from '../api/http'

const route = useRoute()
const router = useRouter()
const ui = useUiStore()
const stats = useStatsStore()
const { isCompact, isWide } = useBreakpoint()

const scrollEl = ref<HTMLElement | null>(null)
const showToTop = ref(false)

onMounted(() => stats.load())

/** 滚动超过一屏半才出现"回到顶部"。太早出现的话它几乎一直挂着，反而碍事 */
function onScroll(e: Event) {
  showToTop.value = (e.target as HTMLElement).scrollTop > 900
}

/**
 * URL 里的 ?id= 是 selectedId 的镜像。
 *
 * 双向同步的意义：刷新页面、把链接发给自己的时候，详情面板还在原处；
 * 而在宽屏上关掉面板，地址栏也不会留一个已经失效的 id。
 */
watch(
  () => ui.selectedId,
  (id) => {
    const current = route.query.id ? Number(route.query.id) : null
    if (current === id) return
    const query = { ...route.query }
    if (id === null) delete query.id
    else query.id = String(id)
    router.replace({ path: route.path, query })
  },
)

watch(
  () => route.query.id,
  (raw) => {
    const id = typeof raw === 'string' && /^\d+$/.test(raw) ? Number(raw) : null
    if (id === ui.selectedId) return
    if (id === null) {
      ui.clearSelection()
      return
    }
    ui.selectId(id)
  },
  { immediate: true },
)

// 选中了 id 但手上没有实体（刷新、深链、j/k 跳到未加载项）→ 单独取一次
watch(
  () => [ui.selectedId, ui.selectedEntry] as const,
  async ([id, entry]) => {
    if (id === null || entry?.id === id) return
    try {
      ui.selectedEntry = await api.entry(id)
    } catch {
      // 取不到就当没选过。常见于分享了一个已被删除的 id
      ui.clearSelection()
    }
  },
  { immediate: true },
)

// 切页回到顶部。只跟 path，不跟 query —— 否则搜索时每敲一个字都会跳回顶部
watch(
  () => route.path,
  () => scrollEl.value?.scrollTo({ top: 0 }),
)

async function onComposeClose(saved: boolean) {
  ui.closeCompose()
  if (!saved) return

  // 在星标页/归档页记一条，新条目默认不星标也不归档，留在当前页会"看着像没记上"。
  // 所以记完就回到时间线 —— 那里一定看得到它。
  if (route.path !== '/') await router.push('/')

  // 视图各自监听 reloadSeq 去重新拉，这样第一页就有这条新记录
  ui.bumpReload()
}

useHotkeys({
  onEscape: () => {
    if (ui.composeOpen) ui.closeCompose()
    else if (ui.selectedId) ui.clearSelection()
  },
  onCompose: () => (route.path === '/' ? ui.focusCompose() : ui.openCompose()),
  onSearch: () => router.push('/search'),
})
</script>
