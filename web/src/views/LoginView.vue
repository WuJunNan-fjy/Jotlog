<template>
  <div class="flex min-h-screen items-center justify-center px-5 py-12">
    <div class="fade-up w-full max-w-[360px]">
      <div class="mb-9 text-center">
        <h1 class="font-serif text-[30px] leading-none tracking-wide text-ink">Jotlog</h1>
        <p class="mt-2.5 text-[13px] text-ink-3">登录后查看你的随手记</p>
      </div>

      <form class="space-y-3" @submit.prevent="submit">
        <input
          v-model="username"
          class="field"
          placeholder="用户名"
          autocomplete="username"
          autocapitalize="off"
          spellcheck="false"
        />
        <input
          v-model="password"
          class="field"
          type="password"
          placeholder="密码"
          autocomplete="current-password"
        />

        <div class="flex gap-2">
          <input
            v-model="code"
            class="field flex-1 tracking-[0.3em]"
            placeholder="验证码"
            inputmode="numeric"
            maxlength="6"
            autocomplete="one-time-code"
          />
          <button
            type="button"
            class="btn-ghost shrink-0"
            :disabled="!username.trim() || sending || cooldown > 0"
            @click="send"
          >
            <template v-if="sending">发送中</template>
            <template v-else-if="cooldown > 0">{{ cooldown }} 秒</template>
            <template v-else>发验证码</template>
          </button>
        </div>

        <button type="submit" class="btn-primary w-full" :disabled="busy">
          {{ busy ? '登录中…' : '登录' }}
        </button>

        <p v-if="error" class="rounded bg-danger/8 px-3 py-2 text-[13px] text-danger">
          {{ error }}
        </p>
        <p v-else-if="notice" class="rounded bg-accent-soft px-3 py-2 text-[13px] text-accent">
          {{ notice }}
        </p>
      </form>

      <p class="mt-7 text-center text-[12px] leading-relaxed text-ink-3">
        验证码发到账号登记的邮箱。<br />
        没配 SMTP 时，后端日志里也能找到它。
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const code = ref('')
const busy = ref(false)
const sending = ref(false)
const error = ref('')
const notice = ref('')
const cooldown = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

/** 60 秒倒计时。后端也有冷却，这里只是让用户不用白点。 */
function startCooldown() {
  cooldown.value = 60
  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

async function send() {
  if (!username.value.trim() || sending.value || cooldown.value > 0) return
  sending.value = true
  error.value = ''
  notice.value = ''
  try {
    const res = await auth.sendCode(username.value.trim())
    notice.value = `验证码已发送，${res.ttlMinutes} 分钟内有效`
    startCooldown()
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '发送失败，稍后再试'
  } finally {
    sending.value = false
  }
}

async function submit() {
  if (busy.value) return
  error.value = ''
  notice.value = ''

  if (!username.value.trim() || !password.value || !code.value.trim()) {
    error.value = '用户名、密码、验证码都要填'
    return
  }

  busy.value = true
  try {
    await auth.login(username.value.trim(), password.value, code.value.trim())
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '登录失败，稍后再试'
  } finally {
    busy.value = false
  }
}
</script>
