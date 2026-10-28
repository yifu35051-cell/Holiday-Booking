<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useAuth, portal } from '@/stores/auth'
import { mailApi } from '@/api/services'
import { label } from '@/shared/format'
import { useTask } from '@/composables/useTask'
import TaskNotice from '@/components/TaskNotice.vue'
const auth = useAuth(),
  unread = ref(0),
  { error, run } = useTask()
const base = computed(() => portal(auth.user!.role))
onMounted(() =>
  run(async () => {
    unread.value = (await mailApi.unread()).count
  }),
)
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">YOUR HOLIDAY SPACE</div>
      <h1>你好，{{ auth.user?.displayName }}</h1>
      <p>欢迎回到你的{{ label(auth.user!.role) }}工作空间。</p>
    </div>
    <span class="pill">{{ new Date().toLocaleDateString('zh-CN') }}</span>
  </div>
  <TaskNotice :error="error" />
  <section class="hero">
    <div>
      <div class="eyebrow">
        {{ auth.user?.role === 'CUSTOMER' ? 'MAKE ROOM FOR A HOLIDAY' : 'EVERY DETAIL MATTERS' }}
      </div>
      <h2>
        {{ auth.user?.role === 'CUSTOMER' ? '给生活，留一间好房。' : '今天的服务，从这里开始。' }}
      </h2>
      <p>
        {{
          auth.user?.role === 'CUSTOMER'
            ? '选择入住日期，查看实时可用客房与专属报价。'
            : '查看预订、维护客房状态，让每次入住井然有序。'
        }}
      </p>
      <RouterLink
        class="button cream"
        :to="base + (auth.user?.role === 'CUSTOMER' ? '/explore' : '/bookings')"
        >{{ auth.user?.role === 'CUSTOMER' ? '探索客房' : '查看预订' }} →</RouterLink
      >
    </div>
    <div class="hero-art" aria-hidden="true">
      <div class="arch"></div>
      <span>STAY<br />A LITTLE<br />LONGER.</span>
    </div>
  </section>
  <div class="cards">
    <RouterLink :to="base + '/mail'" class="panel"
      ><div class="eyebrow">YOUR MAILBOX</div>
      <h2>{{ unread }} <small>封未读邮件</small></h2>
      <p>查看通知或联系其他账户 →</p></RouterLink
    >
    <section class="panel">
      <div class="eyebrow">YOUR ACCOUNT</div>
      <h3>{{ auth.user?.email }}</h3>
      <p>账户编号 {{ auth.user?.id }} · {{ label(auth.user!.role) }}</p>
      <p class="hint">账号权限由服务端管理。切换身份请先退出登录。</p>
    </section>
  </div>
</template>
