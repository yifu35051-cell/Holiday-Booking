<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { membershipApi } from '@/api/services'
import type { Plan } from '@/api/types'
import { useTask } from '@/composables/useTask'
import { money } from '@/shared/format'
import TaskNotice from '@/components/TaskNotice.vue'
const plans = ref<Plan[]>([]),
  selected = ref<Plan | null>(null),
  { busy, error, success, run } = useTask()
const form = reactive({ name: '', fee: '99.00', discountRatio: '0.9000', durationDays: 365 })
async function load() {
  plans.value = await membershipApi.plans()
}
onMounted(() => run(load))

function createPlan() {
  void run(async () => {
    await membershipApi.create(form)
    form.name = ''
    await load()
  }, '方案已创建。')
}

function retirePlan() {
  void run(async () => {
    await membershipApi.retire(selected.value!.id)
    selected.value = null
    await load()
  }, '方案已停用。')
}
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">MEMBERSHIP PLANS</div>
      <h1>会员方案</h1>
      <p>已购买权益保留快照；修改方案请创建新方案并停用旧方案。</p>
    </div>
  </div>
  <TaskNotice :busy="busy" :error="error" :success="success" />
  <form class="panel" @submit.prevent="createPlan">
    <h3>创建方案</h3>
    <fieldset :disabled="busy">
      <div class="grid">
        <label>名称<input v-model="form.name" maxlength="100" required /></label
        ><label
          >费用（元）<input
            v-model="form.fee"
            type="number"
            min="0.01"
            step="0.01"
            required /></label
        ><label
          >房价比例（0.9表示九折）<input
            v-model="form.discountRatio"
            type="number"
            min="0.0001"
            max="1"
            step="0.0001"
            required /></label
        ><label
          >有效天数<input
            v-model.number="form.durationDays"
            type="number"
            min="1"
            max="3650"
            required
        /></label>
      </div>
      <button class="primary">创建方案</button>
    </fieldset>
  </form>
  <div class="cards">
    <article v-for="plan in plans" :key="plan.id" class="panel">
      <h3>{{ plan.name }}</h3>
      <p>{{ money(plan.fee) }} / {{ plan.durationDays }}天 · 房价比例 {{ plan.discountRatio }}</p>
      <button class="danger" :disabled="busy" @click="selected = plan">停用方案</button>
    </article>
  </div>
  <section v-if="selected" class="panel">
    <p>确定停止新用户购买「{{ selected.name }}」？已有会员权益不撤销。</p>
    <div class="row">
      <button class="danger" :disabled="busy" @click="retirePlan">确认停用</button
      ><button @click="selected = null">取消</button>
    </div>
  </section>
</template>
