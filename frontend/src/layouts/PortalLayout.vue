<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth, portal } from '@/stores/auth'
import { useTask } from '@/composables/useTask'
import TaskNotice from '@/components/TaskNotice.vue'
import { label } from '@/shared/format'
const auth = useAuth(),
  router = useRouter()
const { busy, error, run } = useTask()
const base = computed(() => portal(auth.user?.role ?? 'CUSTOMER'))
const links = computed(() => {
  const role = auth.user?.role
  const business =
    role === 'CUSTOMER'
      ? [
          ['explore', '探索客房'],
          ['bookings', '我的预订'],
          ['membership', '会员权益'],
          ['wallet', '沙箱钱包'],
        ]
      : [
          ['bookings', '预订管理'],
          ['rooms', '客房与封房'],
          ['notifications', '通知任务'],
        ]
  const admin =
    role === 'ADMIN'
      ? [
          ['catalog', '房型与价格'],
          ['plans', '会员方案'],
          ['users', '账户与角色'],
          ['all-mail', '全站邮件'],
          ['exceptions', '支付异常'],
          ['audit', '审计记录'],
        ]
      : []
  return [['', '工作台'], ...business, ['mail', '我的邮箱'], ...admin].map(([path, title]) => ({
    path: path!,
    title: title!,
  }))
})
function logout() {
  void run(async () => {
    const path = `/login${base.value}`
    await auth.logout()
    await router.replace(path)
  })
}
</script>
<template>
  <div v-if="auth.user" class="portal" :data-role="auth.user.role">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">H</span>
        <div>HOLIDAY<span class="brand-small">假日旅居</span></div>
      </div>
      <p class="portal-label">{{ label(auth.user.role) }}空间</p>
      <nav aria-label="工作空间导航">
        <RouterLink
          v-for="item in links"
          :key="item.path"
          :to="base + (item.path ? '/' + item.path : '')"
          :class="{ home: !item.path }"
          >{{ item.title }}</RouterLink
        >
      </nav>
      <div class="sidebar-footer">让每一段旅程，从容开始。</div>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <span
          >Holiday Booking <span class="muted">/ {{ label(auth.user.role) }}工作空间</span></span
        >
        <div class="row">
          <span>{{ auth.user.displayName }}</span
          ><button :disabled="busy" @click="logout">退出登录</button>
        </div>
      </header>
      <main id="main">
        <TaskNotice :error="error" /><RouterView v-slot="{ Component, route }"
          ><component :is="Component" :key="route.path"
        /></RouterView>
      </main>
    </div>
  </div>
</template>
