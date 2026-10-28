<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { paymentApi } from '@/api/services'
import type { SandboxWallet, WalletEntry } from '@/api/types'
import { commandKey } from '@/api/client'
import { useTask } from '@/composables/useTask'
import { money, time } from '@/shared/format'
import TaskNotice from './TaskNotice.vue'

const emit = defineEmits<{ change: [wallet: SandboxWallet] }>()
const wallet = ref<SandboxWallet | null>(null)
const entries = ref<WalletEntry[]>([])
const page = ref(0)
const loginPassword = ref(''),
  pin = ref(''),
  confirmation = ref(''),
  oldPin = ref('')
const creditPin = ref(''),
  creditAmount = ref('1000.00')
let creditKey = commandKey()
const { busy, error, success, run } = useTask()

async function reload() {
  wallet.value = await paymentApi.wallet()
  emit('change', wallet.value)
  entries.value = await paymentApi.entries(page.value)
}
defineExpose({ reload })
onMounted(() => void run(reload))

function savePin() {
  void run(async () => {
    try {
      if (wallet.value?.configured) {
        await paymentApi.changePin(oldPin.value, pin.value, confirmation.value)
      } else {
        await paymentApi.setupWallet(loginPassword.value, pin.value, confirmation.value)
      }
    } finally {
      loginPassword.value = pin.value = confirmation.value = oldPin.value = ''
      await reload().catch(() => {})
    }
  }, '沙箱支付密码已保存。')
}

function credit() {
  void run(async () => {
    try {
      await paymentApi.credit(
        creditAmount.value,
        creditPin.value,
        creditKey({ amount: creditAmount.value }),
      )
      creditKey = commandKey()
      page.value = 0
    } finally {
      creditPin.value = ''
      await reload().catch(() => {})
    }
  }, '模拟充值完成；未产生真实资金交易。')
}

function turnPage(delta: number) {
  void run(async () => {
    const next = page.value + delta
    const rows = await paymentApi.entries(next)
    page.value = next
    entries.value = rows
  })
}
const entryLabels = { CREDIT: '模拟充值', PAYMENT: '付款扣款', REFUND: '退款到账' }
</script>

<template>
  <section class="panel">
    <div class="row spread">
      <h2>沙箱钱包</h2>
      <button :disabled="busy" @click="run(reload)">刷新余额与流水</button>
    </div>
    <p>仅用于本项目模拟付款。请设置独立的6位数字密码，不要使用真实银行卡或支付平台密码。</p>
    <TaskNotice :busy="busy" :error="error" :success="success" />
    <template v-if="wallet">
      <p class="booking-total">可用模拟余额：{{ money(wallet.balance) }}</p>
      <p v-if="wallet.lockedUntil" role="alert">
        支付验证已锁定，解锁时间：{{ time(wallet.lockedUntil) }}。解锁后请刷新。
      </p>
      <p v-else>密码验证剩余尝试次数：{{ wallet.attemptsRemaining }}；连续错误5次锁定15分钟。</p>
      <details :open="!wallet.configured">
        <summary>{{ wallet.configured ? '修改支付密码' : '第一步：设置支付密码' }}</summary>
        <form class="toolbar" @submit.prevent="savePin">
          <label v-if="!wallet.configured"
            >验证本项目登录密码
            <input
              v-model="loginPassword"
              type="password"
              autocomplete="current-password"
              maxlength="72"
              required
            />
          </label>
          <label v-else
            >原沙箱支付密码
            <input
              v-model="oldPin"
              type="password"
              inputmode="numeric"
              pattern="[0-9]{6}"
              maxlength="6"
              autocomplete="off"
              required
            />
          </label>
          <label
            >新的6位数字支付密码
            <input
              v-model="pin"
              type="password"
              inputmode="numeric"
              pattern="[0-9]{6}"
              maxlength="6"
              autocomplete="new-password"
              required
            />
          </label>
          <label
            >再次输入支付密码
            <input
              v-model="confirmation"
              type="password"
              inputmode="numeric"
              pattern="[0-9]{6}"
              maxlength="6"
              autocomplete="new-password"
              required
            />
          </label>
          <button class="primary" :disabled="busy || !!wallet.lockedUntil">保存支付密码</button>
        </form>
      </details>
      <details v-if="wallet.configured">
        <summary>模拟充值（不连接银行）</summary>
        <form class="toolbar" @submit.prevent="credit">
          <label
            >充值额度<select v-model="creditAmount">
              <option value="100.00">100元</option>
              <option value="1000.00">1000元</option>
              <option value="10000.00">10000元</option>
            </select></label
          >
          <label
            >沙箱支付密码
            <input
              v-model="creditPin"
              type="password"
              inputmode="numeric"
              pattern="[0-9]{6}"
              maxlength="6"
              autocomplete="off"
              required
            />
          </label>
          <button :disabled="busy || !!wallet.lockedUntil">确认模拟充值</button>
        </form>
      </details>
      <details>
        <summary>资金流水（第{{ page + 1 }}页）</summary>
        <p v-if="!entries.length" class="empty">本页没有资金流水。</p>
        <article v-for="entry in entries" :key="entry.id" class="panel">
          <strong>{{ entryLabels[entry.kind] }} · {{ money(entry.amount) }}</strong>
          <p>交易后余额 {{ money(entry.balanceAfter) }} · {{ time(entry.createdAt) }}</p>
          <small>{{ entry.reference }}</small>
        </article>
        <div class="row">
          <button :disabled="busy || page === 0" @click="turnPage(-1)">上一页</button>
          <button :disabled="busy || entries.length < 20" @click="turnPage(1)">下一页</button>
        </div>
      </details>
    </template>
  </section>
</template>
