<template>
  <svg class="activity-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <circle cx="15.5" cy="4.5" r="2" fill="currentColor" />
    <path d="m12.3 8.1 3.1 1.2 2.1 3.2 3 .5M11 8.7l-2.8 3.1-3.6 1.4M14 9.2l-2 4.1 2.8 2.1 1.1 4M11.8 13.2l-3.1 3.1-3.2 2.2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
  </svg>
</template>

<script setup lang="ts">
defineProps<{ subSport: string | null }>()
</script>

<style scoped>
.activity-icon { display: block; width: 1.5rem; height: 1.5rem; color: var(--color-accent-strong); }
</style>
