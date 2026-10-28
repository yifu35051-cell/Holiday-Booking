<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { managementApi } from '@/api/services'
import type { Notification } from '@/api/types'
import { useTask } from '@/composables/useTask'
import { time, label } from '@/shared/format'
import TaskNotice from '@/components/TaskNotice.vue'
import PageNav from '@/components/PageNav.vue'
const rows = ref<Notification[]>([]),
  page = ref(0),
  selected = ref<Notification | null>(null),
  acknowledge = ref(false)
const { busy, error, success, run } = useTask()
async function load(p = page.value) {
  rows.value = await managementApi.notifications(p)
  page.value = p
}
onMounted(() => run(() => load()))

function retryTask() {
  void run(async () => {
    await managementApi.retryNotification(selected.value!.id, acknowledge.value)
    selected.value = null
    await load()
  }, '已重新加入投递队列。')
}

function selectTask(item: Notification) {
  selected.value = item
  acknowledge.value = false
}
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">BUSINESS NOTIFICATIONS</div>
      <h1>通知任务</h1>
      <p>预订确认与入住提醒；此处不展示个人邮件正文。</p>
    </div>
    <button :disabled="busy" @click="run(() => load())">刷新</button>
  </div>
  <TaskNotice :busy="busy" :error="error" :success="success" />
  <section class="panel">
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>任务 / 预订</th>
            <th>类型</th>
            <th>收件人</th>
            <th>状态 / 尝试</th>
            <th>计划时间</th>
            <th>处理</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in rows" :key="item.id">
            <td>#{{ item.id }} / #{{ item.bookingId }}</td>
            <td>{{ item.type }}</td>
            <td>{{ item.recipient }}</td>
            <td>{{ label(item.state) }} / {{ item.attempts }}</td>
            <td>{{ time(item.dueAt) }}</td>
            <td>
              <button
                v-if="['FAILED', 'UNKNOWN'].includes(item.state)"
                :disabled="busy"
                @click="selectTask(item)"
              >
                核对并重试
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="!rows.length" class="empty">暂无通知任务。</p>
    <PageNav :page="page" :count="rows.length" :busy="busy" @change="(p) => run(() => load(p))" />
  </section>
  <section v-if="selected" class="panel">
    <h3>任务 #{{ selected.id }}</h3>
    <p>{{ selected.lastError }}</p>
    <label v-if="selected.state === 'UNKNOWN'" class="check"
      ><input v-model="acknowledge" type="checkbox" />已核对并接受可能重复投递的风险</label
    >
    <div class="row">
      <button :disabled="busy || (selected.state === 'UNKNOWN' && !acknowledge)" @click="retryTask">
        确认重试</button
      ><button @click="selected = null">关闭</button>
    </div>
  </section>
</template>
