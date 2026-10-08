<template>
  <div class="safe-x safe-b flex min-h-[100dvh] items-center justify-center px-5 py-10">
    <div class="anim-up w-full max-w-[392px]">
      <!-- 品牌 -->
      <div class="mb-8 text-center">
        <span
          class="mb-4 inline-grid h-12 w-12 place-items-center rounded-xl bg-accent font-serif text-[22px] leading-none text-white shadow-md dark:text-[#10201c]"
        >
          J
        </span>
        <h1 class="font-serif text-[27px] leading-none tracking-wide text-ink">Jotlog</h1>
        <p class="mt-2.5 text-[13px] leading-relaxed text-ink-3">
          随手记一笔，落进自己的数据库。<br />
          手机和电脑，同一个网址。
        </p>
      </div>

      <form class="card p-6 shadow-md" @submit.prevent="submit">
        <div class="space-y-3">
          <label class="block">
            <span class="mb-1.5 block text-[12.5px] text-ink-3">用户名</span>
            <input
              ref="userInput"
              v-model="username"
              class="field"
              placeholder="你的用户名"
              autocomplete="username"
              autocapitalize="off"
              spellcheck="false"
            />
          </label>

          <label class="block">
            <span class="mb-1.5 block text-[12.5px] text-ink-3">密码</span>
            <input
              v-model="password"
              class="field"
              type="password"
              placeholder="密码"
              autocomplete="current-password"
            />
          </label>

          <label class="block">
            <span class="mb-1.5 block text-[12.5px] text-ink-3">邮箱验证码</span>
            <div class="flex gap-2">
              <input
                v-model="code"
                class="field flex-1 tracking-[0.3em]"
                placeholder="6 位数字"
                inputmode="numeric"
                maxlength="6"
                autocomplete="one-time-code"
              />
              <button
                type="button"
                class="btn btn-ghost shrink-0"
                :disabled="!username.trim() || sending || cooldown > 0"
                @click="send"
              >
                <template v-if="sending">发送中</template>
                <template v-else-if="cooldown > 0">{{ cooldown }} 秒</template>
                <template v-else>发送</template>
              </button>
            </div>
          </label>
        </div>

        <button type="submit" class="btn btn-primary mt-5 w-full py-2.5" :disabled="busy">
          {{ busy ? '登录中…' : '登录' }}
        </button>

        <Transition
          enter-active-class="transition duration-200"
          enter-from-class="opacity-0 -translate-y-1"
        >
          <p
            v-if="error"
            class="mt-3 rounded-lg bg-danger-soft px-3.5 py-2.5 text-[13px] leading-relaxed text-danger"
          >
            {{ error }}
          </p>
          <p
            v-else-if="notice"
            class="mt-3 rounded-lg bg-accent-soft px-3.5 py-2.5 text-[13px] leading-relaxed text-accent"
          >
            {{ notice }}
          </p>
        </Transition>
      </form>

      <p class="mt-6 text-center text-[12px] leading-relaxed text-ink-3">
        验证码发到账号登记的邮箱。<br />
        没配 SMTP 时，后端日志里也能找到它。
      </p>

      <!-- 主题切换在登录页也要有：晚上十一点打开一个刺眼的登录页，
           跟打开一个刺眼的主界面一样让人想关掉 -->
      <div class="mt-5 flex justify-center">
        <button
          class="btn btn-quiet px-3 py-1.5 text-[12.5px]"
          :title="currentlyDark() ? '切到浅色' : '切到深色'"
          @click="toggle()"
        >
          <Icon :name="currentlyDark() ? 'sun' : 'moon'" :size="15" />
          {{ currentlyDark() ? '浅色' : '深色' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { useTheme } from '../composables/useTheme'
import Icon from '../components/Icon.vue'

const auth = useAuthStore()
const { toggle, currentlyDark } = useTheme()
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
const userInput = ref<HTMLInputElement | null>(null)
let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  // 记住上次登录的用户名。个人自用系统，每次都要重打一遍自己的用户名纯属折磨
  const last = localStorage.getItem('jotlog.lastUser')
  if (last) username.value = last

  // 手机上不自动聚焦：键盘一上来就把表单顶掉半屏，还没看清是什么页面
  if (window.innerWidth >= 768) userInput.value?.focus()
})

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
    localStorage.setItem('jotlog.lastUser', username.value.trim())
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '登录失败，稍后再试'
  } finally {
    busy.value = false
  }
}
</script>
