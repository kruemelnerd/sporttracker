<template>
  <div class="metric-card" :class="{ 'is-emphasized': emphasis }">
    <dt>{{ label }}</dt>
    <dd>{{ value }}</dd>
  </div>
</template>

<script setup lang="ts">
defineProps<{ label: string; value: string; emphasis?: boolean }>()
</script>

<style scoped>
.metric-card {
  min-width: 0;
  padding: 1rem;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card);
}

dt { color: var(--color-muted); font-size: 0.8125rem; font-weight: 600; }
dd { margin: 0.25rem 0 0; color: var(--color-ink); font-size: 1.5rem; font-weight: 700; line-height: 1.15; overflow-wrap: anywhere; }
.is-emphasized { background: var(--color-accent-subtle); border-color: var(--color-accent); }
</style>
