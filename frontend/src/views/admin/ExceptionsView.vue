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
const recoveryRows = ref<Awaited<ReturnType<typeof managementApi.recovery>>>([])
async function load(p = page.value) {
  const [exceptions, recovery] = await Promise.all([
    managementApi.exceptions(p),
    managementApi.recovery(),
  ])
  rows.value = exceptions
  recoveryRows.value = recovery
  page.value = p
}
function releaseRecovery(key: string) {
  void run(async () => {
    await managementApi.releaseRecovery(key)
    await load()
  }, '已重新开放自动核验；此操作不会直接确认付款或退款。')
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
    <h2>需要人工介入的渠道恢复</h2>
    <p>先检查渠道故障原因，再重新开放重试。重试沿用原交易号，不会创建新的扣款。</p>
    <p v-if="!recoveryRows.length" class="empty">暂无超过自动重试预算的任务。</p>
    <div v-for="item in recoveryRows" :key="item.key" class="row spread">
      <span>{{ item.key }} · 失败 {{ item.failures }} 次 · {{ time(item.nextAttemptAt) }}</span>
      <button :disabled="busy" @click="releaseRecovery(item.key)">重新开放核验</button>
    </div>
  </section>
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
