<template>
  <section class="import-view">
    <h2>Import running activities</h2>
    <label>
      FIT files
      <input ref="picker" data-testid="fit-picker" type="file" accept=".fit" multiple @change="select" />
    </label>
    <div
      class="drop-zone"
      data-testid="fit-drop-zone"
      role="button"
      tabindex="0"
      @click="picker?.click()"
      @keydown.enter.prevent="picker?.click()"
      @keydown.space.prevent="picker?.click()"
      @dragover.prevent
      @drop.prevent="drop"
    >
      Drop FIT files here or choose files above.
    </div>
    <p v-if="batchError" data-testid="batch-error" role="alert">{{ batchError.message }}</p>
    <ol aria-label="Import queue">
      <li v-for="item in queue" :key="item.id" :data-testid="`import-row-${item.file.name}`">
        <strong>{{ item.file.name }}</strong>: {{ item.state }} — {{ item.message }}
        <button v-if="item.state === 'FAILED'" :data-testid="`retry-${item.file.name}`" type="button" @click="retry(item)">Retry</button>
      </li>
    </ol>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { importFits, type ImportBatchError, type ImportOutcome } from '../api'

type QueueState = 'PENDING' | 'PROCESSING' | ImportOutcome['status']
type QueueItem = { id: number; file: File; state: QueueState; message: string }

const picker = ref<HTMLInputElement | null>(null)
const queue = ref<QueueItem[]>([])
const batchError = ref<ImportBatchError | null>(null)
let nextId = 1

const messageFor = (outcome: ImportOutcome) => {
  if (outcome.status === 'IMPORTED') return 'Imported successfully.'
  if (outcome.status === 'DUPLICATE') return 'Already imported.'
  if (outcome.status === 'SKIPPED_NON_RUN') return 'Skipped because this is not a running activity.'
  if (outcome.status === 'INVALID') return 'This FIT file is invalid and was not imported.'
  return 'Import failed. Retry this file.'
}

function addFiles(files: File[]) {
  const items = files.map((file) => ({ id: nextId++, file, state: 'PENDING' as const, message: 'Waiting to import.' }))
  queue.value.push(...items)
  void upload(queue.value.slice(-items.length))
}

async function upload(items: QueueItem[]) {
  items.forEach((item) => { item.state = 'PROCESSING'; item.message = 'Importing…' })
  batchError.value = null
  try {
    const result = await importFits(items.map((item) => item.file))
    if (result.batchError) {
      batchError.value = result.batchError
      items.forEach((item) => { item.state = 'PENDING'; item.message = 'Waiting for a corrected selection.' })
      return
    }
    items.forEach((item, index) => applyOutcome(item, result.outcomes[index]))
  } catch {
    items.forEach((item) => { item.state = 'FAILED'; item.message = 'Import failed. Retry this file.' })
  }
}

function applyOutcome(item: QueueItem, outcome: ImportOutcome | undefined) {
  if (!outcome) {
    item.state = 'FAILED'
    item.message = 'The server did not return a result for this file. Retry it.'
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
