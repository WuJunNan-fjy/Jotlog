import { ref, watch } from 'vue'

/**
 * 主题。
 *
 * 三档而不是两档：跟随系统 / 强制浅色 / 强制深色。
 * 「跟随系统」是默认值 —— 一个晚上十一点打开的应用如果刺眼，那是设计失职。
 * 但也要允许用户在白天锁死深色，所以不能只做媒体查询。
 *
 * 状态放模块级而不是组件里：顶栏、设置页、侧栏都要读写它，
 * 用 provide/inject 反而绕。
 */

export type ThemeMode = 'system' | 'light' | 'dark'

const KEY = 'jotlog.theme'

function readMode(): ThemeMode {
  const saved = localStorage.getItem(KEY)
  return saved === 'light' || saved === 'dark' || saved === 'system' ? saved : 'system'
}

const media =
  typeof window !== 'undefined' && window.matchMedia
    ? window.matchMedia('(prefers-color-scheme: dark)')
    : null

const mode = ref<ThemeMode>(readMode())
const isDark = ref(media ? media.matches : false)

if (media) {
  media.addEventListener('change', (e) => {
    isDark.value = e.matches
    apply()
  })
}

function apply() {
  const dark = mode.value === 'system' ? isDark.value : mode.value === 'dark'
  document.documentElement.classList.toggle('dark', dark)
  // 状态栏颜色跟着变，否则 PWA 下手机顶部一条白边很出戏
  const meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', dark ? '#131311' : '#FBFAF7')
}

export function useTheme() {
  function set(next: ThemeMode) {
    mode.value = next
    localStorage.setItem(KEY, next)
    apply()
  }

  function toggle() {
    // 在 system 下点切换，落到"跟当前相反的显式值"，而不是绕回 system
    set(currentlyDark() ? 'light' : 'dark')
  }

  function currentlyDark(): boolean {
    return mode.value === 'system' ? isDark.value : mode.value === 'dark'
  }

  return { mode, isDark, set, toggle, currentlyDark }
}

/** 应用启动时调一次。放在 createApp 之前，避免首帧闪一下白。 */
export function initTheme() {
  apply()
  watch(mode, apply)
}
