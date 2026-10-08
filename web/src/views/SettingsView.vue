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
            class="flex flex-col items-center gap-1.5 rounded-lg border py-3.5 transition-colors"
              :class="
                mode === opt.value
                  ? 'border-accent bg-accent-soft text-accent'
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
import { computed, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { useTheme } from '../composables/useTheme'
import type { ThemeMode } from '../composables/useTheme'
import { useToast } from '../composables/useToast'
import Icon from '../components/Icon.vue'

const auth = useAuthStore()
const { mode, set } = useTheme()
const toast = useToast()
const router = useRouter()

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
</script>
