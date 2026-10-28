<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { managementApi } from '@/api/services'
import type { User, Page, Role } from '@/api/types'
import { useAuth } from '@/stores/auth'
import { useTask } from '@/composables/useTask'
import { label } from '@/shared/format'
import TaskNotice from '@/components/TaskNotice.vue'
import PageNav from '@/components/PageNav.vue'
const data = ref<Page<User>>({ items: [], page: 0, size: 20, total: 0 }),
  selected = ref<User | null>(null),
  nextRole = ref<Role>('CUSTOMER'),
  auth = useAuth()
const { busy, error, success, run } = useTask()
async function load(page = data.value.page) {
  data.value = await managementApi.users(page)
}
onMounted(() => run(() => load()))

function saveRole() {
  void run(async () => {
    await managementApi.role(selected.value!.id, nextRole.value)
    selected.value = null
    await load()
  }, '角色已更新。该用户需使用对应登录入口。')
}

function selectUser(user: User) {
  selected.value = user
  nextRole.value = user.role
}
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">ACCOUNTS & ACCESS</div>
      <h1>账户与角色</h1>
      <p>公开注册始终创建顾客。权限变更会影响该账户后续请求。</p>
    </div>
  </div>
  <TaskNotice :busy="busy" :error="error" :success="success" />
  <section class="panel">
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>编号</th>
            <th>姓名</th>
            <th>邮箱</th>
            <th>角色</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in data.items" :key="user.id">
            <td>{{ user.id }}</td>
            <td>{{ user.displayName }}</td>
            <td>{{ user.email }}</td>
            <td>{{ label(user.role) }}</td>
            <td>
              <button :disabled="busy || user.id === auth.user?.id" @click="selectUser(user)">
                调整权限
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <PageNav
      :page="data.page"
      :count="data.items.length"
      :total="data.total"
      :busy="busy"
      @change="(p) => run(() => load(p))"
    />
  </section>
  <form v-if="selected" class="panel" @submit.prevent="saveRole">
    <h3>调整 {{ selected.displayName }} 的角色</h3>
    <p>{{ selected.email }} · 当前{{ label(selected.role) }}</p>
    <label
      >新角色<select v-model="nextRole">
        <option v-for="role in ['CUSTOMER', 'STAFF', 'ADMIN']" :key="role" :value="role">
          {{ label(role) }}
        </option>
      </select></label
    >
    <div class="row">
      <button class="primary" :disabled="busy || nextRole === selected.role">确认变更</button
      ><button type="button" :disabled="busy" @click="selected = null">取消</button>
    </div>
  </form>
</template>
