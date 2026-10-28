<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { catalogApi } from '@/api/services'
import type { RoomType, Room, Block } from '@/api/types'
import { useTask } from '@/composables/useTask'
import { day } from '@/shared/format'
import TaskNotice from '@/components/TaskNotice.vue'
const types = ref<RoomType[]>([]),
  typeId = ref(''),
  rooms = ref<Room[]>([]),
  room = ref<Room | null>(null),
  blocks = ref<Block[]>([]),
  number = ref('')
const form = reactive({ start: day(1), end: day(2), reason: '' }),
  { busy, error, success, run } = useTask()
async function loadRooms() {
  rooms.value = typeId.value ? await catalogApi.rooms(typeId.value) : []
}
onMounted(() =>
  run(async () => {
    types.value = await catalogApi.types(true)
    typeId.value = types.value[0]?.id ?? ''
    await loadRooms()
  }),
)
function showBlocks(selected: Room) {
  void run(async () => {
    room.value = selected
    blocks.value = await catalogApi.blocks(selected.id)
  })
}

function selectType() {
  void run(async () => {
    room.value = null
    await loadRooms()
  })
}

function createRoom() {
  void run(async () => {
    await catalogApi.room(typeId.value, number.value)
    number.value = ''
    await loadRooms()
  }, '房间已创建。')
}

function toggleRoom(item: Room) {
  void run(async () => {
    await catalogApi.active(item.id, !item.active)
    await loadRooms()
  })
}

function createBlock() {
  void run(async () => {
    await catalogApi.block(room.value!.id, form)
    blocks.value = await catalogApi.blocks(room.value!.id)
  }, '封房已创建。')
}

function releaseBlock(block: Block) {
  void run(async () => {
    await catalogApi.unblock(block.id)
    blocks.value = await catalogApi.blocks(room.value!.id)
  })
}
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">ROOM OPERATIONS</div>
      <h1>客房与封房</h1>
      <p>维护实体房间与日期占用；停用不会删除预订记录。</p>
    </div>
  </div>
  <TaskNotice :busy="busy" :error="error" :success="success" />
  <section class="panel">
    <div class="toolbar">
      <label
        >房型<select v-model="typeId" :disabled="busy" @change="selectType">
          <option v-for="type in types" :key="type.id" :value="type.id">
            {{ type.name }}{{ !type.active ? '（已停用）' : '' }}
          </option>
        </select></label
      >
    </div>
    <form class="toolbar" @submit.prevent="createRoom">
      <label
        >新房号<input v-model="number" required maxlength="30" placeholder="例如 A-101" /></label
      ><button class="primary" :disabled="busy || !typeId">添加房间</button>
    </form>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>房号</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in rooms" :key="item.id">
            <td>{{ item.number }}</td>
            <td>{{ item.active ? '启用' : '停用' }}</td>
            <td>
              <div class="row">
                <button :disabled="busy" @click="toggleRoom(item)">
                  {{ item.active ? '停用' : '恢复' }}</button
                ><button :disabled="busy" @click="showBlocks(item)">维护封房</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="!rooms.length" class="empty">该房型暂无实体房间。</p>
  </section>
  <section v-if="room" class="panel">
    <h3>{{ room.number }} · 封房安排</h3>
    <form @submit.prevent="createBlock">
      <fieldset :disabled="busy">
        <div class="grid">
          <label>开始日期<input v-model="form.start" type="date" required /></label
          ><label
            >结束日期（不含）<input v-model="form.end" type="date" :min="form.start" required
          /></label>
        </div>
        <label>封房原因<input v-model="form.reason" required maxlength="500" /></label
        ><button>新增封房</button>
      </fieldset>
    </form>
    <hr class="divider" />
    <div v-for="block in blocks" :key="block.id" class="row spread">
      <p>
        {{ block.start_date }} → {{ block.end_date }} · {{ block.reason }} ·
        {{ block.released ? '已释放' : '生效中' }}
      </p>
      <button v-if="!block.released" :disabled="busy" @click="releaseBlock(block)">解除封房</button>
    </div>
    <p v-if="!blocks.length" class="empty">暂无封房记录。</p>
  </section>
</template>
