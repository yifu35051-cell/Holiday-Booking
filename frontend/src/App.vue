<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>
<template>
  <RouterView v-slot="{ Component, route }"
    ><component :is="Component" :key="String(route.meta.role ?? route.path)"
  /></RouterView>
</template>
