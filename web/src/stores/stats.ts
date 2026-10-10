import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '../api/http'
import type { Entry, Stats } from '../types'

/**
 * 顶部那排数字。
 *
 * 抽成 store 是因为侧栏和时间线都要显示它，各拉一次会打架
 * （一个刚创建完刷新了，另一个还是旧值）。
 *
 * 操作后走本地增量而不是重新请求：星标、归档这种高频小动作，
 * 每点一下就跑一次六子查询的统计接口不值当，而且数字会跳一下再回来，
 * 观感比"晚 200 毫秒才变"更差。
 * 增删这种会改变分页总数的，才真的重新拉。
 */
export const useStatsStore = defineStore('stats', () => {
  const data = ref<Stats | null>(null)

  async function load() {
    try {
      data.value = await api.stats()
    } catch {
      // 统计挂了不影响列表，静默忽略
    }
  }

  function patch(delta: Partial<Record<keyof Stats, number>>) {
    if (!data.value) return
    const next = { ...data.value }
    for (const [key, value] of Object.entries(delta)) {
      const k = key as keyof Stats
      // 计数不该出现负数。本地估算和服务器对不上时宁可显示 0，也不给个 -1
      next[k] = Math.max(0, next[k] + (value ?? 0))
    }
    data.value = next
  }

  function onCreated() {
    patch({ total: 1, today: 1, week: 1 })
  }

  function onDeleted(entry: Entry) {
    const d: Partial<Record<keyof Stats, number>> = {}
    if (entry.starred) d.starred = -1
    if (entry.archived) d.archived = -1
    else d.total = -1
    patch(d)
  }

  function onStarToggled(entry: Entry, starred: boolean) {
    // 归档箱里的条目加星也计数（后端 starred 统计带 archived=0 条件，这里会略偏差，
    // 但下一次真实请求会纠正，不值得为这点误差多做一次往返）
    if (entry.archived) return
    patch({ starred: starred ? 1 : -1 })
  }

  function onArchiveToggled(entry: Entry, archived: boolean) {
    patch({
      total: archived ? -1 : 1,
      archived: archived ? 1 : -1,
      starred: entry.starred ? (archived ? -1 : 1) : 0,
    })
  }

  return { data, load, patch, onCreated, onDeleted, onStarToggled, onArchiveToggled }
})
