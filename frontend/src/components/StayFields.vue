<script setup lang="ts">
import type { RoomType, StayInput } from '@/api/types'
import { day } from '@/shared/format'
const model = defineModel<StayInput>({ required: true })
defineProps<{ types: RoomType[] }>()
</script>
<template>
  <div class="grid">
    <label>入住日期<input v-model="model.checkIn" type="date" :min="day(0)" required /></label
    ><label
      >离店日期<input v-model="model.checkOut" type="date" :min="model.checkIn" required
    /></label>
  </div>
  <div v-for="(selection, index) in model.rooms" :key="index" class="selection-row">
    <label
      >房型<select v-model="selection.roomTypeId" required>
        <option value="" disabled>请选择房型</option>
        <option v-for="type in types" :key="type.id" :value="type.id">
          {{ type.name }}（最多{{ type.capacity }}人）
        </option>
      </select></label
    ><label
      >房间数<input
        v-model.number="selection.rooms"
        type="number"
        min="1"
        max="10"
        required /></label
    ><label
      >每间人数<input
        v-model.number="selection.guestsPerRoom"
        type="number"
        min="1"
        :max="types.find((t) => t.id === selection.roomTypeId)?.capacity ?? 20"
        required /></label
    ><button
      type="button"
      :disabled="model.rooms.length === 1"
      @click="model.rooms.splice(index, 1)"
    >
      移除
    </button>
  </div>
  <button
    type="button"
    :disabled="model.rooms.length >= 10"
    @click="model.rooms.push({ roomTypeId: '', rooms: 1, guestsPerRoom: 1 })"
  >
    ＋ 添加其他房型
  </button>
</template>
