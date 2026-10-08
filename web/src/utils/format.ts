/** 日期时间格式化。后端给的是 "2026-10-08T14:32:11.123" 这种本地时间串（不含时区）。 */

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** 从 "2026-10-08T14:32" 里切出日期和时间，不走 Date 解析，避免时区把本地时间挪走。 */
function parts(iso: string): { date: string; time: string } {
  const [date = '', time = ''] = iso.split('T')
  return { date, time: time.slice(0, 5) }
}

export function formatTime(iso: string): string {
  return parts(iso).time
}

/** 分组标题：今天 / 昨天 / 10月8日 周三 */
export function dayLabel(iso: string): string {
  const { date } = parts(iso)
  if (!date) return ''

  const today = todayStr()
  if (date === today) return '今天'
  if (date === shiftDay(today, -1)) return '昨天'
  if (date === shiftDay(today, -2)) return '前天'

  const [, month = '', day = ''] = date.split('-')
  const weekday = weekdayOf(date)
  return `${Number(month)}月${Number(day)}日 ${weekday}`
}

/** 分组用的 key，同组同 key。 */
export function dayKey(iso: string): string {
  return parts(iso).date
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

function weekdayOf(dateStr: string): string {
  const [y, m, d] = dateStr.split('-').map(Number)
  return WEEKDAYS[new Date(y, m - 1, d).getDay()]
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}
