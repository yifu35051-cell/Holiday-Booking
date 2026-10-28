<script setup lang="ts">
defineProps<{ page: number; total?: number; count: number; size?: number; busy?: boolean }>()
defineEmits<{ change: [page: number] }>()
</script>
<template>
  <nav class="pager" aria-label="分页">
    <button :disabled="busy || page === 0" @click="$emit('change', page - 1)">上一页</button
    ><span
      >第 {{ page + 1 }} 页<span v-if="total !== undefined"> · 共 {{ total }} 条</span></span
    ><button
      :disabled="
        busy || (total !== undefined ? (page + 1) * (size ?? 20) >= total : count < (size ?? 20))
      "
      @click="$emit('change', page + 1)"
    >
      下一页
    </button>
  </nav>
</template>
