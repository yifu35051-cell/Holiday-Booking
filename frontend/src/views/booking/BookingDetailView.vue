<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { bookingApi, catalogApi, paymentApi } from '@/api/services'
import type { Booking, Change, Page, RoomType, StayInput } from '@/api/types'
import { commandKey } from '@/api/client'
import { useAuth, portal } from '@/stores/auth'
import { useTask } from '@/composables/useTask'
import { label, money, time, day } from '@/shared/format'
import TaskNotice from '@/components/TaskNotice.vue'
import PriceBreakdown from '@/components/PriceBreakdown.vue'
import StayFields from '@/components/StayFields.vue'
import PageNav from '@/components/PageNav.vue'
const route = useRoute(),
  auth = useAuth(),
  id = String(route.params.id),
  admin = computed(() => auth.user?.role !== 'CUSTOMER')
const { busy, error, success, run } = useTask(),
  booking = ref<Booking | null>(null),
  proposed = ref<Change | null>(null),
  amend = ref(false),
  types = ref<RoomType[]>([])
const changes = ref<Page<Change>>({ items: [], page: 0, size: 20, total: 0 })
const stay = ref<StayInput>({
  checkIn: day(7),
  checkOut: day(9),
  rooms: [{ roomTypeId: '', rooms: 1, guestsPerRoom: 1 }],
})
const key = commandKey()
// Changing the proposed stay invalidates the displayed quote, never submit an older price silently.
watch(
  stay,
  () => {
    proposed.value = null
  },
  { deep: true },
)
async function load(page = changes.value.page) {
  booking.value = await bookingApi.get(id, admin.value)
  if (!admin.value) changes.value = await bookingApi.changes(id, page)
}
onMounted(() =>
  run(async () => {
    await load()
    if (!admin.value) types.value = await catalogApi.types()
  }),
)
function confirmChange() {
  void run(async () => {
    if (!proposed.value) return
    const submitted = await bookingApi.submitChange(
      id,
      proposed.value,
      key({ id, change: proposed.value.id }),
    )
    proposed.value = null
    amend.value = false
    await load(0)
    success.value = submitted.paymentOrderId
      ? '改订已提交，请完成差额付款后等待生效。'
      : '变更已提交，请查看状态与退款记录。'
  })
}
function refreshChange(change: Change) {
  void run(async () => {
    const current = await bookingApi.change(id, change.id)
    for (const refund of current.refunds) await paymentApi.refund(refund.id, true)
    await load()
  })
}

function requestCancellation() {
  void run(async () => {
    proposed.value = await bookingApi.quoteCancel(id)
    amend.value = false
  })
}

function requestAmendment() {
  void run(async () => {
    proposed.value = await bookingApi.quoteAmend(id, stay.value)
  })
}

function toggleAmendment() {
  amend.value = !amend.value
  proposed.value = null
}
</script>
<template>
  <div class="page-heading">
    <div>
      <RouterLink :to="portal(auth.user!.role) + '/bookings'">← 返回预订列表</RouterLink>
      <h1>预订 #{{ id }}</h1>
    </div>
    <button :disabled="busy" @click="run(() => load())">刷新状态</button>
  </div>
  <TaskNotice :busy="busy" :error="error" :success="success" /><template v-if="booking"
    ><section class="panel">
      <div class="row spread">
        <span class="pill">{{ label(booking.status) }}</span
        ><span class="booking-total">{{ money(booking.totalAmount) }}</span>
      </div>
      <dl class="detail-list">
        <div>
          <dt>入住 / 离店</dt>
          <dd>{{ booking.checkIn }} → {{ booking.checkOut }}</dd>
        </div>
        <div>
          <dt>联系人</dt>
          <dd>{{ booking.contactName }} · {{ booking.contactPhone }}</dd>
        </div>
        <div>
          <dt>联系邮箱</dt>
          <dd>{{ booking.contactEmail }}</dd>
        </div>
        <div>
          <dt>付款截止 / 确认时间</dt>
          <dd>{{ time(booking.expiresAt) }} / {{ time(booking.confirmedAt) }}</dd>
        </div>
      </dl>
      <div class="row" v-if="!admin">
        <RouterLink class="button primary" :to="'/customer/payments/' + booking.paymentOrderId">{{
          booking.status === 'PENDING_PAYMENT' ? '前往付款' : '查看付款记录'
        }}</RouterLink
        ><button
          v-if="['PENDING_PAYMENT', 'CONFIRMED'].includes(booking.status)"
          :disabled="busy"
          @click="requestCancellation"
        >
          申请取消报价</button
        ><button v-if="booking.status === 'CONFIRMED'" :disabled="busy" @click="toggleAmendment">
          调整入住安排
        </button>
      </div>
    </section>
    <section class="panel">
      <h3>已分配客房 · 第 {{ booking.revision }} 版</h3>
      <p v-for="room in booking.rooms" :key="room.roomId">
        {{ room.price.roomTypeName }} · 房间 {{ room.roomNumber }} · {{ room.price.guests }} 人
      </p>
      <PriceBreakdown
        :snapshot="{
          checkIn: booking.checkIn,
          checkOut: booking.checkOut,
          discountRatio: '按预订快照',
          rooms: booking.rooms.map((r) => r.price),
        }"
      />
    </section>
    <form v-if="amend && !admin" class="panel" @submit.prevent="requestAmendment">
      <h3>新的入住安排</h3>
      <p class="hint">提前至少24小时可改订。补款后生效，减少金额按后端计算退还。</p>
      <fieldset :disabled="busy">
        <StayFields v-model="stay" :types="types" /><button class="primary">计算改订差价</button>
      </fieldset>
    </form>
    <section v-if="proposed" class="panel">
      <h3>{{ proposed.kind === 'CANCEL' ? '取消' : '改订' }}确认</h3>
      <p>差额 {{ money(proposed.priceDifference) }}（负数为退款，正数为补款）</p>
      <p>报价有效至 {{ time(proposed.expiresAt) }}</p>
      <PriceBreakdown v-if="proposed.proposedStay" :snapshot="proposed.proposedStay" />
      <div class="row">
        <button class="danger" :disabled="busy" @click="confirmChange">
          确认{{ proposed.kind === 'CANCEL' ? '取消预订' : '提交改订' }}</button
        ><button :disabled="busy" @click="proposed = null">暂不变更</button>
      </div>
    </section>
    <section v-if="!admin" class="panel">
      <h3>变更与退款记录</h3>
      <p v-if="!changes.items.length" class="empty">暂无变更记录。</p>
      <article v-for="change in changes.items" :key="change.id" class="panel">
        <div class="row spread">
          <h4>
            #{{ change.id }} · {{ change.kind === 'CANCEL' ? '取消' : '改订' }} ·
            {{ label(change.state) }}
          </h4>
          <span>{{ money(change.priceDifference) }}</span>
        </div>
        <div class="row">
          <RouterLink
            v-if="change.paymentOrderId"
            class="button"
            :to="'/customer/payments/' + change.paymentOrderId"
            >补款订单</RouterLink
          ><button :disabled="busy" @click="refreshChange(change)">刷新变更 / 退款</button>
        </div>
        <p v-for="refund in change.refunds" :key="refund.id">
          退款 #{{ refund.id }} · {{ money(refund.amount) }} · {{ label(refund.state) }}
          <small>{{ refund.detail }}</small>
        </p>
      </article>
      <PageNav
        :page="changes.page"
        :total="changes.total"
        :count="changes.items.length"
        :busy="busy"
        @change="(page) => run(() => load(page))"
      /></section
  ></template>
</template>
