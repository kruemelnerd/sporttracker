<template>
  <section>
    <h2>Fixture summary</h2>
    <p v-if="loading" role="status">Loading fixture summary...</p>
    <p v-else-if="error" role="alert">Could not load the fixture summary: {{ error }}</p>
    <dl v-else-if="summary" data-testid="fixture-summary">
      <div><dt>Sport</dt><dd data-testid="sport">{{ summary.sport }}</dd></div>
      <div><dt>Start time</dt><dd data-testid="start-time">{{ formatStartTime(summary.startTime) }}</dd></div>
      <div><dt>Distance</dt><dd data-testid="distance">{{ formatDistance(summary.distanceMeters) }}</dd></div>
      <div><dt>Status</dt><dd data-testid="status">{{ summary.status }}</dd></div>
      <div><dt>Records</dt><dd data-testid="record-count">{{ summary.recordCount }}</dd></div>
      <div><dt>Laps</dt><dd data-testid="lap-count">{{ summary.lapCount }}</dd></div>
    </dl>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getFixtureSummary, type FixtureSummary } from '../api'

const loading = ref(true)
const error = ref<string | null>(null)
const summary = ref<FixtureSummary | null>(null)

const formatStartTime = (value: string) => new Intl.DateTimeFormat(undefined, {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'UTC',
}).format(new Date(value)) + ' UTC'

const formatDistance = (value: number | null) => value === null ? 'n/a' : `${(value / 1000).toFixed(2)} km`

onMounted(async () => {
  try {
    summary.value = await getFixtureSummary()
  } catch (requestError) {
    error.value = requestError instanceof Error ? requestError.message : 'Unknown request error'
  } finally {
    loading.value = false
  }
})
</script>
