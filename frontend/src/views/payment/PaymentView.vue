<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { paymentApi } from '@/api/services'
import type { Attempt, Payment, SandboxWallet } from '@/api/types'
import { commandKey } from '@/api/client'
import { useTask } from '@/composables/useTask'
import { label, money, time } from '@/shared/format'
import TaskNotice from '@/components/TaskNotice.vue'
import SandboxWalletPanel from '@/components/SandboxWalletPanel.vue'

const id = String(useRoute().params.id)
const payment = ref<Payment | null>(null)
const method = ref('Alipay')
const pin = ref('')
const selected = ref<Attempt | null>(null)
const wallet = ref<SandboxWallet | null>(null)
const walletPanel = ref<InstanceType<typeof SandboxWalletPanel> | null>(null)
const now = ref(Date.now())
let key = commandKey()
const { busy, error, success, run } = useTask()
const secondsLeft = computed(() =>
  payment.value
    ? Math.max(0, Math.floor((Date.parse(payment.value.expiresAt) - now.value) / 1000))
    : 0,
)
const payable = computed(() => payment.value?.status === 'PENDING' && secondsLeft.value > 0)
const pending = computed(() =>
  payment.value?.attempts.some((a) => ['CREATED', 'PENDING', 'UNKNOWN'].includes(a.status)),
)
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  timer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
  void run(async () => {
    payment.value = await paymentApi.get(id)
  })
})
onUnmounted(() => {
  clearInterval(timer)
  pin.value = ''
})

function start() {
  void run(async () => {
    if (!payment.value) return
    const input = { id, amount: payment.value.amount, method: method.value }
    payment.value = await paymentApi.start(id, input.amount, input.method, key(input))
    key = commandKey()
  })
}
function refreshPayment() {
  void run(async () => {
    payment.value = await paymentApi.refresh(id)
    await walletPanel.value?.reload()
  })
}
function openCheckout(attempt: Attempt) {
  selected.value = attempt
  pin.value = ''
}
function dismissCheckout() {
  selected.value = null
  pin.value = ''
}
function authorize() {
  void run(async () => {
    if (!selected.value) return
    try {
      payment.value = await paymentApi.authorize(selected.value.id, pin.value)
      selected.value = null
    } finally {
      pin.value = ''
      await walletPanel.value?.reload().catch(() => {})
    }
  }, '付款结果已更新，可返回预订或会员页面查看。')
}
function cancel(attempt: Attempt) {
  void run(async () => {
    payment.value = await paymentApi.cancel(attempt.id)
    dismissCheckout()
    key = commandKey()
  }, '当前交易已查询/关闭，预订本身未取消；若已付款，以实际结果为准。')
}
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">PAYMENT</div>
      <h1>付款订单 #{{ id }}</h1>
      <p>先检查金额与收款方，再授权付款。沙箱支付不产生真实扣款。</p>
    </div>
    <button :disabled="busy" @click="refreshPayment">查询支付结果</button>
  </div>
  <TaskNotice :busy="busy" :error="error" :success="success" />
  <template v-if="payment">
    <section class="panel">
      <div class="row spread">
        <span class="pill">{{ label(payment.status) }}</span
        ><span class="booking-total">{{ money(payment.amount) }}</span>
      </div>
      <p>收款方：Holiday Booking · 币种：{{ payment.currency }}</p>
      <p>
        有效至 {{ time(payment.expiresAt)
        }}<span v-if="payment.status === 'PENDING'"> · 剩余 {{ secondsLeft }} 秒</span>
      </p>
      <p v-if="payment.status === 'PENDING' && !secondsLeft">
        已超过付款期限，请查询支付结果以同步状态。
      </p>
      <form v-if="payable" class="toolbar" @submit.prevent="start">
        <label
          >付款方式<select v-model="method">
            <option value="Alipay">
              {{ payment.provider === 'MOCK' ? '模拟支付宝' : '支付宝沙箱' }}
            </option>
            <option v-if="payment.provider === 'MOCK'" value="Wechat">模拟微信</option>
          </select></label
        >
        <button class="primary" :disabled="busy || pending">创建支付请求</button>
      </form>
      <p v-if="pending">已有在途交易，请在下方确认付款、取消当前交易或查询结果。</p>
    </section>
    <SandboxWalletPanel
      v-if="payment.provider === 'MOCK'"
      ref="walletPanel"
      @change="wallet = $event"
    />
    <section v-if="selected && payable" class="panel" aria-labelledby="checkout-title">
      <h2 id="checkout-title">确认付款</h2>
      <p>
        收款方：Holiday Booking · 订单 #{{ id }} ·
        {{ selected.method === 'Alipay' ? '模拟支付宝' : '模拟微信' }}
      </p>
      <p class="booking-total">本次扣款 {{ money(payment.amount) }}</p>
      <p>可用余额 {{ money(wallet?.balance ?? '0.00') }}；两种模拟方式共用同一个沙箱钱包。</p>
      <form class="toolbar" @submit.prevent="authorize">
        <label
          >6位沙箱支付密码<input
            v-model="pin"
            type="password"
            inputmode="numeric"
            pattern="[0-9]{6}"
            maxlength="6"
            autocomplete="off"
            required
        /></label>
        <button class="primary" :disabled="busy || !wallet?.configured || !!wallet?.lockedUntil">
          授权扣款 {{ money(payment.amount) }}
        </button>
        <button type="button" :disabled="busy" @click="dismissCheckout">暂不付款</button>
      </form>
    </section>
    <section class="panel">
      <h2>支付尝试记录</h2>
      <p v-if="!payment.attempts.length" class="empty">尚未发起支付。</p>
      <article v-for="attempt in payment.attempts" :key="attempt.id" class="panel">
        <div class="row spread">
          <h3>{{ attempt.method }} · {{ label(attempt.status) }}</h3>
          <small>{{ attempt.provider }}</small>
        </div>
        <p>交易号 {{ attempt.outTradeNo }}</p>
        <p v-if="attempt.paidAt">支付时间 {{ time(attempt.paidAt) }}</p>
        <div
          v-if="
            attempt.provider === 'MOCK' &&
            ['CREATED', 'PENDING', 'UNKNOWN'].includes(attempt.status) &&
            payable
          "
          class="row"
        >
          <button
            class="primary"
            :disabled="busy || !wallet?.configured || !!wallet?.lockedUntil"
            @click="openCheckout(attempt)"
          >
            进入收银台
          </button>
          <button :disabled="busy" @click="cancel(attempt)">取消当前交易</button>
        </div>
        <p v-else-if="attempt.checkoutUrl && attempt.provider !== 'MOCK'" class="hint">
          渠道支付内容：{{ attempt.checkoutUrl }}
        </p>
      </article>
      <div class="row">
        <RouterLink to="/customer/bookings">返回我的预订</RouterLink
        ><RouterLink to="/customer/membership">返回会员权益</RouterLink>
      </div>
    </section>
  </template>
</template>
