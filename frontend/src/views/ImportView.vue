<template>
  <section class="import-view">
    <header class="page-heading">
      <div><h1>FIT-Dateien importieren</h1><p>Importiere deine Läufe direkt aus FIT-Dateien.</p></div>
      <a href="/">Zur Übersicht</a>
    </header>
    <section class="upload-panel" aria-labelledby="upload-title">
      <h2 id="upload-title">Dateien auswählen</h2>
      <label class="file-picker">
        FIT-Dateien (.fit)
        <input ref="picker" data-testid="fit-picker" type="file" accept=".fit" multiple aria-label="FIT-Dateien auswählen" @change="select" />
      </label>
      <button
        class="drop-zone"
        data-testid="fit-drop-zone"
        type="button"
        aria-label="FIT-Dateien hier ablegen oder Dateien auswählen"
        @click="picker?.click()"
        @dragover.prevent
        @drop.prevent="drop"
      >
        FIT-Dateien hier ablegen oder Dateien oben auswählen. Mehrere Dateien möglich.
      </button>
      <p v-if="selectionError" role="alert">{{ selectionError }}</p>
      <p v-if="selected.length" data-testid="selection-count" role="status" aria-live="polite">
        {{ selected.length }} {{ selected.length === 1 ? 'Datei' : 'Dateien' }} ausgewählt
      </p>
      <button class="start-button" data-testid="start-import" type="button" :disabled="!selected.length" @click="startImport">
        Import starten
      </button>
    </section>
    <p v-if="batchError" data-testid="batch-error" role="alert">{{ batchError.message }}</p>
    <section v-if="queue.length" aria-labelledby="queue-title">
      <h2 id="queue-title">Importverlauf</h2>
      <ol class="queue" aria-label="Importverlauf">
        <li v-for="item in queue" :key="item.id" :data-testid="`import-row-${item.file.name}`">
          <strong class="file-name">{{ item.file.name }}</strong>
          <span class="queue-state">{{ item.state }}</span>
          <ImportOutcomeBadge v-if="isOutcome(item.state)" :status="item.state" />
          <span class="queue-message">{{ item.message }}</span>
          <button v-if="item.state === 'FAILED'" class="retry-button" :data-testid="`retry-${item.file.name}`" type="button" @click="retry(item)">Erneut versuchen</button>
        </li>
      </ol>
    </section>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { importFits, type ImportBatchError, type ImportOutcome } from '../api'
import ImportOutcomeBadge from '../components/ImportOutcomeBadge.vue'

type QueueState = 'PENDING' | 'PROCESSING' | ImportOutcome['status']
type QueueItem = { id: number; file: File; state: QueueState; message: string }

const picker = ref<HTMLInputElement | null>(null)
const queue = ref<QueueItem[]>([])
const selected = ref<QueueItem[]>([])
const batchError = ref<ImportBatchError | null>(null)
const selectionError = ref('')
let nextId = 1
const isOutcome = (state: QueueState): state is ImportOutcome['status'] =>
  !['PENDING', 'PROCESSING'].includes(state)

const messageFor = (outcome: ImportOutcome) => {
  if (outcome.status === 'IMPORTED') return 'Erfolgreich importiert.'
  if (outcome.status === 'DUPLICATE') return 'Diese Datei wurde bereits importiert.'
  if (outcome.status === 'SKIPPED_NON_RUN') return 'Übersprungen: Die Datei enthält kein Lauftraining.'
  if (outcome.status === 'INVALID') return 'Ungültige FIT-Datei; sie wurde nicht importiert.'
  return 'Import fehlgeschlagen. Du kannst es erneut versuchen.'
}

function addFiles(files: File[]) {
  selectionError.value = files.some((file) => !file.name.toLowerCase().endsWith('.fit'))
    ? 'Nur .fit-Dateien können importiert werden.'
    : ''
  const items = files.filter((file) => file.name.toLowerCase().endsWith('.fit'))
    .map((file) => ({ id: nextId++, file, state: 'PENDING' as const, message: 'Import starten, um die ausgewählten Dateien zu verarbeiten.' }))
  queue.value.push(...items)
  selected.value.push(...items)
}

function startImport() {
  const items = [...selected.value]
  if (!items.length) return
  selected.value = []
  void upload(items)
}

async function upload(items: QueueItem[]) {
  items.forEach((item) => { item.state = 'PROCESSING'; item.message = 'Import wird verarbeitet …' })
  batchError.value = null
  try {
    const result = await importFits(items.map((item) => item.file))
    if (result.batchError) {
      batchError.value = result.batchError
      items.forEach((item) => { item.state = 'PENDING'; item.message = 'Warte auf eine korrigierte Auswahl.' })
      selected.value.push(...items)
      return
    }
    items.forEach((item, index) => applyOutcome(item, result.outcomes[index]))
  } catch {
    items.forEach((item) => { item.state = 'FAILED'; item.message = 'Import fehlgeschlagen. Erneut versuchen.' })
  }
}

function applyOutcome(item: QueueItem, outcome: ImportOutcome | undefined) {
  if (!outcome) {
    item.state = 'FAILED'
    item.message = 'Der Server hat kein Ergebnis für diese Datei zurückgegeben. Erneut versuchen.'
    return
  }
  item.state = outcome.status
  item.message = messageFor(outcome)
}

function select(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files ?? [])
  if (files.length) addFiles(files)
  ;(event.target as HTMLInputElement).value = ''
}

function drop(event: DragEvent) {
  const files = Array.from(event.dataTransfer?.files ?? [])
  if (files.length) addFiles(files)
}

async function retry(item: QueueItem) {
  if (item.state !== 'FAILED') return
  await upload([item])
}
</script>

<style scoped>
.import-view { display: grid; gap: 1.5rem; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
.page-heading h1 { margin: 0; color: var(--color-ink); font-size: clamp(1.5rem, 3vw, 2rem); line-height: 1.2; }
.page-heading p { margin: .5rem 0 0; color: var(--color-muted); }
.page-heading a { min-height: 44px; display: inline-flex; align-items: center; }
.upload-panel { display: grid; justify-items: start; gap: 1rem; padding: 1.5rem; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-card); }
.upload-panel h2, .import-view section[aria-labelledby="queue-title"] h2 { margin: 0; color: var(--color-ink); font-size: 1.125rem; }
.file-picker { display: grid; gap: .5rem; font-weight: 600; }
.file-picker input { max-width: 100%; min-height: 44px; }
.drop-zone { width: 100%; min-height: 7rem; padding: 1rem; color: var(--color-muted); background: var(--color-canvas); border: 2px dashed #9aabba; border-radius: var(--radius-control); cursor: pointer; }
.drop-zone:hover { border-color: var(--color-accent-strong); background: var(--color-accent-subtle); }
.start-button, .retry-button { min-height: 44px; padding: .625rem 1rem; color: white; background: var(--color-accent-strong); border: 0; border-radius: var(--radius-control); font-weight: 600; cursor: pointer; }
.start-button:disabled { color: #52657b; background: #e3e8ed; cursor: not-allowed; }
.queue { display: grid; gap: .75rem; margin: 0; padding: 0; list-style: none; }
.queue li { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: .5rem 1rem; min-width: 0; padding: 1rem; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-card); }
.file-name { min-width: 0; overflow-wrap: anywhere; }
.queue-state { color: var(--color-muted); font-size: .8125rem; }
.queue-message { grid-column: 1; color: var(--color-muted); font-size: .875rem; }
.retry-button { grid-column: 2; grid-row: 2; }
@media (max-width: 639px) {
  .page-heading { align-items: flex-start; flex-direction: column; }
  .upload-panel { padding: 1rem; }
  .queue li { grid-template-columns: minmax(0, 1fr); }
  .queue-state, .queue-message, .retry-button { grid-column: 1; grid-row: auto; }
}
</style>
