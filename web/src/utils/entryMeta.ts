import type { EntryType } from '../types'

/**
 * 条目类型的展示元数据。
 *
 * 集中在这里是因为卡片、详情面板、搜索筛选都要用同一套颜色和文案 ——
 * 散在三个组件里写 switch，改一个类型要动三处，迟早会不一致。
 */
interface TypeMeta {
  label: string
  icon: string
  /** chip 的配色。语义色，不是具体色值 —— 深色模式下会自己换 */
  tone: 'accent' | 'brass' | 'neutral'
}

const TYPES: Record<EntryType, TypeMeta> = {
  link: { label: '链接', icon: 'link', tone: 'accent' },
  repo: { label: '仓库', icon: 'repo', tone: 'accent' },
  video: { label: '视频', icon: 'video', tone: 'brass' },
  note: { label: '随记', icon: 'note', tone: 'neutral' },
  file: { label: '文件', icon: 'file', tone: 'neutral' },
  image: { label: '图片', icon: 'image', tone: 'neutral' },
  unknown: { label: '未分类', icon: 'unknown', tone: 'neutral' },
}

export function typeMeta(type: string): TypeMeta {
  return TYPES[type as EntryType] ?? TYPES.unknown
}

/** 搜索页顶部的类型筛选。顺序按实际使用频率排，不按字母。 */
export const TYPE_FILTERS: { value: string; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'note', label: '随记' },
  { value: 'link', label: '链接' },
  { value: 'repo', label: '仓库' },
  { value: 'video', label: '视频' },
  { value: 'image', label: '图片' },
  { value: 'file', label: '文件' },
]

const SOURCES: Record<string, string> = {
  feishu: '飞书',
  web: '网页',
  pwa: '手机',
  manual: '手动',
  cli: '命令行',
  api: '接口',
}

export function sourceLabel(source: string): string {
  return SOURCES[source] ?? source
}

/** chip 配色 → Tailwind class。tone 是语义，class 在这里落地，只有这一处知道具体颜色 */
export function toneClass(tone: TypeMeta['tone']): string {
  if (tone === 'accent') return 'bg-accent-soft text-accent'
  if (tone === 'brass') return 'bg-brass-soft text-brass'
  return 'bg-paper-sunken text-ink-3'
}

/**
 * AI 状态的中文说明。
 *
 * 只在详情面板展示：列表里报"处理中"会把注意力从内容上抢走，
 * 而用户真正在意的是"这条记下来了没"，答案是记下来了。
 */
export function aiStatusLabel(status: string): string {
  switch (status) {
    case 'done':
      return '已补充'
    case 'pending':
      return '排队中'
    case 'processing':
      return '处理中'
    case 'failed':
      return '补充失败'
    case 'skipped':
      return '已跳过'
    default:
      return status || '未处理'
  }
}
