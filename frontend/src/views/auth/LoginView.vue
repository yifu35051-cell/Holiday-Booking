<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import type { Role } from '@/api/types'
import { authApi } from '@/api/services'
import { useAuth, portal } from '@/stores/auth'
import { useTask } from '@/composables/useTask'
import TaskNotice from '@/components/TaskNotice.vue'
import { label } from '@/shared/format'
const props = defineProps<{ role: Role }>()
const auth = useAuth(),
  router = useRouter(),
  email = ref(''),
  password = ref(''),
  displayName = ref(''),
  register = ref(false)
const { busy, error, success, run } = useTask()
function submit() {
  void run(async () => {
    if (register.value) {
      await authApi.register(email.value, password.value, displayName.value)
      register.value = false
      password.value = ''
      success.value = '注册成功，请使用新账户登录。'
      return
    }
    await auth.login(props.role, email.value, password.value)
    password.value = ''
    await router.replace(portal(props.role))
  })
}

function toggleRegistration() {
  register.value = !register.value
  error.value = ''
  success.value = ''
}
</script>
<template>
  <main class="login-page">
    <section class="login-story">
      <RouterLink to="/" class="light">← 返回入口</RouterLink>
      <div>
        <div class="eyebrow">HOLIDAY / {{ role }}</div>
        <h1>
          {{
            role === 'CUSTOMER'
              ? '下一段美好，从这里出发。'
              : role === 'STAFF'
                ? '用心服务，每一次抵达。'
                : '从容管理，每一个细节。'
          }}
        </h1>
        <p>属于{{ label(role) }}的独立工作空间</p>
      </div>
      <span>HOLIDAY BOOKING</span>
    </section>
    <section class="login-form">
      <div class="eyebrow">WELCOME BACK</div>
      <h2>{{ register ? '创建顾客账户' : label(role) + '登录' }}</h2>
      <p class="muted">
        {{ register ? '注册仅创建顾客账户。' : '请使用与你的入口身份一致的账户。' }}
      </p>
      <TaskNotice :busy="busy" :error="error" :success="success" />
      <form @submit.prevent="submit">
        <label v-if="register"
          >称呼<input v-model="displayName" required maxlength="80" autocomplete="name" /></label
        ><label
          >邮箱<input
            v-model="email"
            type="email"
            required
            maxlength="254"
            autocomplete="username" /></label
        ><label
          >密码<input
            v-model="password"
            type="password"
            required
            :minlength="register ? 12 : 1"
            maxlength="72"
            :autocomplete="register ? 'new-password' : 'current-password'"
        /></label>
        <p v-if="register" class="hint">密码12–72字符，UTF-8编码不超过72字节。</p>
        <button class="primary" :disabled="busy">
          {{ register ? '注册账户' : '登录' + label(role) + '空间' }} →
        </button>
      </form>
      <button
        v-if="role === 'CUSTOMER'"
        class="text-button"
        :disabled="busy"
        @click="toggleRegistration"
      >
        {{ register ? '已有账户？返回登录' : '还没有账户？立即注册' }}
      </button>
      <p v-else class="hint">员工和管理员账户由项目管理员分配权限。</p>
    </section>
  </main>
</template>
