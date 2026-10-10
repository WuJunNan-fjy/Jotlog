import { ref } from 'vue'

/**
 * 断点。
 *
 * 只有两档，因为布局只需要回答两个问题：
 *   - compact：屏幕窄到放不下侧栏吗？（< 768）→ 用顶栏 + 底部 tab
 *   - wide：宽到能在主列表旁边再塞一列详情吗？（>= 1280）→ 详情内联，否则走抽屉
 *
 * 之所以用 JS 而不是纯 CSS 断点：抽屉和内联面板是两种完全不同的 DOM 结构，
 * 靠 display:none 切换会同时渲染两份（浪费 + 状态不同步）。
 *
 * 模块级共享：多个组件同时监听同一条 media query 没必要各建一套。
 */

/**
 * 模块级单例，故意不注销监听：它和页面同生命周期，
 * 注销反而会在组件卸载时让后续订阅者拿到死掉的 ref。
 */
function track(query: string) {
  if (typeof window === 'undefined' || !window.matchMedia) return ref(false)

  const mql = window.matchMedia(query)
  const state = ref(mql.matches)
  mql.addEventListener('change', (e) => {
    state.value = e.matches
  })
  return state
}

const compactQuery = '(max-width: 767px)'
const wideQuery = '(min-width: 1280px)'

let compactRef: ReturnType<typeof ref<boolean>> | null = null
let wideRef: ReturnType<typeof ref<boolean>> | null = null

export function useBreakpoint() {
  if (!compactRef) compactRef = track(compactQuery)
  if (!wideRef) wideRef = track(wideQuery)
  return { isCompact: compactRef, isWide: wideRef }
}
