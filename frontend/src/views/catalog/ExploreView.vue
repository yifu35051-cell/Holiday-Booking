<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { catalogApi, bookingApi } from '@/api/services'
import type { RoomType, Availability, StayInput, Quote } from '@/api/types'
import { commandKey } from '@/api/client'
import { useAuth } from '@/stores/auth'
import { useTask } from '@/composables/useTask'
import { day, money, time } from '@/shared/format'
import TaskNotice from '@/components/TaskNotice.vue'
import StayFields from '@/components/StayFields.vue'
import PriceBreakdown from '@/components/PriceBreakdown.vue'
const auth = useAuth(),
  router = useRouter(),
  { busy, error, success, run } = useTask()
const types = ref<RoomType[]>([]),
  available = ref<Availability[] | null>(null),
  detail = ref<RoomType | null>(null),
  quote = ref<Quote | null>(null)
const stay = ref<StayInput>({
  checkIn: day(1),
  checkOut: day(3),
  rooms: [{ roomTypeId: '', rooms: 1, guestsPerRoom: 1 }],
})
const contact = reactive({
  contactName: auth.user?.displayName ?? '',
  contactEmail: auth.user?.email ?? '',
  contactPhone: '',
})
const key = commandKey()
onMounted(() =>
  run(async () => {
    types.value = await catalogApi.types()
  }),
)
watch(
  stay,
  () => {
    quote.value = null
    available.value = null
  },
  { deep: true },
)
function availability() {
  void run(async () => {
    const selection = stay.value.rooms[0]!
    available.value = await catalogApi.availability({
      checkIn: stay.value.checkIn,
      checkOut: stay.value.checkOut,
      rooms: selection.rooms,
      guestsPerRoom: selection.guestsPerRoom,
    })
  })
}
function getQuote() {
  void run(async () => {
    quote.value = await bookingApi.quote(stay.value)
  })
}
function create() {
  void run(async () => {
    if (!quote.value) return
    const input = { quoteId: quote.value.id, ...contact }
    const result = await bookingApi.create(input, key(input))
    await router.push('/customer/bookings/' + result.id)
  })
}

function showRoomType(type: RoomType) {
  void run(async () => {
    detail.value = await catalogApi.detail(type.id)
  })
}
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">FIND YOUR STAY</div>
      <h1>探索客房</h1>
      <p>实时可用性查询 · 服务端报价 · 安心预订</p>
    </div>
  </div>
  <TaskNotice :busy="busy" :error="error" :success="success" />
  <div class="cards">
    <article v-for="type in types" :key="type.id" class="panel">
      <div class="room-art">HOLIDAY / {{ type.name }}</div>
      <h3>{{ type.name }}</h3>
      <p>{{ type.description || '舒适旅居，静享假日。' }}</p>
      <div class="row spread">
        <span>{{ money(type.basePrice) }} / 晚起 · {{ type.capacity }}人</span
        ><button :disabled="busy" @click="showRoomType(type)">查看详情</button>
      </div>
    </article>
  </div>
  <p v-if="!busy && !types.length" class="empty">暂未开放房型，请稍后再来。</p>
  <section v-if="detail" class="panel">
    <div class="row spread">
      <h3>{{ detail.name }}</h3>
      <button @click="detail = null">收起</button>
    </div>
    <p>{{ detail.description }}</p>
    <p>基础价 {{ money(detail.basePrice) }} / 晚 · 最多 {{ detail.capacity }} 人</p>
  </section>
  <form class="panel" @submit.prevent="getQuote">
    <h2>安排你的入住</h2>
    <fieldset :disabled="busy">
      <StayFields v-model="stay" :types="types" />
      <div class="row">
        <button type="button" @click="availability">查询可用客房</button
        ><button class="primary">获取本次报价 →</button>
      </div>
    </fieldset>
    <p class="hint">可用性按第一组人数及间数查询；完整多房型组合将在报价和下单时再次核验。</p>
  </form>
  <section v-if="available" class="panel">
    <h3>当前可用房型</h3>
    <p v-if="!available.length">所选条件下暂无可用客房。</p>
    <div v-for="item in available" :key="item.roomType.id" class="row spread">
      <span>{{ item.roomType.name }} · 可用 {{ item.availableRooms }} 间</span
      ><span>{{ item.nights }} 晚 / 每间 {{ money(item.totalPerRoom) }}</span>
    </div>
  </section>
  <section v-if="quote" class="panel">
    <div class="row spread">
      <h2>本次报价</h2>
      <span class="booking-total">{{ money(quote.totalAmount) }}</span>
    </div>
    <p>报价有效至 {{ time(quote.expiresAt) }}，尚未锁定库存。</p>
    <PriceBreakdown :snapshot="quote.snapshot" />
    <form @submit.prevent="create">
      <fieldset :disabled="busy">
        <div class="grid">
          <label>联系人<input v-model="contact.contactName" required maxlength="80" /></label
          ><label
            >联系邮箱<input
              v-model="contact.contactEmail"
              type="email"
              required
              maxlength="254" /></label
          ><label
            >联系电话<input
              v-model="contact.contactPhone"
              type="tel"
              required
              minlength="5"
              maxlength="30"
          /></label>
        </div>
        <button class="primary">确认预订，前往付款</button>
      </fieldset>
    </form>
  </section>
</template>
