/** 日期时间格式化。后端给的是 "2026-10-08T14:32:11.123" 这种本地时间串（不带时区）。 */

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/**
 * 从 "2026-10-08T14:32" 里切出日期和时间。
 *
 * 刻意不走 new Date(iso) —— MySQL 的 LocalDateTime 序列化出来没有时区后缀，
 * 一旦被当成 UTC 解析，北京时间会整体偏 8 小时，日期分组直接错位一天。
 */
function parts(iso: string): { date: string; time: string } {
  const [date = '', time = ''] = iso.split('T')
  return { date, time: time.slice(0, 5) }
}

/** 列表里那个窄时间列：14:32 */
export function formatTime(iso: string): string {
  return parts(iso).time
}

/** 详情面板里的完整时间戳：10月8日 周三 14:32 */
export function formatFull(iso: string): string {
  const { date, time } = parts(iso)
  if (!date) return ''
  const [y, m, d] = date.split('-').map(Number)
  if (!y || !m || !d) return date
  const weekday = WEEKDAYS[new Date(y, m - 1, d).getDay()]
  const showYear = y !== new Date().getFullYear()
  return `${showYear ? `${y}年` : ''}${m}月${d}日 ${weekday} ${time}`
}

/** 详情面板顶部的日期大字：8 */
export function dayNumber(iso: string): string {
  const { date } = parts(iso)
  const [, , d = ''] = date.split('-')
  return String(Number(d) || '')
}

/** 详情面板顶部跟在日期后面的：十月 · 2026 */
export function monthYear(iso: string): string {
  const { date } = parts(iso)
  const [y = '', m = ''] = date.split('-')
  const names = ['一月', '二月', '三月', '四月', '五月', '六月', '七月', '八月', '九月', '十月', '十一月', '十二月']
  return `${names[Number(m) - 1] ?? ''}${y ? ` · ${y}` : ''}`
}

/** 分组标题：今天 / 昨天 / 10月8日 周三 */
export function dayLabel(iso: string): string {
  const { date } = parts(iso)
  if (!date) return ''

  const today = todayStr()
  if (date === today) return '今天'
  if (date === shiftDay(today, -1)) return '昨天'
  if (date === shiftDay(today, -2)) return '前天'

  const [y, m, d] = date.split('-').map(Number)
  const weekday = WEEKDAYS[new Date(y, m - 1, d).getDay()]
  const showYear = y !== new Date().getFullYear()
  return `${showYear ? `${y}年` : ''}${m}月${d}日 ${weekday}`
}

/** 分组用的 key，同组同 key。 */
export function dayKey(iso: string): string {
  return parts(iso).date
}

/**
 * 今天的本地日期串，格式跟后端一致，可以直接喂给 dayLabel。
 * 不能用 toISOString() —— 那是 UTC，北京时间早上八点前会算成昨天。
 */
export function todayIso(): string {
  const now = new Date()
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T00:00`
}

/** 从一堆 URL 里取首字母块用的显示名。没有域名就用主机名首字符。 */
export function hostLabel(url: string, domain?: string | null): string {
  if (domain) return domain
  try {
    return new URL(url).hostname.replace(/^www\./, '')
  } catch {
    return url
  }
}

function todayStr(): string {
  const now = new Date()
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

function shiftDay(dateStr: string, delta: number): string {
  const [y, m, d] = dateStr.split('-').map(Number)
  const dt = new Date(y, m - 1, d + delta)
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}`
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}
