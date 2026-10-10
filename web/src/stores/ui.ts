import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { Entry } from '../types'

export interface EntryChange {
  id: number
  kind: 'star' | 'archive' | 'delete'
  starred?: boolean
  archived?: boolean
  seq: number
}

/**
 * 界面状态。
 *
 * 只放「跨组件共享且和服务器无关」的东西：现在选中哪条、记录面板开没开。
 * 条目数据本身不进来 —— 那是列表视图的，放这儿会变成第二个数据源，
 * 更新了一条还要记得同步两处。
 *
 * URL 里 ?id= 是 selectedId 的镜像，同步逻辑在 AppShell 里做，
 * 这里不碰 router：store 应该在 router 之外也能被测试。
 */
export const useUiStore = defineStore('ui', () => {
  const selectedId = ref<number | null>(null)
  const selectedEntry = ref<Entry | null>(null)
  const composeOpen = ref(false)
  /**
   * 让输入框聚焦用的计数器。
   *
   * 布尔量做不到"再聚焦一次"——点第二下时值没变，watch 不触发。
   * 递增的数字每次都是新值，输入框只需要 watch 它。
   */
  const composeFocusSeq = ref(0)

  const hasSelection = computed(() => selectedId.value !== null)

  function select(entry: Entry) {
    selectedEntry.value = entry
    selectedId.value = entry.id
  }

  /**
   * 只给个 id（比如从 URL 恢复、用 j/k 移动到还没加载的条目）。
   * 条目实体由 AppShell 负责补齐。
   */
  function selectId(id: number) {
    selectedId.value = id
    if (selectedEntry.value?.id !== id) selectedEntry.value = null
  }

  function clearSelection() {
    selectedId.value = null
    selectedEntry.value = null
  }

  /** 列表里那条被删了/归档了，选中态要跟着失效，否则详情面板会显示一条不存在的记录 */
  function dropSelection(id: number) {
    if (selectedId.value === id) clearSelection()
  }

  function openCompose() {
    composeOpen.value = true
  }

  function closeCompose() {
    composeOpen.value = false
  }

  /** 焦点打到已有的输入框上（桌面）。跟 openCompose 的区别是不开新面板。 */
  function focusCompose() {
    composeFocusSeq.value += 1
  }

  /**
   * 条目变更广播。
   *
   * 详情面板挂在 AppShell 上，列表数据却在各个视图里 —— 面板改了一条，
   * 视图没法直接知道。与其把列表数据也提到全局（那会让两个视图共享一份状态，
   * 切换标签页时互相污染），不如广播一个"改了什么"，让当前视图自己决定怎么跟。
   */
  const entryChange = ref<EntryChange | null>(null)
  let changeSeq = 0

  function notifyEntryChange(change: Omit<EntryChange, 'seq'>) {
    entryChange.value = { ...change, seq: ++changeSeq }
  }

  /**
   * 让列表重新拉一次的信号。
   *
   * 记一条新东西之后，列表要把它显示在第一位。但"记"这个动作可能发生在
   * 顶部输入框里，也可能发生在手机的底部弹层里 —— 后者跟列表不在同一个组件树上，
   * 传不了事件。用一个递增的数字广播，跟 composeFocusSeq 同理。
   */
  const reloadSeq = ref(0)
  function bumpReload() {
    reloadSeq.value += 1
  }

  return {
    selectedId,
    selectedEntry,
    composeOpen,
    composeFocusSeq,
    entryChange,
    reloadSeq,
    hasSelection,
    select,
    selectId,
    clearSelection,
    dropSelection,
    openCompose,
    closeCompose,
    focusCompose,
    notifyEntryChange,
    bumpReload,
  }
})
