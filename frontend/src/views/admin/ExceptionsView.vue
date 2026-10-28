<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { managementApi } from '@/api/services'
import type { PaymentException } from '@/api/types'
import { useTask } from '@/composables/useTask'
import { time } from '@/shared/format'
import TaskNotice from '@/components/TaskNotice.vue'
import PageNav from '@/components/PageNav.vue'
const rows = ref<PaymentException[]>([]),
  page = ref(0),
  selected = ref<PaymentException | null>(null),
  note = ref(''),
  { busy, error, success, run } = useTask()
async function load(p = page.value) {
  rows.value = await managementApi.exceptions(p)
  page.value = p
}
onMounted(() => run(() => load()))

function saveReview() {
  void run(async () => {
    await managementApi.review(selected.value!.id, note.value)
    selected.value = null
    await load()
  }, '复核意见已保存。')
}

function selectException(item: PaymentException) {
  selected.value = item
  note.value = ''
}
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">PAYMENT REVIEW</div>
      <h1>支付异常复核</h1>
      <p>复核记录处理意见；此操作不会执行退款或修改支付金额。</p>
    </div>
    <button :disabled="busy" @click="run(() => load())">刷新</button>
  </div>
  <TaskNotice :busy="busy" :error="error" :success="success" />
  <section class="panel">
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>编号 / 支付单 / 尝试</th>
            <th>原因</th>
            <th>状态</th>
            <th>记录时间</th>
            <th>处理</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in rows" :key="item.id">
            <td>
              #{{ item.id }}<br />订单 {{ item.payment_order_id }} / 尝试 {{ item.attempt_id }}
            </td>
            <td>{{ item.reason || item.detail }}</td>
            <td>
              {{ item.status === 'REVIEWED' ? '已复核' : '待复核'
              }}<small>{{ item.review_note }}</small>
            </td>
            <td>{{ time(item.created_at) }}</td>
            <td>
              <button :disabled="busy || item.status === 'REVIEWED'" @click="selectException(item)">
                填写复核意见
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="!rows.length" class="empty">暂无支付异常。</p>
    <PageNav :page="page" :count="rows.length" :busy="busy" @change="(p) => run(() => load(p))" />
  </section>
  <form v-if="selected" class="panel" @submit.prevent="saveReview">
    <h3>复核异常 #{{ selected.id }}</h3>
    <label>处理意见<textarea v-model="note" required maxlength="1000" /></label>
    <div class="row">
      <button class="primary" :disabled="busy">保存复核</button
      ><button type="button" @click="selected = null">取消</button>
    </div>
  </form>
</template>
