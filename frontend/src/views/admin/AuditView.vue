<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { managementApi } from '@/api/services'
import type { Audit } from '@/api/types'
import { useTask } from '@/composables/useTask'
import { time } from '@/shared/format'
import TaskNotice from '@/components/TaskNotice.vue'
import PageNav from '@/components/PageNav.vue'
const rows = ref<Audit[]>([]),
  page = ref(0),
  { busy, error, run } = useTask()
async function load(p = page.value) {
  rows.value = await managementApi.audit(p)
  page.value = p
}
onMounted(() => run(() => load()))
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">AUDIT TRAIL</div>
      <h1>审计记录</h1>
      <p>角色调整、业务维护、人工处理与管理员查信记录。</p>
    </div>
    <button :disabled="busy" @click="run(() => load())">刷新</button>
  </div>
  <TaskNotice :busy="busy" :error="error" />
  <section class="panel">
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>时间</th>
            <th>操作者</th>
            <th>操作</th>
            <th>资源</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in rows" :key="item.id">
            <td>{{ time(item.createdAt) }}</td>
            <td>{{ item.actorId || '系统' }}</td>
            <td>{{ item.action }}</td>
            <td>{{ item.resourceType }} #{{ item.resourceId }}</td>
            <td>{{ item.detail || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="!rows.length" class="empty">暂无审计记录。</p>
    <PageNav :page="page" :count="rows.length" :busy="busy" @change="(p) => run(() => load(p))" />
  </section>
</template>
