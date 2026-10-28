<script setup lang="ts">
defineProps<{ busy?: boolean; error?: string; success?: string }>()
</script>
<template>
  <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  <p v-if="success" class="notice success" role="status">{{ success }}</p>
  <p v-if="busy" class="loading" role="status">正在处理，请稍候…</p>
</template>
