<template>
  <section>
    <h2>Import running activity</h2>
    <label>
      FIT file
      <input data-testid="fit-picker" type="file" accept=".fit" @change="select" />
    </label>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="outcome" data-testid="import-outcome">{{ outcome.status }}<span v-if="outcome.activityId">: {{ outcome.activityId }}</span></p>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { importFit, type ImportOutcome } from '../api'

const outcome = ref<ImportOutcome | null>(null)
const error = ref<string | null>(null)

async function select(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  error.value = null
  outcome.value = null
  try {
    outcome.value = await importFit(file)
  } catch (requestError) {
    error.value = requestError instanceof Error ? requestError.message : 'Import failed'
  }
}
</script>
