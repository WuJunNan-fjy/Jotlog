<template>
  <div class="pb-8">
    <h2 class="date-rule mb-5">设置</h2>

    <!-- 账号 -->
    <section class="border-line-soft border-b py-5">
      <div class="flex items-baseline justify-between gap-4">
        <span class="text-[13px] text-ink-3">用户名</span>
        <span class="text-[14px] text-ink">{{ auth.user?.username ?? '—' }}</span>
      </div>
      <div class="mt-2.5 flex items-baseline justify-between gap-4">
        <span class="text-[13px] text-ink-3">邮箱</span>
        <span class="text-[14px] text-ink">{{ auth.user?.email || '未设置' }}</span>
      </div>
      <p class="mt-2 text-[12px] leading-relaxed text-ink-3">
        登录验证码发到这个邮箱。改它需要先验证旧邮箱。
      </p>
    </section>

    <!-- 改密码 -->
    <section class="border-line-soft border-b py-5">
      <h3 class="mb-3 text-[14px] text-ink">修改密码</h3>
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
        <button
          type="submit"
          class="btn-ghost"
          :disabled="!oldPassword || !newPassword || pwdBusy"
        >
          {{ pwdBusy ? '提交中…' : '修改密码' }}
        </button>
      </form>
      <p v-if="pwdError" class="mt-2 text-[13px] text-danger">{{ pwdError }}</p>
      <p v-else class="mt-2 text-[12px] text-ink-3">改完所有设备都会退出登录。</p>
    </section>

    <!-- 换邮箱 -->
    <section class="border-line-soft border-b py-5">
      <h3 class="mb-3 text-[14px] text-ink">换绑邮箱</h3>
      <form class="space-y-2.5" @submit.prevent="submitEmail">
        <div class="flex gap-2">
          <input
            v-model="emailCode"
            class="field flex-1 tracking-[0.3em]"
            placeholder="验证码"
            inputmode="numeric"
            maxlength="6"
          />
          <button
            type="button"
            class="btn-ghost shrink-0"
            :disabled="sending || emailCooldown > 0"
            @click="sendEmailCode"
          >
            <template v-if="sending">发送中</template>
            <template v-else-if="emailCooldown > 0">{{ emailCooldown }} 秒</template>
            <template v-else>发验证码</template>
          </button>
        </div>
        <input v-model="newEmail" class="field" type="email" placeholder="新邮箱" />
        <button type="submit" class="btn-ghost" :disabled="!emailCode || !newEmail || emailBusy">
          {{ emailBusy ? '提交中…' : '确认换绑' }}
        </button>
      </form>
      <p v-if="emailError" class="mt-2 text-[13px] text-danger">{{ emailError }}</p>
      <p v-else-if="emailNotice" class="mt-2 text-[13px] text-accent">{{ emailNotice }}</p>
      <p v-else class="mt-2 text-[12px] text-ink-3">
        验证码发到当前邮箱，用来确认是你本人在操作。
      </p>
    </section>

    <!-- 登出 -->
    <section class="py-6">
      <button class="btn-ghost w-full text-danger" @click="doLogout">
        <Icon name="logout" class="h-4 w-4" />
        退出登录
      </button>
    </section>

    <p class="text-center text-[12px] leading-relaxed text-ink-3">
      Jotlog · 自托管随手记<br />
      数据只在你自己的 MySQL 里
    </p>
  </div>
</template>

<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import Icon from '../components/Icon.vue'

const auth = useAuthStore()
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

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

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
    // store 里已经清空了 token，路由守卫会把人送回登录页，这里再显式跳一次更稳
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
