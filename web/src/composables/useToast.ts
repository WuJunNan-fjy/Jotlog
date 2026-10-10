import { ref } from 'vue'

/**
 * 轻提示。
 *
 * 只做「操作已完成」这一件事：加星、归档、删除、记下。
 * 不做确认框、不做加载中 —— 那些有各自的位置，混进 toast 会让它变得不可信。
 */

export type ToastTone = 'default' | 'success' | 'danger'

interface Toast {
  id: number
  text: string
  tone: ToastTone
  /** 撤销这类附加动作，有就显示一个按钮 */
  action?: { label: string; run: () => void }
}

const items = ref<Toast[]>([])
let seq = 0
const timers = new Map<number, ReturnType<typeof setTimeout>>()

export function useToast() {
  function push(text: string, tone: ToastTone = 'default', action?: Toast['action']) {
    const id = ++seq
    items.value = [...items.value, { id, text, tone, action }]
    // 有动作就多留一会儿，用户需要时间看到并点它
    const ttl = action ? 6000 : 2600
    timers.set(
      id,
      setTimeout(() => dismiss(id), ttl),
    )
  }

  function dismiss(id: number) {
    const timer = timers.get(id)
    if (timer) clearTimeout(timer)
    timers.delete(id)
    items.value = items.value.filter((t) => t.id !== id)
  }

  return { items, push, dismiss }
}
