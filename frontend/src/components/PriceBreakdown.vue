<script setup lang="ts">
import type { Snapshot } from '@/api/types'
import { money } from '@/shared/format'
defineProps<{ snapshot: Snapshot }>()
</script>
<template>
  <details class="panel">
    <summary>逐晚价格明细 · {{ snapshot.checkIn }} 至 {{ snapshot.checkOut }}</summary>
    <p>适用折扣比例 {{ snapshot.discountRatio }}</p>
    <section v-for="(room, i) in snapshot.rooms" :key="i">
      <h4>{{ room.roomTypeName }} · 第 {{ i + 1 }} 间 · {{ room.guests }} 人</h4>
      <div v-for="night in room.nights" :key="night.date" class="row">
        <span>{{ night.date }}</span
        ><span>原价 {{ money(night.basePrice) }} / 应付 {{ money(night.payablePrice) }}</span>
      </div>
    </section>
  </details>
</template>
