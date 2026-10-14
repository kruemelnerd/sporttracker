<template>
  <span class="outcome-badge" :class="`outcome-${presentation.tone}`">
    <span aria-hidden="true">{{ presentation.icon }}</span>
    {{ presentation.label }}
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ImportOutcome } from '../api'

const props = defineProps<{ status: ImportOutcome['status'] }>()
const presentation = computed(() => ({
  IMPORTED: { label: 'Importiert', tone: 'success', icon: '✓' },
  DUPLICATE: { label: 'Bereits importiert', tone: 'info', icon: '↻' },
  SKIPPED_NON_RUN: { label: 'Kein Lauftraining', tone: 'warning', icon: '!' },
  INVALID: { label: 'Ungültige FIT-Datei', tone: 'danger', icon: '!' },
  FAILED: { label: 'Import fehlgeschlagen', tone: 'danger', icon: '!' },
}[props.status]))
</script>

<style scoped>
.outcome-badge { display: inline-flex; align-items: center; gap: 0.375rem; padding: 0.25rem 0.625rem; border-radius: 999px; font-size: 0.8125rem; font-weight: 600; }
.outcome-success { color: var(--color-success); background: #e8f7f1; }
.outcome-info { color: var(--color-info); background: #eaf3fb; }
.outcome-warning { color: var(--color-warning); background: #fff5df; }
.outcome-danger { color: var(--color-danger); background: #fff0f0; }
</style>
