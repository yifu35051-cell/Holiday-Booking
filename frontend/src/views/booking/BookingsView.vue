<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { bookingApi } from '@/api/services'
import type { Booking, Page } from '@/api/types'
import { useAuth, portal } from '@/stores/auth'
import { useTask } from '@/composables/useTask'
import { money, label } from '@/shared/format'
import TaskNotice from '@/components/TaskNotice.vue'
import PageNav from '@/components/PageNav.vue'
const auth = useAuth(),
  administrative = computed(() => auth.user?.role !== 'CUSTOMER')
const { busy, error, run } = useTask(),
  data = ref<Page<Booking>>({ items: [], page: 0, size: 20, total: 0 })
const filters = reactive({ status: '', from: '', to: '', userId: '' })
function load(page = 0) {
  void run(async () => {
    data.value = await bookingApi.list(
      { ...filters, userId: administrative.value ? filters.userId : undefined, page },
      administrative.value,
    )
  })
}
onMounted(() => load())
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">RESERVATIONS</div>
      <h1>{{ administrative ? '预订管理' : '我的预订' }}</h1>
      <p>
        {{
          administrative
            ? '查看与筛选全站预订；取消及改订由订单所有者办理。'
            : '所有行程与预订进度，尽在这里。'
        }}
      </p>
    </div>
    <RouterLink v-if="!administrative" class="button primary" to="/customer/explore"
      >＋ 新的预订</RouterLink
    >
  </div>
  <TaskNotice :busy="busy" :error="error" />
  <section class="panel">
    <form class="toolbar" @submit.prevent="load()">
      <label
        >状态<select v-model="filters.status">
          <option value="">全部</option>
          <option
            v-for="status in ['PENDING_PAYMENT', 'CONFIRMED', 'CANCELLED', 'EXPIRED']"
            :key="status"
            :value="status"
          >
            {{ label(status) }}
          </option>
        </select></label
      ><label>入住日期自<input v-model="filters.from" type="date" /></label
      ><label>至<input v-model="filters.to" type="date" /></label
      ><label v-if="administrative"
        >顾客编号<input v-model="filters.userId" inputmode="numeric" pattern="[1-9][0-9]*" /></label
      ><button :disabled="busy">查询</button>
    </form>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>预订</th>
            <th>入住 / 离店</th>
            <th>联系人</th>
            <th>总额</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="booking in data.items" :key="booking.id">
            <td>
              #{{ booking.id }}<small v-if="administrative"> / 顾客 {{ booking.userId }}</small>
            </td>
            <td>{{ booking.checkIn }} → {{ booking.checkOut }}</td>
            <td>{{ booking.contactName }}</td>
            <td>{{ money(booking.totalAmount) }}</td>
            <td>
              <span class="pill">{{ label(booking.status) }}</span>
            </td>
            <td>
              <RouterLink :to="portal(auth.user!.role) + '/bookings/' + booking.id"
                >查看详情 →</RouterLink
              >
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="!busy && !data.items.length" class="empty">没有符合条件的预订。</p>
    <PageNav
      :page="data.page"
      :count="data.items.length"
      :total="data.total"
      :busy="busy"
      @change="load"
    />
  </section>
</template>
