export type EntryType = 'link' | 'repo' | 'video' | 'note' | 'file' | 'image' | 'unknown'

/** 对应后端 EntryRepository.Row。字段名是 camelCase，Jackson 直接序列化 record 组件名。 */
export interface Entry {
  id: number
  source: string
  entryType: EntryType
  rawInput: string
  url: string | null
  domain: string | null
  title: string | null
  aiSummary: string | null
  aiTags: string | null
  aiStatus: string
  createdAt: string
  starred: boolean
  archived: boolean
  /** 首张图片附件的 sha256。有值 = 这条带图，列表可以直接给缩略图 */
  imageSha: string | null
  /** 附件总数（图片 + 文件）。0 = 无附件 */
  attachmentCount: number
  /** 分类 id，null = 待分类 */
  categoryId: number | null
  categoryName: string | null
  /** AI 建议但尚未被采纳的新分类名；采纳或手动选分类后清空 */
  suggestedCategory: string | null
  /** 结构化标签名（来自 entry_tags）；空数组 = 没标签 */
  tags: string[]
}

export interface Category {
  id: number
  name: string
  color: string | null
  sortOrder: number
  system: boolean
  /** 该分类下未归档条目数 */
  count: number
}

export interface EntryLink {
  id: number
  url: string
  domain: string | null
  kind: string
  fetchedTitle: string | null
  fetchStatus: string
}

export interface BatchProgress {
  active: boolean
  total?: number
  enqueued?: number
}

export interface AiStatusInfo {
  enabled: boolean
  configured: boolean
  ready: boolean
  model: string
  polishNotes: boolean
  batch: BatchProgress
}

/** 附件元数据，详情面板用。见 GET /api/entries/{id}/attachments */
export interface AttachmentMeta {
  id: number
  sha256: string
  filename: string
  mime: string | null
  sizeBytes: number
}

export interface Page {
  items: Entry[]
  total: number
}

export interface Stats {
  total: number
  today: number
  week: number
  starred: number
  archived: number
  pendingAi: number
}

export interface User {
  id: number
  username: string
  email: string
  nickname: string | null
  lastLoginAt: string | null
}

export interface LoginResult {
  token: string
  expiresInSeconds: number
  user: User
}
