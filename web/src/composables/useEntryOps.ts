import { api, ApiError } from '../api/http'
import type { Entry } from '../types'
import { useStatsStore } from '../stores/stats'
import { useUiStore } from '../stores/ui'
import { useToast } from './useToast'

/**
 * 条目操作：加星 / 归档 / 删除。
 *
 * 集中在这里是因为列表卡片和详情面板要做同一件事，而它们不在同一个组件树上 ——
 * 详情挂在 AppShell 上，列表挂在视图里。
 *
 * 每次操作都做三件事：打接口 → 改本地对象 → 广播变更。
 * 顺序不能颠倒：先改本地再广播，视图收到广播时能立刻拿到新状态，
 * 否则会渲染出一个"还在旧状态"的行，然后闪一下才对。
 */
export function useEntryOps() {
  const ui = useUiStore()
  const stats = useStatsStore()
  const toast = useToast()

  async function toggleStar(entry: Entry) {
    const next = !entry.starred
    try {
      await api.updateEntry(entry.id, { starred: next })
      entry.starred = next
      stats.onStarToggled(entry, next)
      ui.notifyEntryChange({ id: entry.id, kind: 'star', starred: next })
      toast.push(next ? '已加星标' : '已取消星标', 'success')
    } catch (e) {
      toast.push(e instanceof ApiError ? e.message : '操作失败', 'danger')
    }
  }

  async function toggleArchive(entry: Entry) {
    const next = !entry.archived
    try {
      await api.updateEntry(entry.id, { archived: next })
      entry.archived = next
      stats.onArchiveToggled(entry, next)
      // 归档状态一变，这条就不属于"当前视图"了（每个视图都按 archived 过滤），
      // 所以统一让视图把它移出去，不管当前在哪个页
      ui.notifyEntryChange({ id: entry.id, kind: 'archive', archived: next })
      if (ui.selectedId === entry.id) ui.clearSelection()
      toast.push(next ? '已归档' : '已移出归档', 'success')
    } catch (e) {
      toast.push(e instanceof ApiError ? e.message : '操作失败', 'danger')
    }
  }

  async function remove(entry: Entry) {
    try {
      await api.deleteEntry(entry.id)
      stats.onDeleted(entry)
      ui.notifyEntryChange({ id: entry.id, kind: 'delete' })
      if (ui.selectedId === entry.id) ui.clearSelection()
      toast.push('已删除', 'danger')
    } catch (e) {
      toast.push(e instanceof ApiError ? e.message : '删除失败', 'danger')
    }
  }

  /** 复制原文。手机上长按选中经常选不准，给个按钮比教用户怎么选更快。 */
  async function copyText(entry: Entry) {
    try {
      await navigator.clipboard.writeText(entry.rawInput)
      toast.push('原文已复制', 'success')
    } catch {
      toast.push('复制失败，浏览器可能不允许', 'danger')
    }
  }

  return { toggleStar, toggleArchive, remove, copyText }
}
