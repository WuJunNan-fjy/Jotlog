import { defineStore } from 'pinia'
import { api } from '../api/http'
import type { Category } from '../types'

interface State {
  categories: Category[]
  uncategorized: number
  loaded: boolean
}

/**
 * 分类清单全局只拉一次：侧边栏、卡片色点、详情下拉共用同一份。
 * 分类/条目变动后由调用方显式 load(true) 刷新计数。
 */
export const useCategoryStore = defineStore('categories', {
  state: (): State => ({ categories: [], uncategorized: 0, loaded: false }),
  actions: {
    async load(force = false) {
      if (this.loaded && !force) return
      const r = await api.categories()
      this.categories = r.categories
      this.uncategorized = r.uncategorized
      this.loaded = true
    },
  },
})
