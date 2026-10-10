<template>
  <div class="relative" ref="root">
    <button
      class="flex items-center gap-2.5 rounded-lg p-1.5 text-left transition-colors hover:bg-paper-sunken md:justify-center xl:justify-start"
      :class="compact ? 'w-auto' : 'w-full'"
      :aria-expanded="open"
      aria-haspopup="menu"
      @click="open = !open"
    >
      <span
        class="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-accent-soft text-[13px] font-medium text-accent"
      >
        {{ initial }}
      </span>
      <span class="hidden min-w-0 flex-1 xl:block">
        <span class="block truncate text-[13.5px] leading-tight text-ink">{{ auth.user?.username }}</span>
        <span class="block truncate text-[11.5px] leading-tight text-ink-3">
          {{ auth.user?.email || '未设置邮箱' }}
        </span>
      </span>
      <Icon name="chevron-down" :size="14" class="hidden shrink-0 text-ink-3 xl:block" />
    </button>

    <Transition
      enter-active-class="transition duration-150 ease-out"
      enter-from-class="opacity-0 scale-95"
      leave-active-class="transition duration-100 ease-in"
      leave-to-class="opacity-0 scale-95"
    >
      <div
        v-if="open"
        role="menu"
        class="card absolute z-50 w-[228px] overflow-hidden shadow-lg"
        :class="placement === 'up' ? 'bottom-full left-0 mb-2' : 'top-full right-0 mt-2 xl:left-0 xl:right-auto'"
      >
        <div class="border-line-soft border-b px-3.5 py-3">
          <p class="truncate text-[13.5px] text-ink">{{ auth.user?.nickname || auth.user?.username }}</p>
          <p class="truncate text-[12px] text-ink-3">{{ auth.user?.email || '未设置邮箱' }}</p>
        </div>

        <div class="p-1.5">
          <RouterLink
            to="/settings"
            class="flex items-center gap-2.5 rounded-md px-2.5 py-2 text-[13.5px] text-ink-2 transition-colors hover:bg-paper-sunken hover:text-ink"
            @click="open = false"
          >
            <Icon name="settings" :size="16" />
            设置
          </RouterLink>
        </div>

        <div class="border-line-soft border-t px-3.5 py-3">
          <p class="mb-2 text-[11.5px] tracking-wide text-ink-3">外观</p>
          <div class="flex gap-1 rounded-lg bg-paper-sunken p-1">
            <button
              v-for="opt in themeOptions"
              :key="opt.value"
              class="flex flex-1 items-center justify-center gap-1 rounded-md py-1.5 text-[12px] transition-colors"
              :class="
                mode === opt.value ? 'bg-surface text-ink shadow-sm' : 'text-ink-3 hover:text-ink'
              "
              @click="set(opt.value)"
            >
              <Icon :name="opt.icon" :size="14" />
              <span class="hidden sm:inline">{{ opt.label }}</span>
            </button>
          </div>
        </div>

        <div class="border-line-soft border-t p-1.5">
          <button
            class="flex w-full items-center gap-2.5 rounded-md px-2.5 py-2 text-[13.5px] text-danger transition-colors hover:bg-danger-soft"
            @click="doLogout"
          >
            <Icon name="logout" :size="16" />
            退出登录
          </button>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import Icon from './Icon.vue'
import { useAuthStore } from '../stores/auth'
import { useTheme } from '../composables/useTheme'
import type { ThemeMode } from '../composables/useTheme'

withDefaults(defineProps<{ placement?: 'up' | 'down'; compact?: boolean }>(), {
  placement: 'down',
  compact: false,
})

const auth = useAuthStore()
// 解构出来是为了让模板能自动解包 ref。直接用 theme.mode 拿到的是 Ref 对象本身
const { mode, set } = useTheme()
const router = useRouter()

const open = ref(false)
const root = ref<HTMLElement | null>(null)

const initial = auth.user?.username?.slice(0, 1)?.toUpperCase() ?? '?'

const themeOptions: { value: ThemeMode; label: string; icon: string }[] = [
  { value: 'system', label: '跟随', icon: 'monitor' },
  { value: 'light', label: '浅色', icon: 'sun' },
  { value: 'dark', label: '深色', icon: 'moon' },
]

/** 点面板外面就关。不加遮罩层：那样点击会先命中遮罩，滚动手感也变差。 */
function onDocClick(e: MouseEvent) {
  if (open.value && root.value && !root.value.contains(e.target as Node)) open.value = false
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') open.value = false
}

onMounted(() => {
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onKeydown)
})

// 换页时收起，否则菜单会飘在新页面上方
watch(() => router.currentRoute.value.fullPath, () => (open.value = false))

async function doLogout() {
  open.value = false
  await auth.logout()
  router.replace('/login')
}
</script>
