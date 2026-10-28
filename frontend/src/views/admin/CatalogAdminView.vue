<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { catalogApi } from '@/api/services'
import type { RoomType } from '@/api/types'
import { useTask } from '@/composables/useTask'
import { day, money } from '@/shared/format'
import TaskNotice from '@/components/TaskNotice.vue'
const types = ref<RoomType[]>([]),
  editing = ref(''),
  showForm = ref(false),
  rateId = ref('')
const form = reactive({
    name: '',
    description: '',
    capacity: 2,
    basePrice: '100.00',
    active: true,
  }),
  rates = reactive({ start: day(1), end: day(2), price: '100.00' })
const { busy, error, success, run } = useTask()
async function load() {
  types.value = await catalogApi.types(true)
}
onMounted(() => run(load))
function edit(type?: RoomType) {
  editing.value = type?.id ?? ''
  Object.assign(
    form,
    type ?? { name: '', description: '', capacity: 2, basePrice: '100.00', active: true },
  )
  showForm.value = true
}
function save() {
  void run(async () => {
    await catalogApi.saveType(form, editing.value || undefined)
    showForm.value = false
    await load()
  }, '房型已保存。')
}

function saveRates() {
  void run(async () => {
    await catalogApi.rates(rateId.value, rates)
    rateId.value = ''
  }, '日期价格已保存；可在客房查询中核对报价。')
}

function editRates(type: RoomType) {
  rateId.value = type.id
  rates.price = type.basePrice
}
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">CATALOG & RATES</div>
      <h1>房型与价格</h1>
      <p>基础房价与日期价格由服务端统一计算。</p>
    </div>
    <button class="primary" :disabled="busy" @click="edit()">＋ 创建房型</button>
  </div>
  <TaskNotice :busy="busy" :error="error" :success="success" />
  <form v-if="showForm" class="panel" @submit.prevent="save">
    <h3>{{ editing ? '编辑房型 #' + editing : '创建房型' }}</h3>
    <fieldset :disabled="busy">
      <label>名称<input v-model="form.name" required maxlength="100" /></label
      ><label>说明<textarea v-model="form.description" maxlength="2000" /></label>
      <div class="grid">
        <label
          >入住人数上限<input
            v-model.number="form.capacity"
            type="number"
            min="1"
            max="20"
            required /></label
        ><label
          >基础房价（元/晚）<input
            v-model="form.basePrice"
            type="number"
            min="0.01"
            step="0.01"
            required
        /></label>
      </div>
      <label v-if="editing" class="check"
        ><input v-model="form.active" type="checkbox" />启用房型</label
      >
      <div class="row">
        <button class="primary">保存</button
        ><button type="button" @click="showForm = false">取消</button>
      </div>
    </fieldset>
  </form>
  <section class="panel">
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>名称</th>
            <th>容量</th>
            <th>基础价</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="type in types" :key="type.id">
            <td>
              {{ type.name }} <small>#{{ type.id }}</small>
            </td>
            <td>{{ type.capacity }}</td>
            <td>{{ money(type.basePrice) }}</td>
            <td>{{ type.active ? '启用' : '停用' }}</td>
            <td>
              <div class="row">
                <button :disabled="busy" @click="edit(type)">编辑</button
                ><button :disabled="busy" @click="editRates(type)">设置日期价</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="!types.length" class="empty">暂无房型，请先创建房型，再到客房页面添加实体房间。</p>
  </section>
  <form v-if="rateId" class="panel" @submit.prevent="saveRates">
    <h3>{{ types.find((t) => t.id === rateId)?.name }} · 日期价</h3>
    <fieldset :disabled="busy">
      <div class="grid">
        <label>开始日期<input v-model="rates.start" type="date" required /></label
        ><label
          >结束日期（不含）<input
            v-model="rates.end"
            type="date"
            :min="rates.start"
            required /></label
        ><label
          >每晚价格<input v-model="rates.price" type="number" step="0.01" min="0.01" required
        /></label>
      </div>
      <div class="row">
        <button class="primary">保存日期价</button
        ><button type="button" @click="rateId = ''">取消</button>
      </div>
    </fieldset>
  </form>
</template>
