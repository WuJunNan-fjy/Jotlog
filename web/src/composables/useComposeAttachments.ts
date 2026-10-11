import { ref } from 'vue'
import { api } from '../api/http'

/** 与后端 spring.servlet.multipart.max-file-size 保持一致。 */
export const MAX_ATTACHMENT_SIZE = 20 * 1024 * 1024
/** 一条随手记最多挂几个附件，防止手机上误选一大堆。 */
export const MAX_ATTACHMENTS = 9

export interface PendingAttachment {
  /** 组件内 key，只在本次编辑期有效，跟服务端无关 */
  key: string
  file: File
  /** 图片的本地预览（objectURL）；非图片为 null */
  previewUrl: string | null
  isImage: boolean
}

/**
 * 「记一笔」的待传附件队列。
 *
 * 桌面 ComposeBox 和手机 ComposeSheet 共用同一份逻辑：
 * 选取/粘贴只入队（不等网络），点「记下」时先建条目拿到 id，再顺序上传。
 * 顺序而不是并发：手机弱网下一堆并发请求更容易集体超时；单用户场景省点比慢点重要。
 */
export function useComposeAttachments() {
  const files = ref<PendingAttachment[]>([])
  let seq = 0

  /** @returns 被拦下的文件原因列表（空数组 = 全收） */
  function addFiles(incoming: File[] | FileList): string[] {
    const rejected: string[] = []
    for (const file of Array.from(incoming)) {
      if (files.value.length >= MAX_ATTACHMENTS) {
        rejected.push(`一次最多带 ${MAX_ATTACHMENTS} 个附件`)
        break
      }
      if (file.size === 0) {
        rejected.push(`「${file.name || '空文件'}」是空的`)
        continue
      }
      if (file.size > MAX_ATTACHMENT_SIZE) {
        rejected.push(`「${file.name}」超过 20MB`)
        continue
      }
      const isImage = file.type.startsWith('image/')
      files.value.push({
        key: `${Date.now()}-${seq++}`,
        file,
        previewUrl: isImage ? URL.createObjectURL(file) : null,
        isImage,
      })
    }
    return rejected
  }

  function removeAt(key: string) {
    const idx = files.value.findIndex((f) => f.key === key)
    if (idx >= 0) {
      const f = files.value[idx]
      if (f.previewUrl) URL.revokeObjectURL(f.previewUrl)
      files.value.splice(idx, 1)
    }
  }

  function clear() {
    files.value.forEach((f) => f.previewUrl && URL.revokeObjectURL(f.previewUrl))
    files.value = []
  }

  /**
   * 顺序上传到已建好的条目。
   *
   * @returns 失败个数。单文件失败不中断其余文件 —— 条目已经在了，
   * 能挂几个是几个，失败的由调用方提示用户。
   */
  async function uploadAll(entryId: number): Promise<number> {
    let failed = 0
    for (const f of files.value) {
      try {
        await api.uploadAttachment(entryId, f.file)
      } catch {
        failed++
      }
    }
    return failed
  }

  /** 纯附件（没写一个字）时用文件名拼一条正文，raw_input 不允许为空。 */
  function fallbackText(): string {
    return files.value.map((f) => f.file.name).join('，')
  }

  return { files, addFiles, removeAt, clear, uploadAll, fallbackText }
}
