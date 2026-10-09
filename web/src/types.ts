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
