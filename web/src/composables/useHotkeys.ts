import { onBeforeUnmount, onMounted } from 'vue'

/**
 * 键盘快捷键。
 *
 * 只在桌面有意义，但注册不注册没什么成本，手机上根本按不出这些键。
 *
 *   /      聚焦搜索（并把光标带过去）
 *   n      聚焦输入框 / 打开记录面板
 *   Esc    关闭详情、弹层、清空搜索
 *   j / k  在列表里上下移动选中项
 *
 * 一律判断「焦点在输入框里就不抢键」——正在打字时按 n 想输入字母 n 的人不在少数。
 */

export interface HotkeyHandlers {
  onSearch?: () => void
  onCompose?: () => void
  onEscape?: () => void
  onMove?: (delta: 1 | -1) => void
}

function isTyping(el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement)) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
}

export function useHotkeys(handlers: HotkeyHandlers) {
  function onKeydown(e: KeyboardEvent) {
    // 组合键不拦：Cmd+R、Ctrl+Shift+I 这些是浏览器的
    if (e.metaKey || e.ctrlKey || e.altKey) return

    if (e.key === 'Escape') {
      handlers.onEscape?.()
      return
    }

    if (isTyping(e.target)) return

    if (e.key === '/') {
      e.preventDefault()
      handlers.onSearch?.()
    } else if (e.key === 'n' || e.key === 'N') {
      e.preventDefault()
      handlers.onCompose?.()
    } else if (e.key === 'j' || e.key === 'J') {
      e.preventDefault()
      handlers.onMove?.(1)
    } else if (e.key === 'k' || e.key === 'K') {
      e.preventDefault()
      handlers.onMove?.(-1)
    }
  }

  onMounted(() => window.addEventListener('keydown', onKeydown))
  onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
}
