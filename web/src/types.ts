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
