<template>
  <div class="pb-4">
    <header class="mb-6">
      <h1 class="font-serif text-[21px] leading-tight tracking-wide text-ink">设置</h1>
      <p class="mt-1 text-[13px] text-ink-3">这个实例只有你一个账号，数据只在你自己的 MySQL 里</p>
    </header>

    <div class="space-y-4">
      <!-- 账号 -->
      <section class="card p-5">
        <h2 class="t-rule mb-4">账号</h2>
        <dl class="space-y-3">
          <div class="flex items-baseline justify-between gap-4">
            <dt class="text-[13px] text-ink-3">用户名</dt>
            <dd class="text-[14px] text-ink">{{ auth.user?.username ?? '—' }}</dd>
          </div>
          <div class="flex items-baseline justify-between gap-4">
            <dt class="text-[13px] text-ink-3">邮箱</dt>
            <dd class="truncate text-[14px] text-ink">{{ auth.user?.email || '未设置' }}</dd>
          </div>
          <div v-if="auth.user?.lastLoginAt" class="flex items-baseline justify-between gap-4">
            <dt class="text-[13px] text-ink-3">上次登录</dt>
            <dd class="tabular text-[13px] text-ink-2">{{ auth.user.lastLoginAt }}</dd>
          </div>
        </dl>
      </section>

      <!-- 外观 -->
      <section class="card p-5">
        <h2 class="t-rule mb-4">外观</h2>
        <div class="grid grid-cols-3 gap-2">
          <button
            v-for="opt in themeOptions"
            :key="opt.value"
            class="flex flex-col items-center gap-1.5 rounded-[4px] border py-3.5 transition-colors"
              :class="
                mode === opt.value
                  ? 'border-ink bg-ink text-paper'
                  : 'border-line text-ink-3 hover:text-ink'
              "
              @click="set(opt.value)"
          >
            <Icon :name="opt.icon" :size="19" />
            <span class="text-[12.5px]">{{ opt.label }}</span>
          </button>
        </div>
        <p class="mt-3 text-[12px] leading-relaxed text-ink-3">
          「跟随」会照着系统的深浅色走。深色不是把颜色反过来，是另一套调过的色板。
        </p>
      </section>

      <!-- 改密码 -->
      <section class="card p-5">
        <h2 class="t-rule mb-4">修改密码</h2>
        <form class="space-y-2.5" @submit.prevent="submitPassword">
          <input
            v-model="oldPassword"
            class="field"
            type="password"
            placeholder="当前密码"
            autocomplete="current-password"
          />
          <input
            v-model="newPassword"
            class="field"
            type="password"
            placeholder="新密码（至少 8 位）"
            autocomplete="new-password"
          />
          <div class="flex items-center gap-3">
            <button type="submit" class="btn btn-ghost" :disabled="!canSubmitPassword">
              {{ pwdBusy ? '提交中…' : '修改密码' }}
            </button>
            <span class="text-[12px] text-ink-3">改完所有设备都会退出登录</span>
          </div>
        </form>
        <p v-if="pwdError" class="mt-2 text-[13px] text-danger">{{ pwdError }}</p>
      </section>

      <!-- 换邮箱 -->
      <section class="card p-5">
        <h2 class="t-rule mb-1">换绑邮箱</h2>
        <p class="mb-4 text-[12px] leading-relaxed text-ink-3">
          验证码发到当前邮箱，用来确认是你本人在操作。
        </p>
        <form class="space-y-2.5" @submit.prevent="submitEmail">
          <div class="flex gap-2">
            <input
              v-model="emailCode"
              class="field flex-1 tracking-[0.3em]"
              placeholder="验证码"
              inputmode="numeric"
              maxlength="6"
              autocomplete="one-time-code"
            />
            <button
              type="button"
              class="btn btn-ghost shrink-0"
              :disabled="sending || emailCooldown > 0"
              @click="sendEmailCode"
            >
              <template v-if="sending">发送中</template>
              <template v-else-if="emailCooldown > 0">{{ emailCooldown }} 秒</template>
              <template v-else>发验证码</template>
            </button>
          </div>
          <input v-model="newEmail" class="field" type="email" placeholder="新邮箱" />
          <button type="submit" class="btn btn-ghost" :disabled="!emailCode || !newEmail || emailBusy">
            {{ emailBusy ? '提交中…' : '确认换绑' }}
          </button>
        </form>
        <p v-if="emailError" class="mt-2 text-[13px] text-danger">{{ emailError }}</p>
        <p v-else-if="emailNotice" class="mt-2 text-[13px] text-accent">{{ emailNotice }}</p>
      </section>

      <!-- AI 整理 -->
      <section class="card p-5">
        <h2 class="t-rule mb-4">AI 整理</h2>

        <!-- 服务状态：三态讲清楚"现在到底能不能用" -->
        <div class="flex items-center justify-between gap-3 text-[13px]">
          <span class="text-ink-3">服务状态</span>
          <span class="flex items-center gap-1.5 text-ink-2">
            <span
              class="h-[6px] w-[6px] rounded-full"
              :class="ai?.ready ? 'bg-accent' : ai?.enabled ? 'bg-danger' : 'bg-ink-4'"
            />
            {{ aiStatusText }}
          </span>
        </div>
        <p class="mt-1.5 text-[12px] leading-relaxed text-ink-3">
          模型与 Key 只走服务器配置文件和环境变量（JOTLOG_AI_KEY），页面不会显示也不会保存 Key。
        </p>

        <!-- 纯文本笔记是否参与整理 -->
        <label
          class="mt-4 flex cursor-pointer items-start justify-between gap-3 rounded-lg border border-line-soft px-3 py-2.5"
        >
          <span>
            <span class="block text-[13px] text-ink-2">纯文本笔记也交给 AI 整理</span>
            <span class="mt-0.5 block text-[12px] leading-relaxed text-ink-3">
              关着时，不带链接的随手记直接归入「灵感随记」，不花模型调用
            </span>
          </span>
          <input
            type="checkbox"
            class="mt-1 h-4 w-4 shrink-0 accent-ink"
            :checked="!!ai?.polishNotes"
            :disabled="!ai?.ready || noteSaving"
            @change="togglePolishNotes(($event.target as HTMLInputElement).checked)"
          />
        </label>

        <!-- 初始分类引导 -->
        <div class="border-line-soft mt-4 border-t pt-4">
          <div class="flex items-center justify-between gap-3">
            <p class="text-[13px] text-ink-2">初始分类</p>
            <button
              class="btn btn-quiet px-2.5 py-1 text-[12.5px]"
              :disabled="bootstrapLoading"
              @click="runBootstrap"
            >
              <Icon name="sparkles" :size="13" />
              {{ bootstrapLoading ? '生成中…' : '让 AI 推荐一套' }}
            </button>
          </div>
          <p class="mt-1 text-[12px] leading-relaxed text-ink-3">
            只是一份建议清单，勾哪些由你定，确认后才会创建；已存在的不会重复。
          </p>
          <div v-if="bootstrapCandidates.length" class="mt-2.5 space-y-1.5">
            <label
              v-for="name in bootstrapCandidates"
              :key="name"
              class="flex items-center gap-2 text-[13px] text-ink-2"
            >
              <input v-model="bootstrapPicked" type="checkbox" :value="name" class="h-4 w-4 accent-ink" />
              {{ name }}
            </label>
            <button
              class="btn btn-ghost mt-1 px-2.5 py-1 text-[12.5px]"
              :disabled="!bootstrapPicked.length || bulkSaving"
              @click="confirmCategories"
            >
              {{ bulkSaving ? '创建中…' : `创建所选（${bootstrapPicked.length}）` }}
            </button>
          </div>
        </div>

        <!-- 批量补跑历史内容 -->
        <div class="border-line-soft mt-4 border-t pt-4">
          <div class="flex items-center justify-between gap-3">
            <p class="text-[13px] text-ink-2">
              批量补跑<span v-if="statsStore.data" class="text-ink-3"> · 待整理 {{ statsStore.data.pendingAi }} 条</span>
            </p>
            <button
              v-if="!ai?.batch?.active"
              class="btn btn-quiet px-2.5 py-1 text-[12.5px]"
              :disabled="!ai?.ready || batchStarting"
              @click="batchStart"
            >
              <Icon name="refresh" :size="13" />
              {{ batchStarting ? '启动中…' : '开始补跑' }}
            </button>
            <button v-else class="btn btn-quiet px-2.5 py-1 text-[12.5px] text-danger" @click="batchStop">
              停止
            </button>
          </div>
          <p v-if="ai?.batch?.active" class="mt-1.5 text-[12px] text-ink-3">
            正在排队补跑{{
              ai.batch.total ? `（${Math.min(ai.batch.enqueued ?? 0, ai.batch.total)} / ${ai.batch.total}）` : '…'
            }}
          </p>
          <p v-else class="mt-1 text-[12px] leading-relaxed text-ink-3">
            按顺序把「待整理」的旧内容重新过一遍 AI，中途可以随时停止。
          </p>
        </div>
      </section>

      <!-- 数据 -->
      <section class="card p-5">
        <h2 class="t-rule mb-1">数据</h2>
        <p class="mb-4 text-[12px] leading-relaxed text-ink-3">
          导出这份实例里的全部记录（含归档和备注）。JSON 适合备份和搬家，Markdown 适合归档翻看。
        </p>
        <div class="flex flex-wrap gap-2">
          <button class="btn btn-ghost" :disabled="!!exporting" @click="doExport('json')">
            <Icon name="download" :size="15" />
            {{ exporting === 'json' ? '导出中…' : '导出 JSON' }}
          </button>
          <button class="btn btn-ghost" :disabled="!!exporting" @click="doExport('markdown')">
            <Icon name="download" :size="15" />
            {{ exporting === 'markdown' ? '导出中…' : '导出 Markdown' }}
          </button>
        </div>
        <p v-if="exportError" class="mt-2 text-[13px] text-danger">{{ exportError }}</p>
      </section>

      <!-- 快捷键 -->
      <section class="card p-5">
        <h2 class="t-rule mb-4">快捷键</h2>
        <dl class="space-y-2.5">
          <div v-for="k in hotkeys" :key="k.key" class="flex items-baseline gap-3">
            <dt class="w-24 shrink-0">
              <kbd class="kbd">{{ k.key }}</kbd>
            </dt>
            <dd class="text-[13px] text-ink-2">{{ k.desc }}</dd>
          </div>
        </dl>
        <p class="mt-3 text-[12px] text-ink-3">在输入框里打字时这些键不生效，照常输入字符。</p>
      </section>

      <!-- 关于 + 登出 -->
      <section class="card p-5">
        <h2 class="t-rule mb-4">关于</h2>
        <dl class="space-y-3">
          <div class="flex items-baseline justify-between gap-4">
            <dt class="text-[13px] text-ink-3">版本</dt>
            <dd class="tabular text-[13px] text-ink-2">v0.2</dd>
          </div>
          <div class="flex items-baseline justify-between gap-4">
            <dt class="text-[13px] text-ink-3">许可</dt>
            <dd class="text-[13px] text-ink-2">MIT</dd>
          </div>
          <div class="flex items-baseline justify-between gap-4">
            <dt class="text-[13px] text-ink-3">记录通道</dt>
            <dd class="text-[13px] text-ink-2">飞书 · 网页</dd>
          </div>
        </dl>

        <div class="border-line-soft mt-5 border-t pt-4">
          <button class="btn btn-danger w-full" @click="doLogout">
            <Icon name="logout" :size="16" />
            退出登录
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, ApiError, downloadExport } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { useCategoryStore } from '../stores/categories'
import { useStatsStore } from '../stores/stats'
import { useTheme } from '../composables/useTheme'
import type { ThemeMode } from '../composables/useTheme'
import { useToast } from '../composables/useToast'
import type { AiStatusInfo } from '../types'
import Icon from '../components/Icon.vue'

const auth = useAuthStore()
const { mode, set } = useTheme()
const toast = useToast()
const router = useRouter()
const categoryStore = useCategoryStore()
const statsStore = useStatsStore()

/* ---------- AI 整理 ---------- */

const ai = ref<AiStatusInfo | null>(null)
const noteSaving = ref(false)

const aiStatusText = computed(() => {
  if (!ai.value) return '加载中…'
  if (!ai.value.enabled) return '未启用（改配置文件开启）'
  if (!ai.value.configured) return '已启用，缺 Key'
  if (!ai.value.ready) return '模型不可用'
  return `已接入 · ${ai.value.model}`
})

async function refreshAi() {
  try {
    ai.value = await api.aiStatus()
  } catch {
    // 状态拉不到不阻塞设置页其他功能
  }
}

async function togglePolishNotes(next: boolean) {
  noteSaving.value = true
  try {
    const res = await api.updateAiSettings(next)
    if (ai.value) ai.value = { ...ai.value, polishNotes: res.polishNotes }
    toast.push(next ? '纯文本笔记也会参与整理' : '纯文本笔记不再调 AI', 'success')
  } catch (e) {
    toast.push(e instanceof ApiError ? e.message : '设置没保存成', 'danger')
  } finally {
    noteSaving.value = false
  }
}

const bootstrapCandidates = ref<string[]>([])
const bootstrapPicked = ref<string[]>([])
const bootstrapLoading = ref(false)
const bulkSaving = ref(false)

async function runBootstrap() {
  bootstrapLoading.value = true
  try {
    const res = await api.bootstrapCategories()
    bootstrapCandidates.value = res.suggestions
    bootstrapPicked.value = [...res.suggestions]
  } catch (e) {
    toast.push(e instanceof ApiError ? e.message : '建议没生成出来', 'danger')
  } finally {
    bootstrapLoading.value = false
  }
}

async function confirmCategories() {
  if (!bootstrapPicked.value.length) return
  bulkSaving.value = true
  try {
    await api.bulkCategories(bootstrapPicked.value)
    await categoryStore.load(true)
    toast.push('分类已创建', 'success')
    bootstrapCandidates.value = []
    bootstrapPicked.value = []
  } catch (e) {
    toast.push(e instanceof ApiError ? e.message : '创建失败', 'danger')
  } finally {
    bulkSaving.value = false
  }
}

const batchStarting = ref(false)
let batchTimer: ReturnType<typeof setInterval> | null = null

function stopBatchPolling() {
  if (batchTimer) {
    clearInterval(batchTimer)
    batchTimer = null
  }
}

function startBatchPolling() {
  stopBatchPolling()
  batchTimer = setInterval(async () => {
    await refreshAi()
    if (!ai.value?.batch?.active) {
      stopBatchPolling()
      statsStore.load().catch(() => {})
    }
  }, 2000)
}

async function batchStart() {
  batchStarting.value = true
  try {
    const res = await api.batchRepolish('start')
    if (ai.value) ai.value = { ...ai.value, batch: { active: res.active, total: res.total, enqueued: res.enqueued } }
    startBatchPolling()
    toast.push('批量补跑已开始', 'success')
  } catch (e) {
    toast.push(e instanceof ApiError ? e.message : '没有启动起来', 'danger')
  } finally {
    batchStarting.value = false
  }
}

async function batchStop() {
  try {
    await api.batchRepolish('stop')
    stopBatchPolling()
    await refreshAi()
    statsStore.load().catch(() => {})
    toast.push('已停止补跑', 'success')
  } catch (e) {
    toast.push(e instanceof ApiError ? e.message : '停止失败', 'danger')
  }
}

onMounted(() => {
  refreshAi()
  statsStore.load().catch(() => {})
})

onUnmounted(stopBatchPolling)

const oldPassword = ref('')
const newPassword = ref('')
const pwdBusy = ref(false)
const pwdError = ref('')

const emailCode = ref('')
const newEmail = ref('')
const emailBusy = ref(false)
const sending = ref(false)
const emailError = ref('')
const emailNotice = ref('')
const emailCooldown = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

const hotkeys = [
  { key: '/', desc: '去搜索' },
  { key: 'n', desc: '记一笔' },
  { key: 'j / k', desc: '在列表里下移 / 上移' },
  { key: 'Esc', desc: '关闭详情或弹层' },
  { key: 'Ctrl + Enter', desc: '输入框里直接记下' },
]

const themeOptions: { value: ThemeMode; label: string; icon: string }[] = [
  { value: 'system', label: '跟随系统', icon: 'monitor' },
  { value: 'light', label: '浅色', icon: 'sun' },
  { value: 'dark', label: '深色', icon: 'moon' },
]

const canSubmitPassword = computed(
  () => oldPassword.value.length > 0 && newPassword.value.length >= 8 && !pwdBusy.value,
)

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

/** 60 秒倒计时。后端也有冷却，这里只是让用户不用白点。 */
function startCooldown() {
  emailCooldown.value = 60
  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    emailCooldown.value -= 1
    if (emailCooldown.value <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

async function submitPassword() {
  if (pwdBusy.value) return
  pwdError.value = ''
  if (newPassword.value.length < 8) {
    pwdError.value = '新密码至少 8 位'
    return
  }
  pwdBusy.value = true
  try {
    await auth.changePassword(oldPassword.value, newPassword.value)
    toast.push('密码已修改，请重新登录', 'success')
    await router.replace('/login')
  } catch (e) {
    pwdError.value = e instanceof ApiError ? e.message : '修改失败'
  } finally {
    pwdBusy.value = false
  }
}

async function sendEmailCode() {
  if (sending.value || emailCooldown.value > 0) return
  sending.value = true
  emailError.value = ''
  emailNotice.value = ''
  try {
    await auth.sendCode(auth.user?.username ?? '')
    emailNotice.value = '验证码已发到当前邮箱'
    startCooldown()
  } catch (e) {
    emailError.value = e instanceof ApiError ? e.message : '发送失败'
  } finally {
    sending.value = false
  }
}

async function submitEmail() {
  if (emailBusy.value) return
  emailError.value = ''
  emailNotice.value = ''
  emailBusy.value = true
  try {
    await auth.changeEmail(emailCode.value.trim(), newEmail.value.trim())
    emailNotice.value = '邮箱已更新'
    emailCode.value = ''
    newEmail.value = ''
    toast.push('邮箱已换绑', 'success')
  } catch (e) {
    emailError.value = e instanceof ApiError ? e.message : '换绑失败'
  } finally {
    emailBusy.value = false
  }
}

async function doLogout() {
  await auth.logout()
  await router.replace('/login')
}

// ---------- 数据导出 ----------
const exporting = ref<'' | 'json' | 'markdown'>('')
const exportError = ref('')

async function doExport(format: 'json' | 'markdown') {
  if (exporting.value) return
  exportError.value = ''
  exporting.value = format
  try {
    await downloadExport(format)
    toast.push('导出已开始，看浏览器下载', 'success')
  } catch (e) {
    exportError.value = e instanceof ApiError ? e.message : '导出失败'
  } finally {
    exporting.value = ''
  }
}
</script>
