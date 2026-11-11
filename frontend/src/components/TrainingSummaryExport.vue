<template>
  <section class="detail-panel export-panel" aria-labelledby="training-summary-heading">
    <h2 id="training-summary-heading">Trainingszusammenfassung</h2>
    <div class="export-controls">
      <label class="format-control" for="training-summary-format">
        Format
        <select id="training-summary-format" v-model="format" aria-label="Format">
          <option value="json">JSON</option>
          <option value="yaml">YAML</option>
        </select>
      </label>
      <label class="splits-control">
        <input v-model="includeSplits" type="checkbox" aria-label="Runden/Splits hinzufügen">
        Runden/Splits hinzufügen
      </label>
    </div>
    <label class="preview-control" for="training-summary-preview">Vorschau</label>
    <textarea id="training-summary-preview" aria-label="Vorschau der Trainingszusammenfassung" :value="preview" readonly spellcheck="false" rows="12"></textarea>
    <button class="copy-button" type="button" aria-label="Für ChatGPT kopieren" @click="copySummary">Für ChatGPT kopieren</button>
    <p v-if="copied" role="status">Trainingszusammenfassung in die Zwischenablage kopiert.</p>
    <p v-if="copyError" role="alert">Kopieren nicht möglich. Bitte Berechtigung prüfen oder die Vorschau manuell kopieren.</p>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ActivityDetail } from '../api'
import { createTrainingSummary, serializeTrainingSummaryJson, serializeTrainingSummaryYaml } from '../activityDetail'

const props = defineProps<{ detail: ActivityDetail }>()
const format = ref<'json' | 'yaml'>('json')
const includeSplits = ref(false)
const copied = ref(false)
const copyError = ref(false)
const preview = computed(() => {
  const summary = createTrainingSummary(props.detail, includeSplits.value)
  return format.value === 'json' ? serializeTrainingSummaryJson(summary) : serializeTrainingSummaryYaml(summary)
})

watch(preview, () => { copied.value = false; copyError.value = false })

async function copySummary() {
  copied.value = false
  copyError.value = false
  try {
    await navigator.clipboard.writeText(preview.value)
    copied.value = true
  } catch {
    copyError.value = true
  }
}
</script>

<style scoped>
.export-panel { display: grid; grid-template-columns: minmax(0, 1fr); gap: .875rem; }
.export-panel > * { min-width: 0; }
.export-panel h2 { margin-bottom: 0; }
.export-panel h2 { overflow-wrap: anywhere; }
.export-controls { display: flex; flex-wrap: wrap; align-items: end; gap: 1rem; }
.format-control { display: grid; gap: .375rem; min-width: 8rem; }
.splits-control { display: inline-flex; align-items: center; gap: .5rem; min-height: 44px; }
select, textarea, .copy-button { font: inherit; color: var(--color-ink); border: 1px solid var(--color-border); border-radius: var(--radius-control); }
select { min-height: 44px; padding: .5rem .75rem; background: var(--color-surface); }
input, button, select { accent-color: var(--color-accent-strong); }
input, button { cursor: pointer; }
.preview-control { font-weight: 600; }
textarea { width: 100%; max-width: 100%; min-width: 0; padding: .75rem; resize: vertical; overflow: auto; background: var(--color-canvas); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: .875rem; line-height: 1.5; white-space: pre; }
.copy-button { min-height: 44px; width: fit-content; padding: .625rem 1rem; background: var(--color-accent); color: var(--color-ink); font-weight: 600; transition: filter 180ms ease; }
.copy-button:hover { filter: brightness(.92); }
.copy-button:focus-visible, select:focus-visible, input:focus-visible, textarea:focus-visible { outline: 3px solid var(--color-info); outline-offset: 2px; }
@media (max-width: 767px) {
  .export-controls, .format-control, .copy-button { width: 100%; }
  .copy-button { min-height: 48px; }
}
</style>
