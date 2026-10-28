<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { membershipApi } from '@/api/services'
import type { Plan, Membership, Purchase, Page } from '@/api/types'
import { commandKey } from '@/api/client'
import { useTask } from '@/composables/useTask'
import { money, time, label } from '@/shared/format'
import TaskNotice from '@/components/TaskNotice.vue'
import PageNav from '@/components/PageNav.vue'
const plans = ref<Plan[]>([]),
  member = ref<Membership | null>(null),
  purchases = ref<Page<Purchase>>({ items: [], page: 0, size: 20, total: 0 })
const router = useRouter(),
  { busy, error, run } = useTask(),
  key = commandKey()
async function load(page = 0) {
  ;[plans.value, member.value, purchases.value] = await Promise.all([
    membershipApi.plans(),
    membershipApi.me(),
    membershipApi.purchases(page),
  ])
}
onMounted(() => run(() => load()))
function buy(id: string) {
  void run(async () => {
    const result = await membershipApi.buy(id, key(id))
    await router.push('/customer/payments/' + result.paymentOrderId)
  })
}

function refreshPurchase(purchase: Purchase) {
  void run(async () => {
    await membershipApi.purchase(purchase.id)
    await load(purchases.value.page)
  })
}
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">MEMBERSHIP</div>
      <h1>会员权益</h1>
      <p>付款成功后激活；有效会员续期沿用原方案。</p>
    </div>
    <button :disabled="busy" @click="run(() => load(purchases.page))">刷新权益</button>
  </div>
  <TaskNotice :busy="busy" :error="error" />
  <section v-if="member" class="hero">
    <div>
      <h2>{{ member.active ? '你的会员权益已生效' : '为每次入住，多一份礼遇' }}</h2>
      <p>当前房价比例 {{ member.discountRatio }} · 有效至 {{ time(member.validUntil) }}</p>
      <button
        v-if="member.active && member.planId"
        class="cream"
        :disabled="busy"
        @click="buy(member.planId)"
      >
        按原方案续期
      </button>
    </div>
  </section>
  <div class="cards">
    <article v-for="plan in plans" :key="plan.id" class="panel">
      <h3>{{ plan.name }}</h3>
      <h2>{{ money(plan.fee) }}</h2>
      <p>{{ plan.durationDays }} 天 · 房价比例 {{ plan.discountRatio }}</p>
      <button v-if="!member?.active" class="primary" :disabled="busy" @click="buy(plan.id)">
        购买会员
      </button>
    </article>
  </div>
  <section class="panel">
    <h3>购买与续期记录</h3>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>购买单</th>
            <th>金额</th>
            <th>状态</th>
            <th>权益</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="purchase in purchases.items" :key="purchase.id">
            <td>#{{ purchase.id }}</td>
            <td>{{ money(purchase.fee) }}</td>
            <td>{{ label(purchase.status) }}</td>
            <td>{{ purchase.durationDays }}天 / {{ purchase.discountRatio }}</td>
            <td>
              <RouterLink :to="'/customer/payments/' + purchase.paymentOrderId"
                >查看 / 付款</RouterLink
              ><button :disabled="busy" @click="refreshPurchase(purchase)">刷新</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="!purchases.items.length" class="empty">暂无购买记录。</p>
    <PageNav
      :page="purchases.page"
      :total="purchases.total"
      :count="purchases.items.length"
      :busy="busy"
      @change="(page) => run(() => load(page))"
    />
  </section>
</template>
